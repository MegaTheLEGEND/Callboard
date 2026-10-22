"""Export ONE RVC voice (.pth) as a static-shape, NPU-friendly ONNX synthesizer.

What "specialised to one model" buys us here
--------------------------------------------
* speaker id baked in      -> emb_g lookup + every g-conditioning 1x1 conv is
                              constant-folded away
* weight-norm removed      -> plain convs
* fixed head / length      -> no dynamic slicing, no CUDA graphs, no If/Loop
* all-ones length mask     -> attention mask folds to constants
* sine excitation outside  -> the cumsum/sin NSF source is computed on the CPU
                              (engine) and enters as input `har`; the NPU only
                              sees convs / matmuls / leaky-relu / tanh
* z_p noise outside        -> `noise` input (engine fills it with N(0,1), or
                              zeros for a deterministic, slightly cleaner mode)

Graph signature (B=1, all float32 except pitch):
    feats  [1, hubert_frames+1, feat_dim]   index-blended HuBERT features
    pitch  [1, window]            int64     coarse pitch 1..255
    har    [1, 1, return_length*upp]        NSF excitation (CPU)
    noise  [1, inter_channels, window-flow_head]
  -> audio [1, 1, return_length*upp]        at tgt_sr
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from geometry import Geometry  # noqa: E402
from rvc_core import modules as rvc_modules  # noqa: E402
from rvc_core.models import (  # noqa: E402
    SynthesizerTrnMs256NSFsid,
    SynthesizerTrnMs768NSFsid,
)


# --------------------------------------------------------------------------- #
# loading
# --------------------------------------------------------------------------- #
def load_checkpoint(pth: str):
    if os.environ.get("RVC_STRICT_PICKLE") == "1":
        # Set by convert_server.py: the file came from a phone, so never run pickle code.
        cpt = torch.load(pth, map_location="cpu", weights_only=True)
    else:
        cpt = torch.load(pth, map_location="cpu", weights_only=False)
    cpt["config"][-3] = cpt["weight"]["emb_g.weight"].shape[0]
    if cpt.get("f0", 1) != 1:
        raise SystemExit("This exporter targets pitch-guided (f0=1) models; "
                         "the checkpoint was trained without f0.")
    version = cpt.get("version", "v1")
    cls = SynthesizerTrnMs768NSFsid if version == "v2" else SynthesizerTrnMs256NSFsid
    net_g = cls(*cpt["config"], is_half=False)
    del net_g.enc_q  # posterior encoder is training-only
    net_g.load_state_dict(cpt["weight"], strict=False)
    net_g = net_g.float().eval()
    net_g.remove_weight_norm()
    return net_g, cpt, version


# --------------------------------------------------------------------------- #
# the static wrapper that gets exported
# --------------------------------------------------------------------------- #
class SynthCore(nn.Module):
    def __init__(self, net_g, geo: Geometry, sid: int = 0):
        super().__init__()
        self.net_g = net_g
        self.geo = geo
        # speaker embedding is a constant: [1, gin, 1]
        g = net_g.emb_g(torch.tensor([sid])).unsqueeze(-1).detach()
        self.register_buffer("g", g, persistent=False)
        # attention length mask is all ones for a full window
        self.register_buffer(
            "lengths", torch.tensor([geo.window], dtype=torch.long), persistent=False
        )

    def forward(self, feats, pitch, har, noise):
        geo, net = self.geo, self.net_g

        # RVC: hubert feats -> nearest x2 upsample -> crop to window.
        # (feats already has the duplicated last frame appended by the engine)
        b, t, c = feats.shape
        phone = feats.unsqueeze(2).expand(b, t, 2, c).reshape(b, t * 2, c)
        phone = phone[:, : geo.window, :]

        m_p, logs_p, x_mask = net.enc_p(phone, pitch, self.lengths, geo.flow_head)
        z_p = (m_p + torch.exp(logs_p) * noise * 0.66666) * x_mask
        z = net.flow(z_p, x_mask, g=self.g, reverse=True)
        z = z[:, :, geo.dec_head : geo.dec_head + geo.return_length]
        x_mask = x_mask[:, :, geo.dec_head : geo.dec_head + geo.return_length]
        return self._decode(z * x_mask, har)

    def _decode(self, x, har):
        """GeneratorNSF.forward minus the (CPU-side) source module."""
        dec = self.net_g.dec
        x = dec.conv_pre(x)
        x = x + dec.cond(self.g)
        nk = dec.num_kernels
        for i, (ups, noise_conv) in enumerate(zip(dec.ups, dec.noise_convs)):
            x = F.leaky_relu(x, dec.lrelu_slope)
            x = ups(x)
            x = x + noise_conv(har)
            xs = None
            for j in range(nk):
                r = dec.resblocks[i * nk + j](x)
                xs = r if xs is None else xs + r
            x = xs / nk
        x = F.leaky_relu(x)
        x = dec.conv_post(x)
        return torch.tanh(x)


# --------------------------------------------------------------------------- #
def make_example_inputs(geo: Geometry, seed: int = 0):
    g = torch.Generator().manual_seed(seed)
    feats = torch.randn(1, geo.hubert_frames + 1, geo.feat_dim, generator=g)
    pitch = torch.randint(1, 256, (1, geo.window), generator=g)
    har = torch.randn(1, 1, geo.out_samples, generator=g) * 0.1
    noise = torch.randn(1, geo.inter_channels, geo.window - geo.flow_head, generator=g)
    return feats, pitch, har, noise


def export(args) -> None:
    net_g, cpt, version = load_checkpoint(args.pth)
    tgt_sr = int(cpt["config"][-1])
    upp = int(np.prod(cpt["config"][12]))  # upsample_rates
    geo = Geometry(
        block=args.block, extra=args.extra, crossfade=args.crossfade,
        tgt_sr=tgt_sr, version=version,
        feat_dim=768 if version == "v2" else 256,
        inter_channels=int(cpt["config"][2]), upp=upp,
    )
    print(f"[synth] {version} {tgt_sr} Hz  window={geo.window} frames  "
          f"decode={geo.return_length} frames -> {geo.out_samples} samples")

    core = SynthCore(net_g, geo, sid=args.sid).eval()
    inputs = make_example_inputs(geo)
    with torch.no_grad():
        ref = core(*inputs)
    assert ref.shape == (1, 1, geo.out_samples), ref.shape

    os.makedirs(args.out, exist_ok=True)
    path = os.path.join(args.out, "synth.onnx")
    torch.onnx.export(
        core, inputs, path,
        input_names=["feats", "pitch", "har", "noise"],
        output_names=["audio"],
        opset_version=17,
        do_constant_folding=True,
        dynamo=False,
    )
    print("[synth] wrote", path, f"{os.path.getsize(path)/1e6:.1f} MB")

    # ---- constants the CPU side of the engine needs ----------------------
    lin = net_g.dec.m_source.l_linear
    meta = geo.to_dict()
    meta.update(
        sid=args.sid,
        source_lin_w=float(lin.weight.detach().reshape(-1)[0]),
        source_lin_b=float(lin.bias.detach().reshape(-1)[0]),
        sine_amp=0.1, noise_std=0.003, z_noise_scale=0.66666,
    )
    with open(os.path.join(args.out, "config.json"), "w") as f:
        json.dump(meta, f, indent=2)
    print("[synth] wrote config.json")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pth", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--sid", type=int, default=0, help="speaker id to bake in")
    ap.add_argument("--block", type=int, default=10, help="new audio per step, x10 ms")
    ap.add_argument("--extra", type=int, default=50, help="left context, x10 ms")
    ap.add_argument("--crossfade", type=int, default=5, help="crossfade, x10 ms")
    export(ap.parse_args())


if __name__ == "__main__":
    main()

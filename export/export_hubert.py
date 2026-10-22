"""Export the HuBERT / ContentVec feature extractor RVC uses (Transformers format).

    hubert.onnx   audio16k [1, window*160] -> feats [1, hubert_frames, feat_dim]

* v2 voices: last hidden state (768-d)            -> `feat_dim=768`
* v1 voices: hidden_states[9] -> final_proj (256-d)
* eager attention (no SDPA op), weight-norm positional conv folded to constants
* optional per-window normalisation is baked in when preprocessor_config.json
  says `do_normalize: true` (RVC's hubert_base normally says false)

Get the weights with (see the RVC README):
    huggingface-cli download lj1995/VoiceConversionWebUI --include "hubert_base/*" --local-dir assets
and pass --hubert-dir assets/hubert_base .  For pipeline testing without the
weights use --random-init (architecture-identical, meaningless features).
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import torch
import torch.nn as nn
from transformers import HubertConfig, HubertModel

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from geometry import Geometry  # noqa: E402


class HubertModelWithFinalProj(HubertModel):
    def __init__(self, config):
        super().__init__(config)
        self.final_proj = nn.Linear(config.hidden_size, config.classifier_proj_size)


class HubertGraph(nn.Module):
    def __init__(self, model: HubertModelWithFinalProj, version: str, normalize: bool):
        super().__init__()
        self.m = model
        self.version = version
        self.normalize = normalize

    def forward(self, audio):                         # [1, N]
        x = audio
        if self.normalize:
            x = (x - x.mean(-1, keepdim=True)) / torch.sqrt(x.var(-1, keepdim=True, unbiased=False) + 1e-7)
        if self.version == "v1":
            out = self.m(input_values=x, output_hidden_states=True, return_dict=True)
            return self.m.final_proj(out.hidden_states[9])
        return self.m(input_values=x, return_dict=True).last_hidden_state


def load_model(hubert_dir: str | None, random_init: bool):
    kw = dict(attn_implementation="eager")
    if random_init:
        cfg = HubertConfig(classifier_proj_size=256)
        cfg._attn_implementation = "eager"
        model = HubertModelWithFinalProj(cfg)
        normalize = False
    else:
        model, info = HubertModelWithFinalProj.from_pretrained(hubert_dir, local_files_only=True, output_loading_info=True,
                                                               torch_dtype=torch.float32, **kw)
        # Weights that fail to load stay RANDOM and give hiss / beep-boop output, so refuse to export.
        miss = [k for k in list(info.get("missing_keys", [])) if "masked_spec_embed" not in k]
        print("[hubert] missing keys:", miss, "| unexpected:", list(info.get("unexpected_keys", []))[:10])
        if miss:
            raise SystemExit(f"HuBERT weights did not load (random init would be exported): {miss[:10]}")
        pp = os.path.join(hubert_dir, "preprocessor_config.json")
        normalize = bool(json.load(open(pp)).get("do_normalize", False)) if os.path.isfile(pp) else False
    return model.float().eval(), normalize


def export(args) -> None:
    geo = Geometry.load(os.path.join(args.out, "config.json"))   # written by export_synth.py
    model, normalize = load_model(args.hubert_dir, args.random_init)
    graph = HubertGraph(model, geo.version, normalize).eval()
    audio = torch.randn(1, geo.hubert_samples) * 0.1
    with torch.no_grad():
        ref = graph(audio)
    print(f"[hubert] {geo.version} normalize={normalize}  in={tuple(audio.shape)} out={tuple(ref.shape)}")
    expect = (1, geo.hubert_frames, geo.feat_dim)
    assert tuple(ref.shape) == expect, f"shape {tuple(ref.shape)} != geometry {expect}"

    path = os.path.join(args.out, "hubert.onnx")
    torch.onnx.export(graph, (audio,), path, input_names=["audio"], output_names=["feats"],
                      opset_version=17, do_constant_folding=True, dynamo=False)
    print("[hubert] wrote", path, f"{os.path.getsize(path)/1e6:.1f} MB")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", required=True, help="build dir that already holds config.json")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--hubert-dir", help="assets/hubert_base (Transformers format)")
    g.add_argument("--random-init", action="store_true", help="architecture-only test mode")
    export(ap.parse_args())

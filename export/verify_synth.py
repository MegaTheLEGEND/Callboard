"""Verify synth.onnx against the ORIGINAL RVC `net_g.infer` and audit its ops.

1. Runs the original `SynthesizerTrnMs768NSFsid.infer(...)` (skip_head /
   return_length path, exactly as rtrvc.py does) with all randomness zeroed.
2. Runs our exported synth.onnx with `har` produced by sinegen_ref.py.
3. Compares waveforms.
4. Lists every ONNX op type and flags anything outside the QNN-EP HTP list.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter

import numpy as np
import onnx
import onnxruntime as ort
import torch

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_synth import load_checkpoint  # noqa: E402
from geometry import Geometry  # noqa: E402
from sinegen_ref import sine_excitation  # noqa: E402

# ai.onnx ops the QNN EP documents as supported (subset relevant to our graphs)
QNN_OK = {
    "Abs", "Add", "And", "ArgMax", "Cast", "Ceil", "Clip", "Concat", "Constant",
    "ConstantOfShape", "Conv", "ConvTranspose", "Cos", "CumSum", "Div", "Equal",
    "Erf", "Exp", "Expand", "Flatten", "Floor", "Gather", "GatherElements",
    "GatherND", "Gelu", "Gemm", "Greater", "GreaterOrEqual", "Identity",
    "LayerNormalization", "LeakyRelu", "Less", "LessOrEqual", "Log", "MatMul",
    "Max", "Min", "Mul", "Neg", "Not", "Pad", "Pow", "Reciprocal", "ReduceMax",
    "ReduceMean", "ReduceMin", "ReduceSum", "Relu", "Reshape", "Resize", "Round",
    "Shape", "Sigmoid", "Sign", "Sin", "Slice", "Softmax", "Split", "Sqrt",
    "Squeeze", "Sub", "Tanh", "Tile", "Transpose", "Unsqueeze", "Where",
    "GroupNormalization", "InstanceNormalization", "STFT", "Sum", "Mod", "Or",
    "ReduceL2", "ReduceProd", "Softplus", "HardSigmoid", "HardSwish",
}


def audit_ops(path: str) -> Counter:
    m = onnx.load(path)
    ops = Counter(n.op_type for n in m.graph.node)
    print(f"\n[ops] {path}: {sum(ops.values())} nodes")
    bad = {k: v for k, v in ops.items() if k not in QNN_OK}
    for k, v in sorted(ops.items(), key=lambda kv: -kv[1]):
        print(f"   {'!!' if k in bad else '  '} {k:24s} {v}")
    if bad:
        print("   !! ops not in the QNN list (will fall back to CPU):", sorted(bad))
    else:
        print("   all ops are in the QNN-EP supported list")
    # any dynamic dim left?
    for t in list(m.graph.input) + list(m.graph.output):
        dims = [d.dim_value if d.dim_value else d.dim_param for d in t.type.tensor_type.shape.dim]
        print(f"   io {t.name:8s} {dims}")
    return ops


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pth", required=True)
    ap.add_argument("--build", required=True, help="dir containing the onnx + config.json")
    ap.add_argument("--file", default="synth.onnx", help="onnx file name inside --build")
    args = ap.parse_args()

    meta = json.load(open(os.path.join(args.build, "config.json")))
    geo = Geometry.load(os.path.join(args.build, "config.json"))
    net_g, cpt, version = load_checkpoint(args.pth)

    rng = np.random.default_rng(1)
    feats = rng.standard_normal((1, geo.hubert_frames + 1, geo.feat_dim)).astype(np.float32)
    pitch = rng.integers(1, 256, (1, geo.window)).astype(np.int64)
    f0 = rng.uniform(110, 330, geo.window).astype(np.float32)
    f0[20:26] = 0.0            # include an unvoiced stretch
    f0[55:58] = 0.0            # ... and one inside the decoded region

    # ---- original path (all randomness zeroed) ---------------------------
    t_feats = torch.from_numpy(feats)
    phone = t_feats.unsqueeze(2).expand(1, t_feats.shape[1], 2, geo.feat_dim)
    phone = phone.reshape(1, -1, geo.feat_dim)[:, : geo.window]
    orig_randn_like = torch.randn_like
    torch.randn_like = lambda x, *a, **k: torch.zeros_like(x)
    try:
        with torch.no_grad():
            o_ref = net_g.infer(
                phone, torch.tensor([geo.window]), torch.from_numpy(pitch),
                torch.from_numpy(f0)[None], torch.tensor([meta["sid"]]),
                geo.skip_head, geo.return_length, geo.return_length,
            )[0].squeeze().numpy()
    finally:
        torch.randn_like = orig_randn_like

    # ---- our path ---------------------------------------------------------
    region = f0[geo.skip_head : geo.skip_head + geo.return_length]
    har = sine_excitation(region, geo.tgt_sr, geo.upp,
                          meta["source_lin_w"], meta["source_lin_b"])
    noise = np.zeros((1, geo.inter_channels, geo.window - geo.flow_head), np.float32)
    sess = ort.InferenceSession(os.path.join(args.build, args.file),
                                providers=["CPUExecutionProvider"])
    o_onnx = sess.run(None, {"feats": feats, "pitch": pitch,
                             "har": har[None, None, :], "noise": noise})[0].squeeze()

    err = np.abs(o_ref - o_onnx)
    print(f"[parity] samples={o_ref.size}  max|err|={err.max():.2e}  "
          f"mean|err|={err.mean():.2e}  ref rms={np.sqrt((o_ref**2).mean()):.4f}")
    ok = err.max() < 2e-3
    print("[parity]", "PASS" if ok else "FAIL")

    audit_ops(os.path.join(args.build, args.file))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

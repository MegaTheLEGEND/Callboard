"""Verify the exported FCPE pipeline (mel.onnx -> net.onnx -> FP32 decode).

Compares against (a) torchfcpe itself and (b) the known pitch of a synthetic
glissando, and audits the ops of both graphs.
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import onnxruntime as ort

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_fcpe import local_argmax_decode  # noqa: E402
from verify_synth import audit_ops  # noqa: E402


def glide(n: int, f_start: float, f_end: float, sr: int = 16000) -> np.ndarray:
    f = np.linspace(f_start, f_end, n)
    phase = 2 * np.pi * np.cumsum(f) / sr
    y = sum(np.sin(h * phase) / h for h in range(1, 12))      # harmonic-rich "voice"
    return (0.3 * y / np.abs(y).max()).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", required=True)
    ap.add_argument("--mel", default="fcpe_mel.onnx")
    ap.add_argument("--net", default="fcpe_net.onnx")
    a = ap.parse_args()

    cfg = json.load(open(os.path.join(a.build, "fcpe.json")))
    cents = np.array(cfg["cent_table"], np.float32)
    S = cfg["samples"]
    mel_s = ort.InferenceSession(os.path.join(a.build, a.mel), providers=["CPUExecutionProvider"])
    net_s = ort.InferenceSession(os.path.join(a.build, a.net), providers=["CPUExecutionProvider"])

    import torch
    from torchfcpe import spawn_bundled_infer_model
    ref_model = spawn_bundled_infer_model("cpu")

    worst_ref, worst_true = 0.0, 0.0
    for f0a, f0b in [(150, 300), (200, 200), (110, 180), (260, 520)]:
        x = glide(S + 1600, f0a, f0b)[800:800 + S]            # arbitrary slice of a glide
        mel = mel_s.run(None, {"audio": x[None]})[0]
        lat = net_s.run(None, {"mel": mel})[0]
        mine = local_argmax_decode(lat, cents, cfg["threshold"])[0]
        ref = ref_model.infer(torch.from_numpy(x)[None], sr=16000, decoder_mode="local_argmax",
                              threshold=cfg["threshold"]).squeeze().numpy()
        both = (mine > 0) & (ref > 0)
        rel = np.abs(mine[both] - ref[both]) / ref[both]
        # truth: slice start 800 samples into the glide
        t = np.linspace(f0a, f0b, S + 1600)[800:800 + S]
        truth = t[np.minimum(np.arange(mine.size) * 160, S - 1)]
        cents_err = 1200 * np.abs(np.log2(mine[both] / truth[both]))
        worst_ref = max(worst_ref, rel.max() if rel.size else 0)
        worst_true = max(worst_true, np.median(cents_err))
        print(f"  glide {f0a:>3}->{f0b:<3} Hz  voiced {both.sum():2d}/{mine.size}  "
              f"vs torchfcpe max rel err {rel.max() if rel.size else 0:.2e}  "
              f"median err vs truth {np.median(cents_err):5.1f} cents")
    ok = worst_ref < 1e-3
    print("[fcpe parity]", "PASS" if ok else "FAIL", f"(worst rel err vs torchfcpe {worst_ref:.2e})")
    audit_ops(os.path.join(a.build, a.mel))
    audit_ops(os.path.join(a.build, a.net))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

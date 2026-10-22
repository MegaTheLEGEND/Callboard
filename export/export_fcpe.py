"""Export FCPE (the pitch tracker RVC uses) as two static ONNX graphs.

    fcpe_mel.onnx   audio16k [1, S]        -> mel    [1, F, 128]   (CPU EP, FP32)
    fcpe_net.onnx   mel      [1, F, 128]   -> latent [1, F, 360]   (HTP, FP16)
    fcpe.json       cent table + decode constants (decode runs FP32 on the CPU)

Why split?
* torch.stft has no NPU kernel.  We rebuild it as a windowed-DFT Conv1d (exact,
  ~17 M MAC per block) and keep it on the CPU in FP32 where it is cheap and
  numerically identical to torchfcpe.
* The network itself (6 conformer-conv layers, 10.8 M params, no attention) is
  ideal for the HTP.
* The decode works in cents (values up to ~9000); FP16 has ~8 cent resolution
  there, which is an audible pitch error.  So the tiny decode stays FP32 on CPU.

S = block*160 + 800 samples (the newest block + 50 ms lead-in), F = S//160 + 1,
exactly the window rtrvc.py gives the pitch extractor.
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
from torch.nn.utils import parametrize

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from geometry import Geometry  # noqa: E402

N_FFT = 1024
HOP = 160
PAD_L = (N_FFT - HOP) // 2        # 432
PAD_R = (N_FFT - HOP + 1) // 2    # 433
THRESHOLD = 0.006                 # rtrvc.py: threshold=0.006, local_argmax


def reflect_index(n: int, pad_l: int, pad_r: int) -> np.ndarray:
    """Gather indices equal to F.pad(mode='reflect'); Gather maps cleanly to the NPU/CPU."""
    idx = np.arange(-pad_l, n + pad_r)
    idx = np.where(idx < 0, -idx, idx)
    idx = np.where(idx > n - 1, 2 * (n - 1) - idx, idx)
    return idx.astype(np.int64)


class MelGraph(nn.Module):
    """torchfcpe Wav2MelModule (16 kHz in, no resample) as plain ONNX-able ops."""

    def __init__(self, wav2mel, samples: int, frames: int):
        super().__init__()
        mel_basis = wav2mel.mel_extractor.mel_basis.detach().float()  # [128, 513]
        n = torch.arange(N_FFT, dtype=torch.float64)
        k = torch.arange(N_FFT // 2 + 1, dtype=torch.float64)
        win = torch.hann_window(N_FFT, periodic=True, dtype=torch.float64)
        ang = 2.0 * np.pi * k[:, None] * n[None, :] / N_FFT
        w = torch.cat([torch.cos(ang) * win, -torch.sin(ang) * win], 0).float()  # [1026,1024]
        self.register_buffer("dft", w.unsqueeze(1))                               # [1026,1,1024]
        self.register_buffer("mel_basis", mel_basis)
        self.register_buffer("pad_idx", torch.from_numpy(reflect_index(samples, PAD_L, PAD_R)))
        self.samples, self.frames = samples, frames
        n_dft = (samples + PAD_L + PAD_R - N_FFT) // HOP + 1
        assert n_dft in (frames, frames - 1), (n_dft, frames)
        self.n_dft = n_dft

    def forward(self, audio):                                    # [1, S]
        x = audio[:, self.pad_idx].unsqueeze(1)                  # reflect pad -> [1,1,S+865]
        spec = F.conv1d(x, self.dft, stride=HOP)                 # [1,1026,n_dft]
        re, im = spec[:, :513], spec[:, 513:]
        mag = torch.sqrt(re * re + im * im + 1e-9)               # [1,513,n_dft]
        mel = torch.matmul(self.mel_basis, mag)                  # [1,128,n_dft]
        mel = torch.log(torch.clamp(mel, min=1e-5))
        mel = mel.transpose(1, 2)                                # [1,n_dft,128]
        if self.n_dft < self.frames:                             # torchfcpe repeats last frame
            mel = torch.cat([mel, mel[:, -1:, :]], 1)
        return mel


class NetGraph(nn.Module):
    def __init__(self, model):
        super().__init__()
        parametrize.remove_parametrizations(model.output_proj, "weight")
        self.m = model

    def forward(self, mel):                                      # [1,F,128]
        return self.m(mel)                                       # [1,F,360] latent


def local_argmax_decode(latent: np.ndarray, cent_table: np.ndarray,
                        threshold: float = THRESHOLD) -> np.ndarray:
    """FP32 reference of torchfcpe 'local_argmax' decode -> f0 Hz (0 = unvoiced).
    The Android engine implements exactly this."""
    out = np.zeros(latent.shape[:-1], np.float32)
    n = latent.shape[-1]
    for t in range(latent.shape[-2]):
        y = latent[..., t, :].reshape(-1).astype(np.float32)
        i = int(y.argmax())
        if y[i] <= threshold:
            continue
        idx = np.clip(np.arange(i - 4, i + 5), 0, n - 1)    # same clamp as torchfcpe
        cents = (cent_table[idx] * y[idx]).sum() / y[idx].sum()
        out[..., t] = 10.0 * 2.0 ** (cents / 1200.0)
    return out


def export(args) -> None:
    from torchfcpe import spawn_bundled_infer_model

    geo = Geometry(block=args.block, extra=args.extra, crossfade=args.crossfade)
    infer = spawn_bundled_infer_model("cpu")
    S, Fr = geo.fcpe_samples, geo.fcpe_frames
    print(f"[fcpe] samples={S} frames={Fr}")

    mel_g = MelGraph(infer.wav2mel, S, Fr).eval()
    net_g = NetGraph(infer.model).eval()
    os.makedirs(args.out, exist_ok=True)

    audio = (torch.randn(1, S) * 0.1).clamp(-1, 1)
    with torch.no_grad():
        ref_mel = infer.wav2mel(audio, 16000)
        my_mel = mel_g(audio)
        err = (ref_mel - my_mel).abs().max().item()
        print(f"[fcpe] mel parity vs torchfcpe: max|err|={err:.2e}  shape={tuple(my_mel.shape)}")
        assert err < 1e-3, "mel front end deviates from torchfcpe"
        lat = net_g(my_mel)

    p_mel = os.path.join(args.out, "fcpe_mel.onnx")
    p_net = os.path.join(args.out, "fcpe_net.onnx")
    torch.onnx.export(mel_g, (audio,), p_mel, input_names=["audio"], output_names=["mel"],
                      opset_version=17, dynamo=False)
    torch.onnx.export(net_g, (my_mel,), p_net, input_names=["mel"], output_names=["latent"],
                      opset_version=17, dynamo=False)

    cents = infer.model.cent_table.detach().float().numpy()
    with open(os.path.join(args.out, "fcpe.json"), "w") as f:
        json.dump({
            "samples": S, "frames": Fr, "bins": int(lat.shape[-1]),
            "threshold": THRESHOLD, "decoder": "local_argmax", "half_window": 4,
            "cent_table": [float(c) for c in cents],
            "f0_min": 32.7, "f0_max": 1975.5,
        }, f)
    print("[fcpe] wrote", p_mel, p_net, "fcpe.json")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--block", type=int, default=10)
    ap.add_argument("--extra", type=int, default=50)
    ap.add_argument("--crossfade", type=int, default=5)
    export(ap.parse_args())

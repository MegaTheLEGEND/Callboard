"""Export RMVPE (RVC's rmvpe.pt) with the exact interface the engine already uses for pitch:

    fcpe_mel.onnx   audio16k [1, S]       -> mel      [1, F, 128]   (CPU EP, FP32)
    fcpe_net.onnx   mel      [1, F, 128]  -> salience [1, F, 360]   (HTP; the BiGRU falls back to CPU)
    fcpe.json       cent table + decode constants (threshold 0.03, zero-padded local average = RVC's decode)

File names stay "fcpe_*" so Voices.kt / CI / the engine graph wiring need no change.
Mel = RVC MelSpectrogram (n_fft 1024, hop 160, HTK mel 30-8000 Hz, center=True, log(clamp 1e-5)).
The network pads the time axis to a multiple of 32 with zeros (as RVC does) and crops back.
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

N_FFT, HOP, N_MELS = 1024, 160, 128
THRESHOLD = 0.03  # RVC: rmvpe thred=0.03


# ------------------------------------------------------------------ network (RVC infer/lib/rmvpe.py)
class BiGRU(nn.Module):
    def __init__(self, i, h, n):
        super().__init__()
        self.gru = nn.GRU(i, h, num_layers=n, batch_first=True, bidirectional=True)

    def forward(self, x):
        return self.gru(x)[0]


class ConvBlockRes(nn.Module):
    def __init__(self, cin, cout, momentum=0.01):
        super().__init__()
        self.conv = nn.Sequential(
            nn.Conv2d(cin, cout, (3, 3), (1, 1), (1, 1), bias=False), nn.BatchNorm2d(cout, momentum=momentum), nn.ReLU(),
            nn.Conv2d(cout, cout, (3, 3), (1, 1), (1, 1), bias=False), nn.BatchNorm2d(cout, momentum=momentum), nn.ReLU())
        self.is_shortcut = cin != cout
        if self.is_shortcut:
            self.shortcut = nn.Conv2d(cin, cout, (1, 1))

    def forward(self, x):
        return self.conv(x) + (self.shortcut(x) if self.is_shortcut else x)


class ResEncoderBlock(nn.Module):
    def __init__(self, cin, cout, kernel_size, n_blocks=1, momentum=0.01):
        super().__init__()
        self.n_blocks = n_blocks
        self.conv = nn.ModuleList([ConvBlockRes(cin, cout, momentum)] +
                                  [ConvBlockRes(cout, cout, momentum) for _ in range(n_blocks - 1)])
        self.kernel_size = kernel_size
        if kernel_size is not None:
            self.pool = nn.AvgPool2d(kernel_size=kernel_size)

    def forward(self, x):
        for c in self.conv:
            x = c(x)
        return (x, self.pool(x)) if self.kernel_size is not None else x


class Encoder(nn.Module):
    def __init__(self, cin, in_size, n_enc, kernel_size, n_blocks, cout=16, momentum=0.01):
        super().__init__()
        self.n_encoders = n_enc
        self.bn = nn.BatchNorm2d(cin, momentum=momentum)
        self.layers = nn.ModuleList()
        for _ in range(n_enc):
            self.layers.append(ResEncoderBlock(cin, cout, kernel_size, n_blocks, momentum))
            cin, cout = cout, cout * 2
        self.out_channel = cout

    def forward(self, x):
        skips = []
        x = self.bn(x)
        for l in self.layers:
            t, x = l(x)
            skips.append(t)
        return x, skips


class Intermediate(nn.Module):
    def __init__(self, cin, cout, n_inters, n_blocks, momentum=0.01):
        super().__init__()
        self.layers = nn.ModuleList([ResEncoderBlock(cin, cout, None, n_blocks, momentum)] +
                                    [ResEncoderBlock(cout, cout, None, n_blocks, momentum) for _ in range(n_inters - 1)])

    def forward(self, x):
        for l in self.layers:
            x = l(x)
        return x


class ResDecoderBlock(nn.Module):
    def __init__(self, cin, cout, stride, n_blocks=1, momentum=0.01):
        super().__init__()
        out_padding = (0, 1) if stride == (1, 2) else (1, 1)
        self.conv1 = nn.Sequential(nn.ConvTranspose2d(cin, cout, (3, 3), stride, (1, 1), out_padding, bias=False),
                                   nn.BatchNorm2d(cout, momentum=momentum), nn.ReLU())
        self.conv2 = nn.ModuleList([ConvBlockRes(cout * 2, cout, momentum)] +
                                   [ConvBlockRes(cout, cout, momentum) for _ in range(n_blocks - 1)])

    def forward(self, x, skip):
        x = torch.cat((self.conv1(x), skip), dim=1)
        for c in self.conv2:
            x = c(x)
        return x


class Decoder(nn.Module):
    def __init__(self, cin, n_dec, stride, n_blocks, momentum=0.01):
        super().__init__()
        self.n_decoders = n_dec
        self.layers = nn.ModuleList()
        for _ in range(n_dec):
            self.layers.append(ResDecoderBlock(cin, cin // 2, stride, n_blocks, momentum))
            cin //= 2

    def forward(self, x, skips):
        for i, l in enumerate(self.layers):
            x = l(x, skips[-1 - i])
        return x


class DeepUnet(nn.Module):
    def __init__(self, kernel_size, n_blocks, en_de_layers=5, inter_layers=4, cin=1, en_out=16):
        super().__init__()
        self.encoder = Encoder(cin, 128, en_de_layers, kernel_size, n_blocks, en_out)
        self.intermediate = Intermediate(self.encoder.out_channel // 2, self.encoder.out_channel, inter_layers, n_blocks)
        self.decoder = Decoder(self.encoder.out_channel, en_de_layers, kernel_size, n_blocks)

    def forward(self, x):
        x, skips = self.encoder(x)
        return self.decoder(self.intermediate(x), skips)


class E2E(nn.Module):
    def __init__(self, n_blocks=4, n_gru=1, kernel_size=(2, 2)):
        super().__init__()
        self.unet = DeepUnet(kernel_size, n_blocks)
        self.cnn = nn.Conv2d(16, 3, (3, 3), padding=(1, 1))
        self.fc = nn.Sequential(BiGRU(3 * N_MELS, 256, n_gru), nn.Linear(512, 360), nn.Dropout(0.25), nn.Sigmoid())

    def forward(self, mel):  # mel [B, T, 128] (already log-mel, T % 32 == 0)
        x = self.cnn(self.unet(mel.unsqueeze(1))).transpose(1, 2).flatten(-2)
        return self.fc(x)


class NetGraph(nn.Module):
    """[1,F,128] -> [1,F,360]; zero-pads T to a multiple of 32 like RVC's mel2hidden, then crops."""

    def __init__(self, m: E2E, frames: int):
        super().__init__()
        self.m, self.frames = m, frames
        self.pad = (-frames) % 32

    def forward(self, mel):
        if self.pad:
            mel = torch.cat([mel, mel.new_zeros(1, self.pad, N_MELS)], 1)
        return self.m(mel)[:, :self.frames]


# ------------------------------------------------------------------ mel front end
def htk_mel_basis(sr=16000, n_fft=N_FFT, n_mels=N_MELS, fmin=30.0, fmax=8000.0) -> np.ndarray:
    """librosa.filters.mel(htk=True, norm='slaney') without needing librosa."""
    hz2mel = lambda f: 2595.0 * np.log10(1.0 + f / 700.0)
    mel2hz = lambda m: 700.0 * (10.0 ** (m / 2595.0) - 1.0)
    mel_f = mel2hz(np.linspace(hz2mel(fmin), hz2mel(fmax), n_mels + 2))
    fft_f = np.linspace(0, sr / 2, n_fft // 2 + 1)
    fdiff = np.diff(mel_f)
    ramps = mel_f[:, None] - fft_f[None, :]
    w = np.maximum(0, np.minimum(-ramps[:-2] / fdiff[:-1, None], ramps[2:] / fdiff[1:, None]))
    return (w * (2.0 / (mel_f[2:n_mels + 2] - mel_f[:n_mels]))[:, None]).astype(np.float32)


def reflect_index(n: int, pad: int) -> np.ndarray:
    idx = np.arange(-pad, n + pad)
    idx = np.where(idx < 0, -idx, idx)
    return np.where(idx > n - 1, 2 * (n - 1) - idx, idx).astype(np.int64)


class MelGraph(nn.Module):
    def __init__(self, samples: int, frames: int):
        super().__init__()
        n = torch.arange(N_FFT, dtype=torch.float64)
        k = torch.arange(N_FFT // 2 + 1, dtype=torch.float64)
        win = torch.hann_window(N_FFT, periodic=True, dtype=torch.float64)
        ang = 2.0 * np.pi * k[:, None] * n[None, :] / N_FFT
        self.register_buffer("dft", torch.cat([torch.cos(ang) * win, -torch.sin(ang) * win], 0).float().unsqueeze(1))
        self.register_buffer("mel_basis", torch.from_numpy(htk_mel_basis()))
        self.register_buffer("pad_idx", torch.from_numpy(reflect_index(samples, N_FFT // 2)))
        assert (samples // HOP + 1) == frames, (samples, frames)

    def forward(self, audio):  # [1,S]
        spec = F.conv1d(audio[:, self.pad_idx].unsqueeze(1), self.dft, stride=HOP)  # [1,1026,F]
        mag = torch.sqrt(spec[:, :513] ** 2 + spec[:, 513:] ** 2 + 1e-9)
        mel = torch.matmul(self.mel_basis, mag)
        return torch.log(torch.clamp(mel, min=1e-5)).transpose(1, 2)  # [1,F,128]


def decode(sal: np.ndarray, cents: np.ndarray, thr=THRESHOLD) -> np.ndarray:
    """RVC to_local_average_cents (zero padded +-4 window) -> Hz. Same as the C++ engine."""
    out = np.zeros(sal.shape[0], np.float32)
    p = np.pad(sal, ((0, 0), (4, 4))); c = np.pad(cents, (4, 4))
    for t in range(sal.shape[0]):
        if sal[t].max() <= thr:
            continue
        i = int(sal[t].argmax()) + 4
        w = p[t, i - 4:i + 5]
        out[t] = 10.0 * 2.0 ** (((w * c[i - 4:i + 5]).sum() / w.sum()) / 1200.0)
    return out


def export(a) -> None:
    geo = Geometry(block=a.block, extra=a.extra, crossfade=a.crossfade)
    S, Fr = geo.fcpe_samples, geo.fcpe_frames
    m = E2E().eval()
    sd = torch.load(a.rmvpe, map_location="cpu")
    m.load_state_dict({k: v.float() for k, v in sd.items()})
    os.makedirs(a.out, exist_ok=True)
    mel_g, net_g = MelGraph(S, Fr).eval(), NetGraph(m, Fr).eval()

    audio = (torch.randn(1, S) * 0.1).clamp(-1, 1)
    with torch.no_grad():
        ref = torch.stft(audio, N_FFT, HOP, N_FFT, torch.hann_window(N_FFT), center=True, return_complex=True).abs()
        ref = torch.log(torch.clamp(torch.from_numpy(htk_mel_basis()) @ ref, min=1e-5)).transpose(1, 2)
        mel = mel_g(audio)
        err = (ref - mel).abs().max().item()
        print(f"[rmvpe] mel parity vs torch.stft: max|err|={err:.2e} shape={tuple(mel.shape)}")
        assert err < 1e-3
        sal = net_g(mel)
    p_mel, p_net = os.path.join(a.out, "fcpe_mel.onnx"), os.path.join(a.out, "fcpe_net.onnx")
    torch.onnx.export(mel_g, (audio,), p_mel, input_names=["audio"], output_names=["mel"], opset_version=17, dynamo=False)
    torch.onnx.export(net_g, (mel,), p_net, input_names=["mel"], output_names=["latent"], opset_version=17, dynamo=False)

    import onnxruntime as ort
    o = ort.InferenceSession(p_net, providers=["CPUExecutionProvider"]).run(None, {"mel": mel.numpy()})[0]
    print(f"[rmvpe] net onnx vs torch: max|err|={np.abs(o - sal.numpy()).max():.2e}")
    assert np.abs(o - sal.numpy()).max() < 1e-3

    cents = (20.0 * np.arange(360) + 1997.3794084376191).astype(np.float32)
    json.dump({"samples": S, "frames": Fr, "bins": 360, "threshold": THRESHOLD, "decoder": "rvc_local_average",
               "zero_pad": 1, "half_window": 4, "cent_table": [float(c) for c in cents]},
              open(os.path.join(a.out, "fcpe.json"), "w"))
    print("[rmvpe] wrote", p_mel, p_net, "fcpe.json")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--rmvpe", default="assets/rmvpe.pt")
    ap.add_argument("--block", type=int, default=10)
    ap.add_argument("--extra", type=int, default=50)
    ap.add_argument("--crossfade", type=int, default=5)
    export(ap.parse_args())

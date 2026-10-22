"""Independent Python reference of the whole realtime pipeline.

Runs a WAV through the exported pack exactly like the C++ engine does (same
graphs via ONNX Runtime CPU, but all glue re-implemented in NumPy from the
ORIGINAL rtrvc.py / realtime_gui.py semantics).  Used to cross-check the C++
engine bit-for-bit-ish:

    python reference_stream.py --pack pack/ --in in.wav --out ref.wav --blocks 30 \
        --no-noise --index-rate 0.5
    python compare_wavs.py ref.wav engine_out.wav
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import numpy as np
import onnxruntime as ort
from scipy.io import wavfile
from scipy.signal import upfirdn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from export_fcpe import local_argmax_decode  # noqa: E402
from gen_test_vectors import SolaRef, coarse_ref, interp_ref  # noqa: E402
from sinegen_ref import sine_excitation  # noqa: E402

DEV_SR = 48000


def kaiser_kernel(L: int, M: int, half_in: int = 16):
    """Same design as engine/src/dsp.cpp ResamplerKernel::design (float64 -> float32)."""
    mx = max(L, M)
    c = half_in * mx
    n = np.arange(2 * c + 1) - c
    fc = 0.46 / mx
    x = 2 * fc * n
    w = np.i0(8.0 * np.sqrt(np.maximum(0, 1 - (n / c) ** 2))) / np.i0(8.0)
    return (L * 2 * fc * np.sinc(x) * w).astype(np.float32), c


def read_blocks(path: str):
    sr, x = wavfile.read(path)
    assert sr == DEV_SR and x.dtype == np.int16
    return x.astype(np.float32) / 32768.0


def load_index(pack: str):
    raw = open(os.path.join(pack, "index.bin"), "rb").read()
    n, d, _ = np.frombuffer(raw[4:16], dtype="<u4")
    off = 16 + 4 * n
    return np.frombuffer(raw, dtype="<f2", count=n * d, offset=off).reshape(n, d).astype(np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pack", required=True)
    ap.add_argument("--in", dest="inp", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--blocks", type=int, default=0)
    ap.add_argument("--pitch", type=float, default=0.0)
    ap.add_argument("--index-rate", type=float, default=0.5)
    ap.add_argument("--no-noise", action="store_true", help="(required) zero noise inputs")
    a = ap.parse_args()
    assert a.no_noise, "the reference is deterministic: pass --no-noise"

    cfg = {l.split()[0]: float(l.split()[1]) for l in open(os.path.join(a.pack, "engine.cfg")) if l.strip()}
    I = lambda k: int(cfg[k])
    cents = np.fromfile(os.path.join(a.pack, "fcpe_cents.f32"), np.float32)
    sess = {n: ort.InferenceSession(os.path.join(a.pack, f"{n}.onnx"), providers=["CPUExecutionProvider"])
            for n in ("fcpe_mel", "fcpe_net", "hubert", "synth")}
    index = load_index(a.pack) if I("has_index") else None

    zc = DEV_SR // 100
    block48 = I("block") * zc
    H, D = I("hubert_samples"), I("feat_dim")
    h_in, _ = kaiser_kernel(1, 3)
    g = np.gcd(I("tgt_sr"), DEV_SR)
    h_out, c_out = kaiser_kernel(DEV_SR // g, I("tgt_sr") // g)
    L_o, M_o = DEV_SR // g, I("tgt_sr") // g

    x = read_blocks(a.inp)
    nb = len(x) // block48
    if a.blocks:
        nb = min(nb, a.blocks)

    in16 = np.zeros(H, np.float32)
    pc = np.zeros(1024, np.int64)
    pf = np.zeros(1024, np.float32)
    sola = SolaRef(block48, I("sola_buffer") * zc, I("sola_search") * zc)
    hist48 = np.zeros(0, np.float32)           # for the causal streaming filter
    outs = []
    key = 2.0 ** (a.pitch / 12)

    for b in range(nb):
        blk = x[b * block48:(b + 1) * block48]
        # 48k -> 16k causal streaming filter (zero initial state, continues across blocks)
        hist48 = np.concatenate([hist48, blk])
        full = upfirdn(h_in, hist48, up=1, down=3)
        n16_total = (b + 1) * block48 // 3
        y16 = full[:n16_total].astype(np.float32)
        new16 = y16[b * block48 // 3:]
        in16 = np.concatenate([in16[len(new16):], new16])

        # pitch
        mel = sess["fcpe_mel"].run(None, {"audio": in16[None, -I("fcpe_samples"):]})[0]
        lat = sess["fcpe_net"].run(None, {"mel": mel})[0]
        f0 = local_argmax_decode(lat, cents, cfg["fcpe_threshold"])[0]
        f0 = interp_ref(f0) * key
        coarse = coarse_ref(f0)
        n = I("fcpe_frames")
        shift = len(new16) // 160
        pc[:-shift] = pc[shift:].copy(); pf[:-shift] = pf[shift:].copy()
        pc[4 - n:] = coarse[3:-1]; pf[4 - n:] = f0[3:-1]
        W = I("window")
        pitch, pitchf = pc[-W:], pf[-W:]

        # features (+ appended last frame), optional retrieval
        feats = sess["hubert"].run(None, {"audio": in16[None]})[0][0]
        feats = np.concatenate([feats, feats[-1:]], 0)
        first = I("skip_head") // 2
        if index is not None and a.index_rate > 0:
            from build_index import blend_ref
            feats[first:] = blend_ref(feats[first:], index, a.index_rate)

        # excitation + synth
        sk, rl = I("skip_head"), I("return_length")
        har = sine_excitation(pitchf[sk:sk + rl], I("tgt_sr"), I("upp"), cfg["source_lin_w"], cfg["source_lin_b"])
        noise = np.zeros((1, I("inter_channels"), W - I("flow_head")), np.float32)
        audio = sess["synth"].run(None, {"feats": feats[None].astype(np.float32), "pitch": pitch[None],
                                         "har": har[None, None], "noise": noise})[0].reshape(-1)

        # 40k -> 48k, zero-phase block resample
        up = upfirdn(h_out, audio, up=L_o, down=1)
        wav48 = up[c_out + M_o * np.arange(rl * zc)].astype(np.float32)
        o, off = sola.process(wav48)
        outs.append(o)
        if b < 3 or b % 10 == 0:
            print(f"block {b:3d}  sola offset {off:3d}  f0[{n // 2}]={f0[n // 2]:.1f} Hz", flush=True)

    y = np.concatenate(outs)
    wavfile.write(a.out, DEV_SR, (np.clip(y, -1, 1) * 32767).astype(np.int16))
    print("wrote", a.out, y.shape)


if __name__ == "__main__":
    main()

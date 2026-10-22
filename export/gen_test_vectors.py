"""Generate reference vectors for engine/tests/test_dsp.cpp.

Each expected output is produced by NumPy code that mirrors the ORIGINAL RVC
implementation (rtrvc.py / realtime_gui.py / faiss path), so the C++ port is
checked against the upstream behaviour, not against itself.

    python gen_test_vectors.py /tmp/rvc_vectors
"""
from __future__ import annotations

import json
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build_index import blend_ref, write_bin  # noqa: E402
from export_fcpe import local_argmax_decode  # noqa: E402
from sinegen_ref import sine_excitation  # noqa: E402

rng = np.random.default_rng(1234)


def save(d, name, arr):
    arr = np.ascontiguousarray(arr)
    arr.tofile(os.path.join(d, name))


# ---- rtrvc.py get_f0_post / interpolation, in NumPy ------------------------
F0_MEL_MIN = 1127 * np.log(1 + 50 / 700)
F0_MEL_MAX = 1127 * np.log(1 + 1100 / 700)


def interp_ref(f0):
    f0 = f0.copy()
    uv = f0 == 0
    if np.any(~uv):
        f0[uv] = np.interp(np.where(uv)[0], np.where(~uv)[0], f0[~uv])
    return f0


def coarse_ref(f0):
    f0 = f0.astype(np.float32)
    m = (1127 * np.log(1 + f0 / 700)).astype(np.float32)
    pos = m > 0
    m[pos] = (m[pos] - np.float32(F0_MEL_MIN)) * 254 / np.float32(F0_MEL_MAX - F0_MEL_MIN) + 1
    m[m <= 1] = 1
    m[m > 255] = 255
    return np.round(m).astype(np.int64)


# ---- realtime_gui.py SOLA, in NumPy ---------------------------------------
class SolaRef:
    def __init__(self, block, buf, search):
        self.block, self.buf, self.search = block, buf, search
        self.sola_buffer = np.zeros(buf, np.float32)
        x = np.linspace(0.0, 1.0, buf, dtype=np.float32)
        self.fin = (np.sin(0.5 * np.pi * x) ** 2).astype(np.float32)
        self.fout = 1 - self.fin

    def process(self, wav):
        wav = wav.copy()
        conv = wav[: self.buf + self.search]
        nom = np.array([np.dot(conv[k:k + self.buf], self.sola_buffer) for k in range(self.search + 1)])
        den = np.sqrt(np.array([np.dot(conv[k:k + self.buf], conv[k:k + self.buf])
                                for k in range(self.search + 1)]) + 1e-8)
        off = int(np.argmax(nom / den))
        w = wav[off:].copy()
        w[: self.buf] = w[: self.buf] * self.fin + self.sola_buffer * self.fout
        self.sola_buffer = w[self.block: self.block + self.buf].copy()
        return w[: self.block], off


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    meta = {}

    # 1. sine excitation (voiced + unvoiced stretches, with and without noise)
    L, sr, upp = 15, 40000, 400
    f0 = rng.uniform(90, 400, L).astype(np.float32)
    f0[3:6] = 0
    f0[11] = 0
    noise = rng.standard_normal(L * upp).astype(np.float32)
    w, b = 0.8337, -0.0123
    save(out, "sine_f0.f32", f0)
    save(out, "sine_noise.f32", noise)
    save(out, "sine_expect.f32", sine_excitation(f0, sr, upp, w, b))
    save(out, "sine_expect_noise.f32", sine_excitation(f0, sr, upp, w, b, noise))
    meta["sine"] = dict(L=L, sr=sr, upp=upp, w=w, b=b)

    # 2. fcpe decode on peaky synthetic latents (incl. edge bins + sub-threshold frames)
    frames, bins = 16, 360
    cents = (np.arange(bins) * 20.0 + 1000 * 0 + 0).astype(np.float32)
    lat = rng.uniform(0, 0.004, (frames, bins)).astype(np.float32)
    for t in range(frames):
        c = int(rng.integers(0, bins))
        if t == 2: c = 1
        if t == 5: c = bins - 2
        g = np.exp(-0.5 * ((np.arange(bins) - c) / 1.6) ** 2)
        lat[t] += (rng.uniform(0.3, 0.95) * g).astype(np.float32)
    lat[7] *= 0.002          # below threshold -> unvoiced
    lat[9] *= 0.004
    save(out, "fcpe_latent.f32", lat)
    save(out, "fcpe_cents.f32", cents)
    save(out, "fcpe_expect.f32", local_argmax_decode(lat, cents, 0.006))

    # 3. interp + coarse
    f = rng.uniform(70, 900, 16).astype(np.float32)
    f[[0, 1, 5, 6, 7, 15]] = 0
    save(out, "interp_in.f32", f)
    save(out, "interp_expect.f32", interp_ref(f))
    allz = np.zeros(16, np.float32)
    save(out, "interp_allzero_expect.f32", interp_ref(allz))
    f2 = interp_ref(f) * 2 ** (5 / 12)
    f2[2] = 3000.0          # saturates at 255
    f2[3] = 10.0            # below f0_min -> 1
    f2[4] = 0.0
    save(out, "coarse_in.f32", f2.astype(np.float32))
    save(out, "coarse_expect.i64", coarse_ref(f2))

    # 4. pitch cache (torch slicing semantics reproduced in NumPy)
    cap, shift, n = 1024, 10, 16
    cc = np.zeros(cap, np.int64); cf = np.zeros(cap, np.float32)
    steps = []
    for s in range(4):
        pc = rng.integers(1, 256, n).astype(np.int64)
        pf = rng.uniform(80, 400, n).astype(np.float32)
        cc[:-shift] = cc[shift:].copy(); cf[:-shift] = cf[shift:].copy()
        cc[4 - n:] = pc[3:-1]; cf[4 - n:] = pf[3:-1]
        save(out, f"pcache_c{s}.i64", pc); save(out, f"pcache_f{s}.f32", pf)
    save(out, "pcache_tail_c.i64", cc[-66:]); save(out, "pcache_tail_f.f32", cf[-66:])

    # 5. SOLA over 6 consecutive blocks (48 kHz geometry: zc=480)
    zc = 480
    block, buf, search = 10 * zc, 4 * zc, zc
    ref = SolaRef(block, buf, search)
    n_in = block + buf + search
    t = np.arange(8 * n_in + 4000)
    base = (0.4 * np.sin(2 * np.pi * 180 * t / 48000) + 0.2 * np.sin(2 * np.pi * 540 * t / 48000)
            + 0.02 * rng.standard_normal(t.size)).astype(np.float32)
    outs, offs, ins = [], [], []
    for s in range(6):
        seg = base[s * block + 37 * s: s * block + 37 * s + n_in]      # slightly drifting alignment
        o, off = ref.process(seg)
        ins.append(seg); outs.append(o); offs.append(off)
    save(out, "sola_in.f32", np.stack(ins)); save(out, "sola_out.f32", np.stack(outs))
    meta["sola"] = dict(block=block, buf=buf, search=search, n_in=n_in, steps=6, offsets=offs)

    # 6. retrieval: clustered index so neighbours are meaningful
    n_idx, d = 3000, 768
    centers = rng.standard_normal((40, d)).astype(np.float32)
    idx = (centers[rng.integers(0, 40, n_idx)] + 0.35 * rng.standard_normal((n_idx, d))).astype(np.float32)
    write_bin(os.path.join(out, "idx_small.bin"), idx)
    stored = idx.astype(np.float16).astype(np.float32)
    q = (centers[rng.integers(0, 40, 8)] + 0.4 * rng.standard_normal((8, d))).astype(np.float32)
    rate = 0.75
    save(out, "retr_in.f32", q)
    save(out, "retr_expect.f32", blend_ref(q, stored, rate))
    meta["retr"] = dict(T=8, d=d, rate=rate)

    json.dump(meta, open(os.path.join(out, "meta.json"), "w"), indent=1)
    print("vectors written to", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/rvc_vectors")

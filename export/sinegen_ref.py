"""NumPy reference of RVC's NSF source module (SineGen + SourceModuleHnNSF).

The sine excitation is the one part of the vocoder that the Hexagon NPU cannot
do well (cumulative phase + sin), and it is only ~6000 samples per block, so the
Android engine computes it on the CPU and feeds it to the NPU graph as the
`har` input.  This file is the bit-exact specification of that computation
(`engine/src/dsp/sinegen.cpp` implements the same maths and is tested against
vectors produced here).

Mirrors infer/module/models.py::SineGen._f02sine / forward with harmonic_num=0.
"""
from __future__ import annotations

import numpy as np

SINE_AMP = 0.1
NOISE_STD = 0.003
VOICED_THRESHOLD = 0.0


def sine_excitation(
    f0: np.ndarray,          # [L] Hz per 10 ms frame (0 == unvoiced)
    sr: int,
    upp: int,
    lin_w: float,            # SourceModuleHnNSF.l_linear.weight[0, 0]
    lin_b: float,            # SourceModuleHnNSF.l_linear.bias[0]
    noise: np.ndarray | None = None,   # [L*upp] ~ N(0,1); None -> zeros
) -> np.ndarray:
    """Returns har[L*upp] = tanh(lin_w * source + lin_b) (float32)."""
    f0 = np.asarray(f0, dtype=np.float32)
    L = f0.shape[0]

    # rad[t, k] = f0[t]/sr * (k+1), k = 0..upp-1
    k = np.arange(1, upp + 1, dtype=np.float32)
    rad = f0[:, None] / np.float32(sr) * k[None, :]

    # phase at the end of every frame, wrapped to [-0.5, 0.5)
    last = rad[:, -1].astype(np.float32)
    rad2 = np.fmod(last + np.float32(0.5), np.float32(1.0)) - np.float32(0.5)
    # accumulate across frames, wrap, shift by one frame (first frame starts at 0)
    rad_acc = np.fmod(np.cumsum(rad2, dtype=np.float32), np.float32(1.0))
    shifted = np.concatenate([[0.0], rad_acc[:-1]]).astype(np.float32)
    rad = rad + shifted[:, None]

    rad = rad.reshape(-1)                                  # [L*upp]
    sine = np.sin(np.float32(2.0 * np.pi) * rad) * np.float32(SINE_AMP)

    uv = (f0 > VOICED_THRESHOLD).astype(np.float32)
    uv_up = np.repeat(uv, upp)
    noise_amp = uv_up * np.float32(NOISE_STD) + (1 - uv_up) * np.float32(SINE_AMP / 3)
    if noise is None:
        noise = np.zeros(L * upp, dtype=np.float32)
    sine = sine * uv_up + noise_amp * noise.astype(np.float32)

    return np.tanh(np.float32(lin_w) * sine + np.float32(lin_b)).astype(np.float32)

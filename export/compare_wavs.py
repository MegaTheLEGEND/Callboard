"""Compare two 16-bit WAVs (reference vs engine): max error in LSBs and SNR."""
import sys
import numpy as np
from scipy.io import wavfile

sr1, a = wavfile.read(sys.argv[1]); sr2, b = wavfile.read(sys.argv[2])
n = min(len(a), len(b))
a = a[:n].astype(np.float64); b = b[:n].astype(np.float64)
d = a - b
snr = 10 * np.log10((a ** 2).sum() / max((d ** 2).sum(), 1e-12))
print(f"samples compared: {n}   max |diff|: {np.abs(d).max():.0f} LSB (of 32768)   "
      f"rms diff: {np.sqrt((d**2).mean()):.2f} LSB   SNR: {snr:.1f} dB")
ok = snr > 40
print("MATCH" if ok else "MISMATCH")
sys.exit(0 if ok else 1)

"""Pack a faiss `added_*.index` into `index.bin` for the Android engine.

RVC's retrieval step (rtrvc.py): for every HuBERT frame find the 8 nearest
training frames (squared L2), weight them by (1/score)^2, average, and mix the
result into the live features with `index_rate`.

The engine does the same search by brute force on the CPU (NEON, FP16 storage,
FP32 accumulate).  Only ~8 frames per block are searched, so even the full
index is cheap.  If you want it smaller/faster use --index-size N, which
k-means-reduces the index (same trick RVC training uses for huge indices).

index.bin layout (little endian):
    char  magic[4] = "RVCI"
    u32   n, d, version(=1)
    f32   sqnorm[n]            ||c||^2 of every stored vector
    f16   vecs[n*d]            row-major
"""
from __future__ import annotations

import argparse
import json
import os
import struct

import numpy as np

K = 8


def load_vectors(path: str) -> np.ndarray:
    import faiss
    idx = faiss.read_index(path)
    return idx.reconstruct_n(0, idx.ntotal).astype(np.float32)


def reduce_kmeans(x: np.ndarray, n: int, seed: int = 0) -> np.ndarray:
    from sklearn.cluster import MiniBatchKMeans
    km = MiniBatchKMeans(n_clusters=n, batch_size=max(256 * 8, n), n_init=1,
                         random_state=seed, max_iter=50, reassignment_ratio=0.0)
    km.fit(x)
    return km.cluster_centers_.astype(np.float32)


def write_bin(path: str, vecs: np.ndarray) -> None:
    n, d = vecs.shape
    v16 = vecs.astype(np.float16)
    # norms of the *stored* (fp16-rounded) vectors so distances stay self-consistent
    sq = (v16.astype(np.float32) ** 2).sum(1).astype(np.float32)
    with open(path, "wb") as f:
        f.write(b"RVCI" + struct.pack("<III", n, d, 1))
        f.write(sq.tobytes())
        f.write(v16.tobytes())


def blend_ref(feats: np.ndarray, vecs: np.ndarray, rate: float, k: int = K) -> np.ndarray:
    """FP32 brute-force reference. feats [T, D] -> blended [T, D]."""
    q2 = (feats ** 2).sum(1, keepdims=True)
    c2 = (vecs ** 2).sum(1)[None]
    d2 = np.maximum(q2 + c2 - 2.0 * feats @ vecs.T, 1e-9)
    ix = np.argpartition(d2, k - 1, axis=1)[:, :k]
    sc = np.take_along_axis(d2, ix, 1)
    w = np.square(1.0 / sc)
    w /= w.sum(1, keepdims=True)
    mixed = (vecs[ix] * w[..., None]).sum(1)
    return rate * mixed + (1.0 - rate) * feats


def selftest(src_index: str, vecs: np.ndarray) -> None:
    """Compare against the ORIGINAL faiss path (IVF, nprobe=1) as rtrvc.py runs it."""
    import faiss
    idx = faiss.read_index(src_index)
    rng = np.random.default_rng(0)
    big = idx.reconstruct_n(0, idx.ntotal)
    q = big[rng.integers(0, big.shape[0], 8)] + rng.normal(0, 0.15, (8, big.shape[1])).astype(np.float32)
    q = q.astype(np.float32)

    score, ix = idx.search(q, k=K)
    w = np.square(1.0 / np.maximum(score, 1e-9))
    w /= w.sum(1, keepdims=True)
    orig = (big[ix] * w[..., None]).sum(1)
    mine = blend_ref(q, vecs, 1.0)
    v16 = vecs.astype(np.float16).astype(np.float32)
    mine16 = blend_ref(q, v16, 1.0)
    rel = lambda a, b: float(np.linalg.norm(a - b) / np.linalg.norm(b))
    print(f"[index selftest] rel diff vs original faiss path: "
          f"exact fp32 {rel(mine, orig):.4f} | fp16 storage {rel(mine16, orig):.4f}  "
          f"(fp16 vs fp32 exact: {rel(mine16, mine):.5f})")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--index", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--index-size", type=int, default=0,
                    help="k-means reduce to N vectors (0 = keep all)")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    vecs = load_vectors(a.index)
    print(f"[index] {vecs.shape[0]} x {vecs.shape[1]}")
    if a.selftest and a.index_size == 0:
        selftest(a.index, vecs)
    if a.index_size and a.index_size < vecs.shape[0]:
        print(f"[index] k-means reducing to {a.index_size} ...")
        vecs = reduce_kmeans(vecs, a.index_size)
    os.makedirs(a.out, exist_ok=True)
    p = os.path.join(a.out, "index.bin")
    write_bin(p, vecs)
    print(f"[index] wrote {p}  {os.path.getsize(p)/1e6:.1f} MB  ({vecs.shape[0]} vectors)")
    with open(os.path.join(a.out, "index.json"), "w") as f:
        json.dump({"n": int(vecs.shape[0]), "d": int(vecs.shape[1]), "k": K}, f)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build the complete on-device model pack for ONE voice.

    python build_pack.py --pth model.pth --index model.index \
        --hubert-dir assets/hubert_base --out pack/

Outputs  pack/
    hubert.onnx  fcpe_mel.onnx  fcpe_net.onnx  synth.onnx   static-shape graphs
    index.bin                                                 FP16 retrieval index
    config.json                                               everything the engine needs
    manifest.json                                             sizes + sha256

Push the folder to the phone (see README) or bundle it as app assets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def run(*cmd: str) -> None:
    print("\n$", " ".join(os.path.basename(c) if os.sep in c else c for c in cmd), flush=True)
    r = subprocess.run([sys.executable, "-W", "ignore", *cmd], cwd=HERE)
    if r.returncode != 0:
        raise SystemExit(f"step failed: {cmd[0]}")


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def shared_dirs(a, work: str):
    """(hubert_cache_dir, fcpe_cache_dir) for this geometry/version, or (None, None)."""
    if not a.shared_cache:
        return None, None
    version = json.load(open(f"{work}/config.json"))["version"]
    key = f"b{a.block}_e{a.extra}_x{a.crossfade}"
    hub = f"hubert_{version}_{key}" + ("_rand" if a.random_init else "")
    return os.path.join(a.shared_cache, hub), os.path.join(a.shared_cache, f"fcpe_{key}")


def restore_shared(cache, work: str, names) -> bool:
    if not cache or not os.path.isfile(os.path.join(cache, ".done")):
        return False
    if not all(os.path.isfile(os.path.join(cache, n)) for n in names):
        return False
    for n in names:
        shutil.copy(os.path.join(cache, n), os.path.join(work, n))
    return True


def store_shared(cache, work: str, names) -> None:
    if not cache:
        return
    os.makedirs(cache, exist_ok=True)
    for n in names:
        shutil.copy(os.path.join(work, n), os.path.join(cache, n))
    open(os.path.join(cache, ".done"), "w").close()


def write_engine_cfg(cfg: dict, out: str) -> None:
    """Flat `key value` file + raw cent table, so the C++ engine needs no JSON parser."""
    import numpy as np
    f = cfg["fcpe"]
    keys = ["block", "extra", "crossfade", "sola_search", "tgt_sr", "upp", "feat_dim",
            "inter_channels", "window", "sola_buffer", "skip_head", "return_length",
            "flow_head", "dec_head", "hubert_samples", "hubert_frames", "fcpe_samples",
            "fcpe_frames", "out_samples", "source_lin_w", "source_lin_b", "z_noise_scale"]
    lines = [f"{k} {cfg[k]}" for k in keys]
    lines += [f"fcpe_bins {f['bins']}", f"fcpe_threshold {f['threshold']}",
              f"fcpe_half_window {f['half_window']}", f"has_index {1 if cfg['retrieval'] else 0}"]
    open(f"{out}/engine.cfg", "w").write("\n".join(lines) + "\n")
    np.asarray(f["cent_table"], dtype=np.float32).tofile(f"{out}/fcpe_cents.f32")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pth", required=True)
    ap.add_argument("--index", help="added_*.index (omit to disable retrieval)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--hubert-dir")
    g.add_argument("--random-init", action="store_true", help="TEST ONLY: random HuBERT weights")
    ap.add_argument("--out", required=True)
    ap.add_argument("--sid", type=int, default=0)
    ap.add_argument("--block", type=int, default=10, help="x10 ms of new audio per step")
    ap.add_argument("--extra", type=int, default=50, help="x10 ms of left context")
    ap.add_argument("--crossfade", type=int, default=5, help="x10 ms")
    ap.add_argument("--index-size", type=int, default=0, help="k-means reduce index (0 = keep all)")
    ap.add_argument("--skip-verify", action="store_true")
    ap.add_argument("--shared-cache", help="dir that keeps the voice-independent HuBERT/FCPE graphs between "
                    "builds, so a second voice of the same version only exports its synth")
    a = ap.parse_args()
    # sub-steps run with cwd=export/, so every path must be absolute
    for k in ("pth", "index", "hubert_dir", "out", "shared_cache"):
        v = getattr(a, k)
        if v:
            setattr(a, k, os.path.abspath(v))
    for k in ("pth", "index", "hubert_dir"):
        v = getattr(a, k)
        if v and not os.path.exists(v):
            raise SystemExit(f"not found: {v}")

    geo = ["--block", str(a.block), "--extra", str(a.extra), "--crossfade", str(a.crossfade)]
    os.makedirs(a.out, exist_ok=True)
    work = tempfile.mkdtemp(prefix="rvcpack_")
    try:
        run("export_synth.py", "--pth", a.pth, "--out", work, "--sid", str(a.sid), *geo)

        # HuBERT (per version) and FCPE do not depend on the voice, only on the geometry.
        hub_cache, fcpe_cache = shared_dirs(a, work)
        hub_hit = restore_shared(hub_cache, work, ("hubert.onnx",))
        fcpe_hit = restore_shared(fcpe_cache, work, ("fcpe_mel.onnx", "fcpe_net.onnx", "fcpe.json"))
        if fcpe_hit:
            print("[pack] reusing cached FCPE graphs")
        else:
            run("export_fcpe.py", "--out", work, *geo)
        if hub_hit:
            print("[pack] reusing cached HuBERT graph")
        elif a.random_init:
            run("export_hubert.py", "--out", work, "--random-init")
        else:
            run("export_hubert.py", "--out", work, "--hubert-dir", a.hubert_dir)
        if a.index:
            cmd = ["build_index.py", "--index", a.index, "--out", work, "--selftest"]
            if a.index_size:
                cmd += ["--index-size", str(a.index_size)]
            run(*cmd)

        # fold shape plumbing / pin static shapes
        from simplify import simplify_file
        todo = ["synth"] + ([] if hub_hit else ["hubert"]) + ([] if fcpe_hit else ["fcpe_net"])
        for name in todo:                      # cached graphs were already simplified
            simplify_file(f"{work}/{name}.onnx", f"{work}/{name}.simp.onnx")
            os.replace(f"{work}/{name}.simp.onnx", f"{work}/{name}.onnx")

        if not a.skip_verify:
            run("verify_synth.py", "--pth", a.pth, "--build", work)
            if not fcpe_hit:
                run("verify_fcpe.py", "--build", work)

        # keep the voice-independent graphs only once they have passed verification
        if not hub_hit:
            store_shared(hub_cache, work, ("hubert.onnx",))
        if not fcpe_hit:
            store_shared(fcpe_cache, work, ("fcpe_mel.onnx", "fcpe_net.onnx", "fcpe.json"))

        # ---- assemble pack ------------------------------------------------
        cfg = json.load(open(f"{work}/config.json"))
        cfg["fcpe"] = json.load(open(f"{work}/fcpe.json"))
        cfg["retrieval"] = (json.load(open(f"{work}/index.json")) if a.index else None)
        cfg["hubert_random_init"] = bool(a.random_init)
        files = ["hubert.onnx", "fcpe_mel.onnx", "fcpe_net.onnx", "synth.onnx"]
        if a.index:
            files.append("index.bin")
        manifest = {}
        for fn in files:
            shutil.copy(f"{work}/{fn}", f"{a.out}/{fn}")
            manifest[fn] = {"bytes": os.path.getsize(f"{a.out}/{fn}"), "sha256": sha256(f"{a.out}/{fn}")}
        json.dump(cfg, open(f"{a.out}/config.json", "w"), indent=2)
        write_engine_cfg(cfg, a.out)
        json.dump(manifest, open(f"{a.out}/manifest.json", "w"), indent=2)
    finally:
        shutil.rmtree(work, ignore_errors=True)

    total = sum(m["bytes"] for m in manifest.values()) / 1e6
    print(f"\nPack ready in {a.out}  ({total:.0f} MB)")
    if a.random_init:
        print("WARNING: built with random HuBERT weights - for pipeline testing only.")


if __name__ == "__main__":
    main()

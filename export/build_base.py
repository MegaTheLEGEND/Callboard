#!/usr/bin/env python3
"""ONE-TIME PC step.  Builds everything voice-independent so the phone can import any .pth by itself.

    python build_base.py --hubert-dir assets/hubert_base --out base/ [--variants v2_40000 v1_40000 ...] [--from-pth some.pth]
    adb push base /sdcard/Android/data/com.rvcs25.app/files/base

base/  hubert_v1.onnx hubert_v2.onnx fcpe_mel.onnx fcpe_net.onnx fcpe_cents.f32 manifest.json
       templates/<version>_<sr>/  synth.onnx (weights are placeholders)  template.params|config|cfg
The app overrides the synth's weights with the ones read from a downloaded .pth (engine/src/pth_import.cpp).
Variants: v1/v2 x 32000/40000/48000 (default: v2_40000 v1_40000). --from-pth takes the exact architecture of a real file.
Each template is ~100 MB (placeholder weights); install only the variants you need.
"""
from __future__ import annotations

import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile

import numpy as np
import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from export_synth import SynthCore, make_example_inputs  # noqa: E402
from geometry import Geometry  # noqa: E402
from rvc_core.models import SynthesizerTrnMs256NSFsid, SynthesizerTrnMs768NSFsid  # noqa: E402

RATES = {  # (version, sr) -> (upsample_rates, upsample_kernel_sizes)   (RVC's stock configs)
    ("v1", 32000): ([10, 4, 2, 2, 2], [16, 16, 4, 4, 4]), ("v1", 40000): ([10, 10, 2, 2], [16, 16, 4, 4]),
    ("v1", 48000): ([10, 6, 2, 2, 2], [16, 16, 4, 4, 4]), ("v2", 32000): ([10, 8, 2, 2], [20, 16, 4, 4]),
    ("v2", 40000): ([10, 10, 2, 2], [16, 16, 4, 4]), ("v2", 48000): ([12, 10, 2, 2], [24, 20, 4, 4]),
}
SR = {"32k": 32000, "40k": 40000, "48k": 48000}
CFG_KEYS = ["block", "extra", "crossfade", "sola_search", "tgt_sr", "upp", "feat_dim", "inter_channels", "window",
            "sola_buffer", "skip_head", "return_length", "flow_head", "dec_head", "hubert_samples", "hubert_frames",
            "fcpe_samples", "fcpe_frames", "out_samples"]


def default_config(version: str, sr: int) -> list:
    r, k = RATES[(version, sr)]
    return [1025, 17280 if sr == 48000 else 12800, 192, 192, 768, 2, 6, 3, 0, "1", [3, 7, 11],
            [[1, 3, 5]] * 3, r, 512, k, 109, 256, sr]


def canon(x) -> str:  # must match config_canon() in pth_import.cpp
    if isinstance(x, (list, tuple)):
        return "[" + ",".join(canon(i) for i in x) + "]"
    if isinstance(x, str):
        return x if x.isdigit() else f'"{x}"'
    return str(int(x)) if float(x) == int(x) else "%g" % x


def config_canon(cfg: list) -> str:
    sr = SR.get(cfg[17], cfg[17]) if isinstance(cfg[17], str) else int(cfg[17])
    return ";".join(canon(cfg[k]) for k in (2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 16)) + ";" + str(sr)


def sha256(p: str) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for c in iter(lambda: f.read(1 << 20), b""):
            h.update(c)
    return h.hexdigest()


def py(script: str, *a: str) -> None:
    r = subprocess.run([sys.executable, "-W", "ignore", os.path.join(HERE, script), *a], cwd=HERE)
    if r.returncode:
        raise SystemExit(f"{script} failed")


def build_template(version: str, cfg: list, args, fcpe: dict, out_dir: str) -> None:
    import onnx
    import onnxruntime as ort
    sr = SR.get(cfg[17], cfg[17]) if isinstance(cfg[17], str) else int(cfg[17])
    cls = SynthesizerTrnMs768NSFsid if version == "v2" else SynthesizerTrnMs256NSFsid
    c = list(cfg); c[17] = sr
    net = cls(*c, is_half=False)
    del net.enc_q
    net = net.float().eval(); net.remove_weight_norm()
    geo = Geometry(block=args.block, extra=args.extra, crossfade=args.crossfade, tgt_sr=sr, version=version,
                   feat_dim=768 if version == "v2" else 256, inter_channels=int(c[2]),
                   upp=int(np.prod(c[12])))
    core = SynthCore(net, geo, sid=0).eval()
    g = core.g.detach().clone(); del core.g
    core.g = nn.Parameter(g)                      # speaker vector becomes an overridable initializer "g"
    # Template weights are placeholders, but they must be pairwise DISTINCT: a freshly built net has many identical
    # tensors (norm gammas = 1, biases / zero-init layers = 0, ...) and torch.onnx.export merges identical
    # initializers into one, which silently drops those names from the overridable set.
    gen = torch.Generator().manual_seed(1234)
    with torch.no_grad():
        for p_ in core.parameters():
            p_.add_(1e-3 * torch.randn(p_.shape, generator=gen))
    inputs = make_example_inputs(geo)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, "synth.onnx")
    torch.onnx.export(core, inputs, path, input_names=["feats", "pitch", "har", "noise"], output_names=["audio"],
                      opset_version=17, do_constant_folding=False, dynamo=False)  # keep weight names intact
    m = onnx.load(path)
    over = [(i.name, list(i.dims)) for i in m.graph.initializer if i.name == "g" or i.name.startswith("net_g.")]
    named = {n for n, _ in over}
    unused_ok = ("net_g.dec.m_source.", "net_g.emb_g.")   # CPU-side source module / speaker table (replaced by "g")
    missing = [n for n, _ in core.named_parameters() if n not in named and not n.startswith(unused_ok)]
    if missing:
        raise SystemExit(f"[template {version}_{sr}] {len(missing)} weights are not overridable by name "
                         f"(merged/folded by the exporter): {missing[:15]}")
    with open(os.path.join(out_dir, "template.params"), "w") as f:
        for n, d in over:
            f.write(n + " " + " ".join(map(str, d)) + "\n")
    open(os.path.join(out_dir, "template.config"), "w").write(config_canon(c) + "\n")
    d = geo.to_dict(); d["z_noise_scale"] = 0.66666
    lines = [f"{k} {d[k]}" for k in CFG_KEYS] + [f"z_noise_scale {d['z_noise_scale']}"]
    lines += [f"fcpe_bins {fcpe['bins']}", f"fcpe_threshold {fcpe['threshold']}", f"fcpe_half_window {fcpe['half_window']}"]
    open(os.path.join(out_dir, "template.cfg"), "w").write("\n".join(lines) + "\n")

    # ---- self-test: weights injected into the template by name must reproduce PyTorch
    in_names = ["feats", "pitch", "har", "noise"]
    in_arrays = [t.numpy() for t in inputs]

    def run_ort(vals):
        so = ort.SessionOptions(); keep = []
        if vals is not None:
            for n, _ in over:
                keep.append(ort.OrtValue.ortvalue_from_numpy(vals[n].detach().numpy().astype(np.float32)))
                so.add_initializer(n, keep[-1])
        return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"]).run(None, dict(zip(in_names, in_arrays)))[0]

    with torch.no_grad():
        base = {n: p_.detach().clone() for n, p_ in core.named_parameters()}
        # (a) the exported file itself, no overrides: does the graph match PyTorch at all?
        err0 = float(np.abs(run_ort(None) - core(*inputs).numpy()).max())
        gen2 = torch.Generator().manual_seed(7)

        def perturb(only=None):
            for n, p_ in core.named_parameters():
                p_.copy_(base[n])
                if only is None or n.startswith(only):
                    p_.mul_(1 + 0.05 * torch.randn(p_.shape, generator=gen2)).add_(1e-3 * torch.randn(p_.shape, generator=gen2))

        def trial(only=None):
            perturb(only)
            ref = core(*inputs).numpy()
            return float(np.abs(run_ort(core.state_dict()) - ref).max()), float(np.abs(ref).max())

        err, refmax = trial()
        print(f"[template {version}_{sr}] {len(over)} overridable weights; baseline(no overrides) max|err|={err0:.2e}; "
              f"override self-test max|err|={err:.2e} (max|ref|={refmax:.2e})")
        if err > 2e-3 or err0 > 2e-3:
            if err0 > 2e-3:
                print("  -> the exported graph already differs from PyTorch WITHOUT any override (tracing/export issue, not naming)")
            groups = sorted({".".join(n.split(".")[:4]) for n, _ in over})
            bad = []
            for gname in groups:
                e, _ = trial(gname)
                if e > 2e-3:
                    bad.append((gname, e))
            print("  per-group max|err| (only that group perturbed):", ", ".join(f"{g}={e:.1e}" for g, e in bad) or "none above tol")
            raise SystemExit("self-test FAILED (see per-group report above)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hubert-dir", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--variants", nargs="*", default=["v2_40000", "v1_40000"])
    ap.add_argument("--from-pth", nargs="*", default=[])
    ap.add_argument("--block", type=int, default=10)
    ap.add_argument("--extra", type=int, default=50)
    ap.add_argument("--crossfade", type=int, default=5)
    args = ap.parse_args()
    out = os.path.abspath(args.out); os.makedirs(out, exist_ok=True)
    hub = os.path.abspath(args.hubert_dir)
    geo_args = ["--block", str(args.block), "--extra", str(args.extra), "--crossfade", str(args.crossfade)]

    variants = {}
    for v in args.variants:
        ver, sr = v.split("_"); variants[v] = (ver, default_config(ver, int(sr)))
    for p in args.from_pth:
        cpt = torch.load(p, map_location="cpu", weights_only=False)
        cfg = list(cpt["config"]); ver = cpt.get("version", "v1")
        sr = SR.get(cfg[17], cfg[17]) if isinstance(cfg[17], str) else int(cfg[17])
        variants[f"{ver}_{sr}"] = (ver, cfg)
    for name in variants:
        ver, sr = name.split("_")
        if (ver, int(sr)) not in RATES and not args.from_pth:
            raise SystemExit(f"unknown variant {name}")

    tmp = tempfile.mkdtemp(prefix="rvcbase_")
    try:
        # FCPE (voice independent)
        from simplify import simplify_file
        ft = os.path.join(tmp, "fcpe"); os.makedirs(ft)
        Geometry().dump(os.path.join(ft, "config.json"))
        py("export_fcpe.py", "--out", ft, *geo_args)
        simplify_file(f"{ft}/fcpe_net.onnx", f"{ft}/n.onnx"); os.replace(f"{ft}/n.onnx", f"{ft}/fcpe_net.onnx")
        fcpe = json.load(open(f"{ft}/fcpe.json"))
        for n in ("fcpe_mel.onnx", "fcpe_net.onnx"):
            shutil.copy(f"{ft}/{n}", f"{out}/{n}")
        np.asarray(fcpe["cent_table"], dtype=np.float32).tofile(f"{out}/fcpe_cents.f32")

        # HuBERT per feature version (v1 = layer 9 + projection, v2 = last layer)
        for ver in sorted({v for v, _ in variants.values()}):
            ht = os.path.join(tmp, "hub_" + ver); os.makedirs(ht)
            Geometry(block=args.block, extra=args.extra, crossfade=args.crossfade, version=ver,
                     feat_dim=768 if ver == "v2" else 256).dump(os.path.join(ht, "config.json"))
            py("export_hubert.py", "--out", ht, "--hubert-dir", hub)
            simplify_file(f"{ht}/hubert.onnx", f"{ht}/h.onnx")
            shutil.copy(f"{ht}/h.onnx", f"{out}/hubert_{ver}.onnx")

        for name, (ver, cfg) in variants.items():
            build_template(ver, cfg, args, fcpe, os.path.join(out, "templates", name))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    man = {}
    for root, _, files in os.walk(out):
        for f in files:
            if f == "manifest.json":
                continue
            p = os.path.join(root, f)
            rel = os.path.relpath(p, out).replace(os.sep, "/")
            man[rel] = {"bytes": os.path.getsize(p), "sha256": sha256(p), "asset": rel.replace("/", "__")}
    json.dump(man, open(os.path.join(out, "manifest.json"), "w"), indent=1)
    print(f"\nbase ready in {out}\nadb push {args.out} /sdcard/Android/data/com.rvcs25.app/files/base")


if __name__ == "__main__":
    main()

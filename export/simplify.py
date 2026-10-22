"""Fold shape arithmetic and pin every tensor shape so QNN-HTP can compile the graph.

torch.onnx.export leaves a lot of Shape / ConstantOfShape / Where plumbing from
the attention code.  With all input shapes fixed, onnxsim folds that away; we
then re-run shape inference so graph outputs carry concrete dims (QNN rejects
symbolic dims).
"""
from __future__ import annotations

import argparse
from collections import Counter

import onnx
from onnxsim import simplify


def _static_io(model: onnx.ModelProto) -> None:
    for t in list(model.graph.input) + list(model.graph.output):
        for d in t.type.tensor_type.shape.dim:
            if not d.dim_value:
                raise SystemExit(f"tensor {t.name} still has a symbolic dim ({d.dim_param})")


def simplify_file(src: str, dst: str, input_shapes: dict[str, list[int]] | None = None) -> None:
    model = onnx.load(src)
    before = Counter(n.op_type for n in model.graph.node)
    model, ok = simplify(model, overwrite_input_shapes=input_shapes)
    if not ok:
        raise SystemExit("onnxsim could not validate the simplified model")
    model = onnx.shape_inference.infer_shapes(model, strict_mode=True, data_prop=True)
    _static_io(model)
    onnx.save(model, dst)
    after = Counter(n.op_type for n in model.graph.node)
    print(f"[simplify] {src}: {sum(before.values())} -> {sum(after.values())} nodes")
    drop = {k: (before[k], after.get(k, 0)) for k in before if after.get(k, 0) != before[k]}
    for k, (b, a) in sorted(drop.items(), key=lambda kv: kv[1][0] - kv[1][1], reverse=True)[:8]:
        print(f"           {k:22s} {b:5d} -> {a}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    a = ap.parse_args()
    simplify_file(a.src, a.dst)

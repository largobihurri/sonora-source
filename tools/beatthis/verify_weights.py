#!/usr/bin/env python3
"""Check that build/small0.onnx carries the weights of the published upstream checkpoint build/small0.ckpt.

    python3 fetch_model.py --refs && python3 verify_weights.py

The checkpoint is a PyTorch zip archive. It is read WITHOUT PyTorch and without executing anything from it: a
restricted unpickler turns every tensor into a (storage key, offset, shape) record and every unknown class into an
inert stub, then the raw float32 storages are compared with the ONNX initialisers.

Expected outcome: every attention, feed-forward and linear tensor matches bit for bit (as stored or transposed);
what does not match is only what the exporter rewrites: the four conv kernels (batch norm is folded into them),
the batch-norm statistics and the rotary-embedding frequency constants.
"""
import collections
import pickle
import sys
import zipfile
from pathlib import Path

import numpy as np
import onnx
from onnx import numpy_helper

BUILD = Path(__file__).resolve().parent / "build"


class _Storage:
    def __init__(self, key):
        self.key = key


class _Stub:
    def __init__(self, *args, **kwargs):
        pass

    def __setstate__(self, state):
        pass


def _rebuild_tensor(storage, offset, size, stride, *rest):
    return (storage.key, offset, tuple(size))


class _SafeUnpickler(pickle.Unpickler):
    def find_class(self, module, name):
        if module == "torch._utils" and name == "_rebuild_tensor_v2":
            return _rebuild_tensor
        if module == "collections" and name == "OrderedDict":
            return collections.OrderedDict
        return type(name, (_Stub,), {})

    def persistent_load(self, pid):
        return _Storage(pid[2])


def main() -> None:
    with zipfile.ZipFile(BUILD / "small0.ckpt") as archive:
        root = archive.namelist()[0].split("/")[0]
        state = _SafeUnpickler(archive.open(f"{root}/data.pkl")).load()["state_dict"]
        tensors = {}
        for name, (key, offset, size) in state.items():
            if name.endswith("num_batches_tracked"):
                continue
            raw = np.frombuffer(archive.read(f"{root}/data/{key}"), dtype="<f4")
            count = int(np.prod(size)) if size else 1
            tensors[name.removeprefix("model.")] = raw[offset:offset + count].reshape(size)

    onnx_tensors = collections.defaultdict(list)
    for init in onnx.load(str(BUILD / "small0.onnx")).graph.initializer:
        array = numpy_helper.to_array(init)
        onnx_tensors[array.shape].append(array)

    matched = total = 0
    unmatched = []
    for name, tensor in tensors.items():
        total += tensor.size
        candidates = [(tensor.shape, tensor)] + ([(tensor.T.shape, tensor.T)] if tensor.ndim == 2 else [])
        if any(np.array_equal(a, t) for shape, t in candidates for a in onnx_tensors.get(shape, [])):
            matched += tensor.size
        else:
            unmatched.append(name)
    print(f"{matched} of {total} checkpoint elements ({matched / total:.1%}) are bit-identical in the ONNX file")
    rewritten = ("norm", "bn", "conv2d", "freqs")
    unexpected = [n for n in unmatched if not any(part in n for part in rewritten)]
    print("unmatched (batch norm / conv / rotary constants, rewritten by the exporter):", len(unmatched))
    if unexpected:
        sys.exit(f"FAILED: unexpected unmatched tensors: {unexpected}")
    print("ok")


if __name__ == "__main__":
    main()

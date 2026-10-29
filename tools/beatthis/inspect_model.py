#!/usr/bin/env python3
"""Print the opset, inputs, outputs, parameter count and operator histogram of build/small0.onnx (needs onnx)."""
import collections
from pathlib import Path

import numpy as np
import onnx

model = onnx.load(str(Path(__file__).resolve().parent / "build" / "small0.onnx"))
print("opset", [(o.domain or "ai.onnx", o.version) for o in model.opset_import], "ir", model.ir_version,
      "producer", model.producer_name, model.producer_version)
for kind, values in (("input", model.graph.input), ("output", model.graph.output)):
    for v in values:
        shape = [d.dim_param or d.dim_value for d in v.type.tensor_type.shape.dim]
        print(kind, v.name, shape, onnx.TensorProto.DataType.Name(v.type.tensor_type.elem_type))
print("parameters", sum(int(np.prod(t.dims)) for t in model.graph.initializer))
print(collections.Counter(n.op_type for n in model.graph.node).most_common())

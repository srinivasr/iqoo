import onnx
from onnxconverter_common import float16
import os

# Load
model = onnx.load('network_lgbm_gemm.onnx')

# Check what Cast nodes exist
print("=== Original Cast nodes ===")
for node in model.graph.node:
    if node.op_type == 'Cast':
        for attr in node.attribute:
            if attr.name == 'to':
                print(f"  Cast to: {attr.i} (1=float32, 10=float16)")

# Convert with op_block_list to keep Cast nodes in FP32
# The Cast op gets force-converted back to FP32 output types
model_fp16 = float16.convert_float_to_float16(
    model,
    keep_io_types=True,
    op_block_list=['Cast', 'ArgMax']  # Don't convert Cast and ArgMax
)

onnx.save(model_fp16, 'network_lgbm_gemm_fp16.onnx')
size = os.path.getsize('network_lgbm_gemm_fp16.onnx') / 1024 / 1024
print(f"\nFP16 model: {size:.2f} MB")

#!/usr/bin/env python3
"""
QDQ quantization of the MLP for Hexagon NPU.
QNN EP requires QDQ format models.
"""
import numpy as np
import pandas as pd
from pathlib import Path
from sklearn.preprocessing import LabelEncoder
import joblib
import onnx
from onnxruntime.quantization import (
    quantize_static, CalibrationDataReader, QuantType, QuantFormat,
)

DATA_DIR = Path.home() / "dhrashta-x/data"
MODEL_DIR = DATA_DIR / "models"
FLOAT_MODEL = MODEL_DIR / "mlp_network_v2_float.onnx"
QDQ_MODEL = MODEL_DIR / "mlp_network_v2_qdq.onnx"

# ---- Load calibration data ----
scaler = joblib.load(MODEL_DIR / "scaler_mlp.pkl")
df = pd.read_csv(DATA_DIR / "training_dataset.csv")
META = ["run_id","window_id","window_size_sec","window_start_sec",
        "window_end_sec","primary_source_ip","label","threat_class"]
FEATURES = [c for c in df.columns if c not in META]

# Sample 500 rows across all classes for calibration
calib_df = df.groupby("label", group_keys=False).apply(
    lambda g: g.sample(n=min(50, len(g)), random_state=42)
)
X_calib = calib_df[FEATURES].apply(pd.to_numeric, errors="coerce").fillna(0.0).values.astype(np.float32)
X_calib_scaled = scaler.transform(X_calib).astype(np.float32)
print("Calibration samples: {0}".format(len(X_calib_scaled)))

class Reader(CalibrationDataReader):
    def __init__(self, data, input_name):
        self.data = data
        self.input_name = input_name
        self.idx = 0
    def get_next(self):
        if self.idx >= len(self.data):
            return None
        x = self.data[self.idx:self.idx+1]
        self.idx += 1
        return {self.input_name: x}
    def rewind(self):
        self.idx = 0

reader = Reader(X_calib_scaled, "input")

print("Quantizing (QDQ format, INT8 weights + activations)...")
quantize_static(
    model_input=str(FLOAT_MODEL),
    model_output=str(QDQ_MODEL),
    calibration_data_reader=reader,
    quant_format=QuantFormat.QDQ,
    activation_type=QuantType.QInt8,
    weight_type=QuantType.QInt8,
    per_channel=False,
    reduce_range=False,
)

size_in = FLOAT_MODEL.stat().st_size
size_out = QDQ_MODEL.stat().st_size
print("Float: {0} bytes".format(size_in))
print("QDQ:   {0} bytes  ({1:.1f}x smaller)".format(size_out, size_in / size_out))

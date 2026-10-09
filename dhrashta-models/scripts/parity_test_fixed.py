# === FORCE LEGACY ONNX EXPORTER — MUST BE FIRST ===
import torch.onnx
_orig_export = torch.onnx.export
def _patched_export(*args, **kwargs):
    kwargs['dynamo'] = False
    return _orig_export(*args, **kwargs)
torch.onnx.export = _patched_export
# ================================================

import numpy as np
import lightgbm as lgb
from sklearn.preprocessing import StandardScaler
from sklearn.model_selection import train_test_split
from sklearn.metrics import roc_auc_score
from hummingbird.ml import convert
import onnx
import os

print("=== Loading Mischief data ===")
data = np.load('out_mischief/sequences.npz', allow_pickle=True)
X_raw = data['X']
y = data['y']

X_log = np.sign(X_raw) * np.log1p(np.abs(X_raw))
X_flat = X_log.reshape(len(X_log), -1)

X_tr, X_te, y_tr, y_te = train_test_split(X_flat, y, test_size=0.15, random_state=42, stratify=y)

scaler = StandardScaler()
X_tr_s = scaler.fit_transform(X_tr).astype('float32')
X_te_s = scaler.transform(X_te).astype('float32')

print("=== Training LightGBM (100 trees) ===")
lgbm = lgb.LGBMClassifier(
    n_estimators=100, max_depth=6, num_leaves=31,
    learning_rate=0.05, class_weight='balanced',
    n_jobs=1, verbose=-1
)
lgbm.fit(X_tr_s, y_tr)

p_orig = lgbm.predict_proba(X_te_s)[:, 1]
auc_orig = roc_auc_score(y_te, p_orig)
print(f"Original AUC: {auc_orig:.4f}")

print("\n=== Hummingbird GEMM conversion ===")
try:
    hb = convert(lgbm, 'onnx', X_tr_s[:100],
                 extra_config={"tree_implementation": "gemm"})
    print("Conversion OK")
except Exception as e:
    print(f"FAILED: {e}")
    exit(1)

# Save
onnx_path = 'network_lgbm_gemm.onnx'
onnx.save(hb.model, onnx_path)
size_mb = os.path.getsize(onnx_path) / 1024 / 1024
print(f"ONNX size: {size_mb:.2f} MB")

# Parity
p_hb = hb.predict_proba(X_te_s)[:, 1]
max_diff = np.abs(p_orig - p_hb).max()
auc_hb = roc_auc_score(y_te, p_hb)
print(f"\nMax diff: {max_diff:.6f}")
print(f"HB AUC:   {auc_hb:.4f}")
print(f"AUC diff: {abs(auc_orig - auc_hb):.6f}")

if max_diff < 0.01:
    print("\n✅ PARITY PASSED")
else:
    print("\n❌ PARITY FAILED")

# Ops
print("\n=== ONNX ops used ===")
ops = set()
for node in hb.model.graph.node:
    ops.add(node.op_type)
for op in sorted(ops):
    print(f"  {op}")

#!/usr/bin/env python3
"""
DHRASHTA-X — 13-class MLP for NPU inference.
Trains PyTorch MLP, exports float ONNX, quantizes to QDQ INT8.
Compares against XGBoost baseline on the same test set.
"""
import sys
import numpy as np
import pandas as pd
from pathlib import Path
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.preprocessing import LabelEncoder, StandardScaler
from sklearn.metrics import classification_report, f1_score
from sklearn.utils.class_weight import compute_class_weight
import joblib
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import TensorDataset, DataLoader

# ------------------------------------------------------------------
DATA_DIR = Path.home() / "dhrashta-x/data"
MODEL_DIR = DATA_DIR / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)
DATASET = DATA_DIR / "training_dataset.csv"

RANDOM_STATE = 42
N_SPLITS = 5
MAX_WINDOWS_RUN = 300
WEIGHT_MIN, WEIGHT_MAX = 0.5, 3.0
HIDDEN_1, HIDDEN_2 = 128, 64
DROPOUT = 0.2
EPOCHS = 200
BATCH_SIZE = 64
LR = 1e-3
PATIENCE = 20

torch.manual_seed(RANDOM_STATE)
np.random.seed(RANDOM_STATE)
DEVICE = torch.device("cpu")

# ------------------------------------------------------------------
# Load + cap + split (identical to XGBoost baseline)
# ------------------------------------------------------------------
df = pd.read_csv(DATASET)
print("Loaded: {0}".format(df.shape))

capped = []
for run_id, g in df.groupby("run_id", sort=False):
    if len(g) > MAX_WINDOWS_RUN:
        g = g.sample(n=MAX_WINDOWS_RUN, random_state=RANDOM_STATE)
    capped.append(g)
df = pd.concat(capped, ignore_index=True)
print("After cap ({0}/run): {1} rows, {2} runs".format(
    MAX_WINDOWS_RUN, len(df), df["run_id"].nunique()))

META = ["run_id","window_id","window_size_sec","window_start_sec",
        "window_end_sec","primary_source_ip","label","threat_class"]
FEATURES = [c for c in df.columns if c not in META]
print("Features: {0}".format(len(FEATURES)))

X = df[FEATURES].apply(pd.to_numeric, errors="coerce").fillna(0.0).values.astype(np.float32)
le = LabelEncoder()
y = le.fit_transform(df["label"])
n_classes = len(le.classes_)
print("Classes: {0}".format(n_classes))

# ---- SAME SPLIT as train_model.py ----
min_runs = int(df.groupby("label")["run_id"].nunique().min())
eff_splits = max(2, min(N_SPLITS, min_runs))
sgkf = StratifiedGroupKFold(n_splits=eff_splits, shuffle=True,
                            random_state=RANDOM_STATE)
tr, te = next(sgkf.split(X, y, groups=df["run_id"]))
print("Train: {0} / Test: {1}".format(len(tr), len(te)))

# ---- SAME scaler ----
scaler = StandardScaler().fit(X[tr])
Xs = scaler.transform(X).astype(np.float32)
joblib.dump(scaler, MODEL_DIR / "scaler_mlp.pkl")

X_train, X_test = Xs[tr], Xs[te]
y_train, y_test = y[tr], y[te]

# ---- Softened class weights (identical to XGBoost) ----
present = np.unique(y_train)
raw = compute_class_weight("balanced", classes=present, y=y_train)
soft = np.clip(np.sqrt(raw), WEIGHT_MIN, WEIGHT_MAX)
soft = soft / soft.mean()
weight_map = dict(zip(present, soft))
class_weights = np.ones(n_classes, dtype=np.float32)
for i, w in weight_map.items():
    class_weights[i] = w
print("Class weights:")
for i, name in enumerate(le.classes_):
    print("  {0:14s} {1:.3f}".format(name, class_weights[i]))

# ------------------------------------------------------------------
# MLP Model
# ------------------------------------------------------------------
class MLP(nn.Module):
    def __init__(self, n_in, n_h1, n_h2, n_out, dropout):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(n_in, n_h1),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(n_h1, n_h2),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Linear(n_h2, n_out),
        )
    def forward(self, x):
        return self.net(x)

model = MLP(len(FEATURES), HIDDEN_1, HIDDEN_2, n_classes, DROPOUT).to(DEVICE)
print("MLP params: {0}".format(sum(p.numel() for p in model.parameters())))

# ------------------------------------------------------------------
# Training
# ------------------------------------------------------------------
X_tr_t = torch.tensor(X_train, dtype=torch.float32)
y_tr_t = torch.tensor(y_train, dtype=torch.long)
X_te_t = torch.tensor(X_test, dtype=torch.float32)

train_ds = TensorDataset(X_tr_t, y_tr_t)
train_dl = DataLoader(train_ds, batch_size=BATCH_SIZE, shuffle=True)

cw = torch.tensor(class_weights, dtype=torch.float32)
criterion = nn.CrossEntropyLoss(weight=cw)
optimizer = optim.Adam(model.parameters(), lr=LR, weight_decay=1e-5)
scheduler = optim.lr_scheduler.ReduceLROnPlateau(
    optimizer, mode="min", factor=0.5, patience=10)

best_loss = float("inf")
best_state = None
no_improve = 0

for epoch in range(EPOCHS):
    model.train()
    epoch_loss = 0.0
    for xb, yb in train_dl:
        optimizer.zero_grad()
        out = model(xb)
        loss = criterion(out, yb)
        loss.backward()
        optimizer.step()
        epoch_loss += loss.item() * len(xb)
    epoch_loss /= len(train_ds)

    model.eval()
    with torch.no_grad():
        val_logits = model(X_te_t)
        val_loss = criterion(val_logits, torch.tensor(y_test, dtype=torch.long)).item()
    scheduler.step(val_loss)

    if val_loss < best_loss - 1e-5:
        best_loss = val_loss
        best_state = {k: v.clone() for k, v in model.state_dict().items()}
        no_improve = 0
    else:
        no_improve += 1
        if no_improve >= PATIENCE:
            print("Early stop at epoch {0}".format(epoch))
            break

    if (epoch + 1) % 20 == 0:
        print("  epoch {0:3d}  train_loss={1:.4f}  val_loss={2:.4f}".format(
            epoch + 1, epoch_loss, val_loss))

model.load_state_dict(best_state)
model.eval()
print("Best val loss: {0:.4f}".format(best_loss))

# ------------------------------------------------------------------
# Float MLP evaluation
# ------------------------------------------------------------------
with torch.no_grad():
    logits = model(X_te_t).numpy()
mlp_pred = logits.argmax(axis=1)
mlp_f1_macro = f1_score(y_test, mlp_pred, average="macro", zero_division=0)
print("\nMLP float macro F1: {0:.4f}".format(mlp_f1_macro))
print(classification_report(y_test, mlp_pred, labels=list(range(n_classes)),
      target_names=list(le.classes_), zero_division=0))

# ------------------------------------------------------------------
# Export float ONNX
# ------------------------------------------------------------------
float_path = MODEL_DIR / "mlp_network_v2_float.onnx"

class ExportWrapper(nn.Module):
    """Return softmax probabilities for consistent output with XGBoost."""
    def __init__(self, m):
        super().__init__()
        self.m = m
    def forward(self, x):
        return torch.softmax(self.m(x), dim=1)

export_model = ExportWrapper(model)
export_model.eval()  # CRITICAL: eval mode disables dropout during export
dummy = torch.zeros(1, len(FEATURES), dtype=torch.float32)
torch.onnx.export(
    export_model, dummy, str(float_path),
    input_names=["input"],
    output_names=["probabilities"],
    dynamic_axes={"input": {0: "batch"}, "probabilities": {0: "batch"}},
    opset_version=17,
    dynamo=False,  # legacy exporter embeds weights
)
print("\nWrote {0} ({1} bytes)".format(
    float_path, float_path.stat().st_size))

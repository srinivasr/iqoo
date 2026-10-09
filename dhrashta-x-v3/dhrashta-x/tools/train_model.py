#!/usr/bin/env python3
"""
DHRASHTA-X — 13-class XGBoost baseline.
Reads from ~/dhrashta-x/data/training_dataset.csv
Writes models to ~/dhrashta-x/data/models/
"""
import sys
import pandas as pd
import numpy as np
from pathlib import Path
from sklearn.model_selection import StratifiedGroupKFold
from sklearn.preprocessing import LabelEncoder, StandardScaler
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.utils.class_weight import compute_class_weight
import xgboost as xgb
import joblib

DATASET = Path.home() / "dhrashta-x/data/training_dataset.csv"
MODEL_DIR = Path.home() / "dhrashta-x/data/models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)

MAX_WINDOWS_RUN = 300
N_SPLITS = 5
RANDOM_STATE = 42
WEIGHT_MIN, WEIGHT_MAX = 0.5, 3.0

if not DATASET.exists():
    sys.exit("Dataset not found: {0}".format(DATASET))

df = pd.read_csv(DATASET)
print("Loaded: {0}".format(df.shape))

# Cap windows per run
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

X = df[FEATURES].apply(pd.to_numeric, errors="coerce").fillna(0.0).values
le = LabelEncoder()
y = le.fit_transform(df["label"])
n_classes = len(le.classes_)

print("Classes ({0}):".format(n_classes))
for i, name in enumerate(le.classes_):
    n = int((y == i).sum())
    runs = df[df["label"] == name]["run_id"].nunique()
    print("  [{0:2d}] {1:14s} rows={2:5d} runs={3}".format(i, name, n, runs))
print()

# Reduce n_splits if smallest class has fewer runs
min_runs = int(df.groupby("label")["run_id"].nunique().min())
eff_splits = max(2, min(N_SPLITS, min_runs))
if eff_splits != N_SPLITS:
    print("[!] n_splits reduced from {0} to {1} (min runs={2})".format(
        N_SPLITS, eff_splits, min_runs))

sgkf = StratifiedGroupKFold(n_splits=eff_splits, shuffle=True,
                            random_state=RANDOM_STATE)
tr, te = next(sgkf.split(X, y, groups=df["run_id"]))
print("Train: {0} rows / Test: {1} rows".format(len(tr), len(te)))
print("Train runs: {0} / Test runs: {1}".format(
    df.iloc[tr]["run_id"].nunique(), df.iloc[te]["run_id"].nunique()))

overlap = set(df.iloc[tr]["run_id"]) & set(df.iloc[te]["run_id"])
if overlap:
    sys.exit("[!] LEAKAGE: {0} runs in both splits".format(len(overlap)))
print("No run_id leakage.")
print()

scaler = StandardScaler().fit(X[tr])
Xs = scaler.transform(X)
joblib.dump(scaler, MODEL_DIR / "scaler.pkl")

# Softened class weights
present = np.unique(y[tr])
raw = compute_class_weight("balanced", classes=present, y=y[tr])
soft = np.sqrt(raw)
soft = np.clip(soft, WEIGHT_MIN, WEIGHT_MAX)
soft = soft / soft.mean()
weight_map = dict(zip(present, soft))
print("Class weights (softened):")
for i in range(n_classes):
    w = weight_map.get(i, 0.0)
    print("  {0:14s} {1:.3f}".format(le.classes_[i], w))
print()
sample_weight = np.array([weight_map[c] for c in y[tr]])

clf = xgb.XGBClassifier(
    n_estimators=500, max_depth=5, learning_rate=0.05,
    objective="multi:softprob", num_class=n_classes,
    eval_metric="mlogloss", tree_method="hist",
    n_jobs=-1, random_state=RANDOM_STATE,
)
clf.fit(Xs[tr], y[tr], sample_weight=sample_weight)

joblib.dump(clf, MODEL_DIR / "xgb_multiclass.pkl")
joblib.dump(le, MODEL_DIR / "label_encoder.pkl")

pred = clf.predict(Xs[te])
labels_in_test = sorted(set(y[te]) | set(pred))
names_in_test = [le.classes_[i] for i in labels_in_test]

print("=" * 62)
print("Classification report")
print("=" * 62)
print(classification_report(y[te], pred, labels=labels_in_test,
                            target_names=names_in_test, zero_division=0))
print("Confusion matrix (rows=true, cols=predicted)")
print("Order: {0}".format(names_in_test))
print(confusion_matrix(y[te], pred, labels=labels_in_test))
print("")
print("[+] Models saved to {0}".format(MODEL_DIR))

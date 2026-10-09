import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset
from sklearn.metrics import roc_auc_score, f1_score, confusion_matrix
from sklearn.preprocessing import StandardScaler
import json, os, pickle, gc

torch.manual_seed(42)
np.random.seed(42)
device = torch.device('cpu')

# === LOAD ===
data = np.load('out_mischief/sequences.npz', allow_pickle=True)
X_raw = data['X']          # (5510, 10, 12)
y = data['y']
rat = data['rat']
FEATURES = list(data['features'])

SEQ_LEN = X_raw.shape[1]
N_FEATURES = X_raw.shape[2]
FLAT_DIM = SEQ_LEN * N_FEATURES  # 120

print(f"X: {X_raw.shape}, FLAT_DIM: {FLAT_DIM}")

# log1p
X_log = np.sign(X_raw) * np.log1p(np.abs(X_raw))

# === MLP MODEL (NPU-friendly: Linear, ReLU, Sigmoid only) ===
class NetworkMLP(nn.Module):
    def __init__(self, input_dim=120, h1=64, h2=32):
        super().__init__()
        self.fc1 = nn.Linear(input_dim, h1)
        self.fc2 = nn.Linear(h1, h2)
        self.fc3 = nn.Linear(h2, 1)
        self.relu = nn.ReLU()
        self.dropout = nn.Dropout(0.3)
    def forward(self, x):
        x = self.relu(self.fc1(x))
        x = self.dropout(x)
        x = self.relu(self.fc2(x))
        x = self.dropout(x)
        return self.fc3(x)


def train_mlp(X_tr, y_tr, X_vl, y_vl, epochs=20):
    X_tr_t = torch.FloatTensor(X_tr)
    y_tr_t = torch.FloatTensor(y_tr).unsqueeze(1)
    X_vl_t = torch.FloatTensor(X_vl).to(device)
    y_vl_t = torch.FloatTensor(y_vl).unsqueeze(1).to(device)

    n_pos = max(int(y_tr.sum()), 1)
    n_neg = max(len(y_tr) - n_pos, 1)
    pos_weight = torch.tensor([n_neg / n_pos]).to(device)

    loader = DataLoader(TensorDataset(X_tr_t, y_tr_t), batch_size=128, shuffle=True)
    model = NetworkMLP(FLAT_DIM, 64, 32).to(device)
    criterion = nn.BCEWithLogitsLoss(pos_weight=pos_weight)
    optimizer = torch.optim.Adam(model.parameters(), lr=0.001)

    best_val = float('inf')
    best_state = None
    patience = 0
    for epoch in range(epochs):
        model.train()
        for bx, by in loader:
            bx, by = bx.to(device), by.to(device)
            optimizer.zero_grad()
            loss = criterion(model(bx), by)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
        model.eval()
        with torch.no_grad():
            val_loss = criterion(model(X_vl_t), y_vl_t).item()
        if val_loss < best_val - 1e-4:
            best_val = val_loss
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            patience = 0
        else:
            patience += 1
            if patience >= 5:
                break
    model.load_state_dict(best_state)
    return model


def predict_mlp(model, X):
    model.eval()
    with torch.no_grad():
        logits = model(torch.FloatTensor(X).to(device)).cpu().numpy().flatten()
    return 1 / (1 + np.exp(-logits))


# === LOO CV ===
unique_rats = sorted(set(rat))
TARGET_FPR = 0.01

print(f"=== Leave-One-RAT-Out (MLP) ===\n")

mlp_results = []

for held_out in unique_rats:
    print(f"--- Hold out: {held_out} ---")

    test_mask = (rat == held_out)
    train_pool_mask = ~test_mask

    X_test_raw = X_log[test_mask]
    y_test = y[test_mask]

    train_rat_pool = [r for r in unique_rats if r != held_out]
    val_rat = train_rat_pool[0]
    val_mask_in_pool = (rat[train_pool_mask] == val_rat)

    X_val_raw = X_log[train_pool_mask][val_mask_in_pool]
    y_val = y[train_pool_mask][val_mask_in_pool]
    X_train_raw = X_log[train_pool_mask][~val_mask_in_pool]
    y_train = y[train_pool_mask][~val_mask_in_pool]

    # Flatten (10, 12) -> (120,)
    X_train_flat_raw = X_train_raw.reshape(len(X_train_raw), -1)
    X_val_flat_raw = X_val_raw.reshape(len(X_val_raw), -1)
    X_test_flat_raw = X_test_raw.reshape(len(X_test_raw), -1)

    scaler = StandardScaler()
    scaler.fit(X_train_flat_raw)

    X_train = scaler.transform(X_train_flat_raw).astype('float32')
    X_val = scaler.transform(X_val_flat_raw).astype('float32')
    X_test = scaler.transform(X_test_flat_raw).astype('float32')

    model = train_mlp(X_train, y_train, X_val, y_val, epochs=25)
    probs = predict_mlp(model, X_test)
    auc = roc_auc_score(y_test, probs)

    print(f"  Test n={len(y_test)} (mal={int(y_test.sum())}) | AUC={auc:.4f}")

    mlp_results.append({
        'held_out': held_out,
        'y_test': y_test,
        'probs': probs,
    })

    del model
    gc.collect()

# === Global threshold @ 1% FPR ===
print(f"\n=== Global threshold @ {TARGET_FPR*100:.1f}% FPR ===")
all_benign = []
for fold in mlp_results:
    benign = fold['y_test'] == 0
    all_benign.extend(fold['probs'][benign].tolist())

mlp_thresh = np.quantile(all_benign, 1 - TARGET_FPR)
print(f"MLP threshold: {mlp_thresh:.4f}")

# === Per-fold recall @ 1% FPR ===
print(f"\n{'RAT':<25} {'MLP AUC':<10} {'MLP Rec':<10} {'MLP FPR':<10}")
print("-" * 60)

mlp_aucs, mlp_recalls, mlp_fprs = [], [], []

for fold in mlp_results:
    y_t = fold['y_test']
    p = fold['probs']
    preds = (p >= mlp_thresh).astype(int)

    cm = confusion_matrix(y_t, preds, labels=[0, 1])
    tn, fp, fn, tp = cm.ravel()
    rec = tp / (tp + fn) if (tp + fn) > 0 else 0
    fpr = fp / (fp + tn) if (fp + tn) > 0 else 0
    auc = roc_auc_score(y_t, p)

    mlp_aucs.append(auc)
    mlp_recalls.append(rec)
    mlp_fprs.append(fpr)

    print(f"{fold['held_out']:<25} {auc:<10.4f} {rec*100:<10.1f} {fpr*100:<10.2f}")

print(f"\n{'MEAN':<25} {np.mean(mlp_aucs):<10.4f} {np.mean(mlp_recalls)*100:<10.1f} {np.mean(mlp_fprs)*100:<10.2f}")

# === Comparison table ===
print(f"\n=== COMPARISON (LOO, 8 RAT families) ===")
print(f"{'Model':<20} {'Mean AUC':<12} {'Mean Recall@1%FPR':<20}")
print("-" * 55)
print(f"{'GRU':<20} {0.8690:<12.4f} {20.5:<20.1f}")
print(f"{'LightGBM':<20} {0.9668:<12.4f} {46.8:<20.1f}")
print(f"{'MLP':<20} {np.mean(mlp_aucs):<12.4f} {np.mean(mlp_recalls)*100:<20.1f}")

# === Final model ===
print(f"\n=== Training final MLP on all 8 RATs ===")
scaler_final = StandardScaler()
X_flat_all = X_log.reshape(len(X_log), -1)
scaler_final.fit(X_flat_all)
X_all_scaled = scaler_final.transform(X_flat_all).astype('float32')

from sklearn.model_selection import train_test_split
X_tr, X_vl, y_tr, y_vl = train_test_split(X_all_scaled, y, test_size=0.15, random_state=42, stratify=y)
final_model = train_mlp(X_tr, y_tr, X_vl, y_vl, epochs=30)

# === Export to TorchScript (then TFLite) ===
final_model.eval()
example = torch.randn(1, FLAT_DIM)
traced = torch.jit.trace(final_model, example)
traced.save('network_mlp.pt')
print(f"TorchScript: network_mlp.pt ({os.path.getsize('network_mlp.pt')/1024:.1f} KB)")

# === Export to ONNX (for LiteRT conversion) ===
try:
    onnx_path = 'network_mlp.onnx'
    torch.onnx.export(
        final_model,
        example,
        onnx_path,
        input_names=['input'],
        output_names=['output'],
        dynamic_axes={'input': {0: 'batch'}, 'output': {0: 'batch'}},
        opset_version=13,
        dynamo=False,
    )
    print(f"ONNX: {onnx_path} ({os.path.getsize(onnx_path)/1024:.1f} KB)")
except Exception as e:
    print(f"ONNX export failed: {e}")

# === Save scaler for Kotlin ===
with open('scaler_mlp.json', 'w') as f:
    json.dump({
        'mean': scaler_final.mean_.tolist(),
        'scale': scaler_final.scale_.tolist(),
        'features': FEATURES,
        'sequence_length': SEQ_LEN,
        'flat_dim': FLAT_DIM,
        'log1p_applied': True,
        'threshold_at_1pct_fpr': float(mlp_thresh),
    }, f, indent=2)

with open('scaler_mlp.pkl', 'wb') as f:
    pickle.dump(scaler_final, f)

# === Save metrics ===
metrics = {
    'model': 'NetworkMLP',
    'architecture': f'{FLAT_DIM} -> 64 -> 32 -> 1',
    'transform': 'signed_log1p + StandardScaler',
    'threshold_at_1pct_fpr': float(mlp_thresh),
    'loo': {
        'mean_auc': float(np.mean(mlp_aucs)),
        'std_auc': float(np.std(mlp_aucs)),
        'mean_recall': float(np.mean(mlp_recalls)),
        'std_recall': float(np.std(mlp_recalls)),
        'mean_fpr': float(np.mean(mlp_fprs)),
    },
    'comparison': {
        'gru': {'auc': 0.8690, 'recall': 0.205},
        'lightgbm': {'auc': 0.9668, 'recall': 0.468},
        'mlp': {'auc': float(np.mean(mlp_aucs)), 'recall': float(np.mean(mlp_recalls))},
    },
    'per_fold': [
        {'held_out': f['held_out'], 'auc': float(mlp_aucs[i]),
         'recall': float(mlp_recalls[i]), 'fpr': float(mlp_fprs[i])}
        for i, f in enumerate(mlp_results)
    ]
}
with open('network_metrics_mlp.json', 'w') as f:
    json.dump(metrics, f, indent=2)

print("\n=== DONE ===")
print("Files:")
print("  network_mlp.pt        - TorchScript model")
print("  network_mlp.onnx      - ONNX model (for LiteRT conversion)")
print("  scaler_mlp.json       - Kotlin normalization params")
print("  network_metrics_mlp.json - Full metrics")

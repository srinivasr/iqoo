import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, TensorDataset
from sklearn.metrics import roc_auc_score, f1_score, confusion_matrix
from sklearn.preprocessing import StandardScaler
import lightgbm as lgb
import json, os, pickle, gc

torch.manual_seed(42)
np.random.seed(42)
device = torch.device('cpu')

data = np.load('out_mischief/sequences.npz', allow_pickle=True)
X_raw = data['X']
y = data['y']
rat = data['rat']
FEATURES = list(data['features'])

SEQ_LEN = X_raw.shape[1]
N_FEATURES = X_raw.shape[2]

print(f"X: {X_raw.shape}, y: {y.shape}, features: {N_FEATURES}")

# log1p transform
X_log = np.sign(X_raw) * np.log1p(np.abs(X_raw))
print(f"log1p applied: range [{X_log.min():.2f}, {X_log.max():.2f}]")


class NetworkGRU(nn.Module):
    def __init__(self, input_size=12, hidden=64):
        super().__init__()
        self.gru1 = nn.GRU(input_size, hidden, batch_first=True)
        self.gru2 = nn.GRU(hidden, hidden, batch_first=True)
        self.fc1 = nn.Linear(hidden, 32)
        self.dropout = nn.Dropout(0.3)
        self.fc2 = nn.Linear(32, 1)
    def forward(self, x):
        out, _ = self.gru1(x)
        out, _ = self.gru2(out)
        out = out[:, -1, :]
        out = torch.relu(self.fc1(out))
        out = self.dropout(out)
        return self.fc2(out)


def train_gru(X_tr, y_tr, X_vl, y_vl, epochs=15):
    X_tr_t = torch.FloatTensor(X_tr)
    y_tr_t = torch.FloatTensor(y_tr).unsqueeze(1)
    X_vl_t = torch.FloatTensor(X_vl).to(device)
    y_vl_t = torch.FloatTensor(y_vl).unsqueeze(1).to(device)

    n_pos = max(int(y_tr.sum()), 1)
    n_neg = max(len(y_tr) - n_pos, 1)
    pos_weight = torch.tensor([n_neg / n_pos]).to(device)

    loader = DataLoader(TensorDataset(X_tr_t, y_tr_t), batch_size=128, shuffle=True)
    model = NetworkGRU(N_FEATURES, 64).to(device)
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


def predict_gru(model, X):
    model.eval()
    with torch.no_grad():
        logits = model(torch.FloatTensor(X).to(device)).cpu().numpy().flatten()
    return 1 / (1 + np.exp(-logits))


unique_rats = sorted(set(rat))
print(f"\nRATs: {len(unique_rats)}")
print(f"=== Leave-One-RAT-Out ===\n")

TARGET_FPR = 0.01
gru_results = []

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

    scaler = StandardScaler()
    scaler.fit(X_train_raw.reshape(-1, N_FEATURES))

    X_train = scaler.transform(X_train_raw.reshape(-1, N_FEATURES)).reshape(X_train_raw.shape).astype('float32')
    X_val = scaler.transform(X_val_raw.reshape(-1, N_FEATURES)).reshape(X_val_raw.shape).astype('float32')
    X_test = scaler.transform(X_test_raw.reshape(-1, N_FEATURES)).reshape(X_test_raw.shape).astype('float32')

    # --- GRU ---
    gru_model = train_gru(X_train, y_train, X_val, y_val)
    gru_test_probs = predict_gru(gru_model, X_test)
    gru_auc = roc_auc_score(y_test, gru_test_probs)

    del gru_model
    gc.collect()

    # --- LightGBM baseline (simplified, no callbacks) ---
    X_train_flat = X_train.reshape(len(X_train), -1)
    X_test_flat = X_test.reshape(len(X_test), -1)

    lgbm = lgb.LGBMClassifier(
        n_estimators=100, max_depth=6, num_leaves=31,
        learning_rate=0.05, class_weight='balanced',
        n_jobs=1, verbose=-1  # n_jobs=1 to avoid threading issues
    )
    lgbm.fit(X_train_flat, y_train)
    lgbm_test_probs = lgbm.predict_proba(X_test_flat)[:, 1]
    lgbm_auc = roc_auc_score(y_test, lgbm_test_probs)

    del lgbm
    gc.collect()

    print(f"  Test n={len(y_test)} (mal={int(y_test.sum())}, ben={len(y_test)-int(y_test.sum())})")
    print(f"  GRU AUC={gru_auc:.4f} | LGBM AUC={lgbm_auc:.4f}")

    gru_results.append({
        'held_out': held_out,
        'y_test': y_test,
        'gru_probs': gru_test_probs,
        'lgbm_probs': lgbm_test_probs,
    })

# === Global threshold @ 1% FPR ===
print(f"\n=== Global threshold @ {TARGET_FPR*100:.1f}% FPR ===\n")

all_benign_gru = []
all_benign_lgbm = []
for fold in gru_results:
    benign = fold['y_test'] == 0
    all_benign_gru.extend(fold['gru_probs'][benign].tolist())
    all_benign_lgbm.extend(fold['lgbm_probs'][benign].tolist())

gru_thresh = np.quantile(all_benign_gru, 1 - TARGET_FPR)
lgbm_thresh = np.quantile(all_benign_lgbm, 1 - TARGET_FPR)
print(f"GRU threshold: {gru_thresh:.4f}")
print(f"LGBM threshold: {lgbm_thresh:.4f}")

# === Per-fold recall @ 1% FPR ===
print(f"\n{'RAT':<25} {'GRU AUC':<10} {'GRU Rec':<10} {'GRU FPR':<10} {'LGBM AUC':<10} {'LGBM Rec':<10}")
print("-" * 85)

gru_recalls, gru_fprs, gru_aucs = [], [], []
lgbm_recalls, lgbm_aucs = [], []

for fold in gru_results:
    y_t = fold['y_test']
    gru_p = fold['gru_probs']
    lgbm_p = fold['lgbm_probs']

    gru_preds = (gru_p >= gru_thresh).astype(int)
    lgbm_preds = (lgbm_p >= lgbm_thresh).astype(int)

    def metrics(y_true, preds):
        cm = confusion_matrix(y_true, preds, labels=[0, 1])
        tn, fp, fn, tp = cm.ravel()
        rec = tp / (tp + fn) if (tp + fn) > 0 else 0
        fpr = fp / (fp + tn) if (fp + tn) > 0 else 0
        return rec, fpr

    g_rec, g_fpr = metrics(y_t, gru_preds)
    l_rec, l_fpr = metrics(y_t, lgbm_preds)
    g_auc = roc_auc_score(y_t, gru_p)
    l_auc = roc_auc_score(y_t, lgbm_p)

    gru_recalls.append(g_rec)
    gru_fprs.append(g_fpr)
    gru_aucs.append(g_auc)
    lgbm_recalls.append(l_rec)
    lgbm_aucs.append(l_auc)

    print(f"{fold['held_out']:<25} {g_auc:<10.4f} {g_rec*100:<10.1f} {g_fpr*100:<10.2f} {l_auc:<10.4f} {l_rec*100:<10.1f}")

print(f"\n{'MEAN':<25} {np.mean(gru_aucs):<10.4f} {np.mean(gru_recalls)*100:<10.1f} {np.mean(gru_fprs)*100:<10.2f} {np.mean(lgbm_aucs):<10.4f} {np.mean(lgbm_recalls)*100:<10.1f}")

# === Final model — train on all ===
print(f"\n=== Training final model on all 8 RATs ===")
scaler_final = StandardScaler()
scaler_final.fit(X_log.reshape(-1, N_FEATURES))
X_all = scaler_final.transform(X_log.reshape(-1, N_FEATURES)).reshape(X_log.shape).astype('float32')

from sklearn.model_selection import train_test_split
X_tr, X_vl, y_tr, y_vl = train_test_split(X_all, y, test_size=0.15, random_state=42, stratify=y)
final_model = train_gru(X_tr, y_tr, X_vl, y_vl, epochs=20)

final_model.eval()
example = torch.randn(1, SEQ_LEN, N_FEATURES)
traced = torch.jit.trace(final_model, example)
traced.save('network_anomaly_rat_v3.pt')
print(f"Model saved: network_anomaly_rat_v3.pt ({os.path.getsize('network_anomaly_rat_v3.pt')/1024:.1f} KB)")

with open('scaler_rat_v3.json', 'w') as f:
    json.dump({
        'mean': scaler_final.mean_.tolist(),
        'scale': scaler_final.scale_.tolist(),
        'features': FEATURES,
        'sequence_length': SEQ_LEN,
        'log1p_applied': True,
        'threshold_at_1pct_fpr': float(gru_thresh),
    }, f, indent=2)

metrics = {
    'model': 'NetworkGRU_RAT_v3',
    'transform': 'signed_log1p',
    'threshold': {'gru': float(gru_thresh), 'lgbm': float(lgbm_thresh), 'fpr_target': TARGET_FPR},
    'gru': {'mean_auc': float(np.mean(gru_aucs)), 'std_auc': float(np.std(gru_aucs)),
            'mean_recall': float(np.mean(gru_recalls)), 'std_recall': float(np.std(gru_recalls))},
    'lgbm': {'mean_auc': float(np.mean(lgbm_aucs)), 'std_auc': float(np.std(lgbm_aucs)),
             'mean_recall': float(np.mean(lgbm_recalls))},
    'per_fold': [
        {'held_out': f['held_out'], 'gru_auc': float(gru_aucs[i]), 'gru_recall': float(gru_recalls[i]),
         'lgbm_auc': float(lgbm_aucs[i]), 'lgbm_recall': float(lgbm_recalls[i])}
        for i, f in enumerate(gru_results)
    ]
}
with open('network_metrics_rat_v3.json', 'w') as f:
    json.dump(metrics, f, indent=2)

print("\n=== DONE ===")

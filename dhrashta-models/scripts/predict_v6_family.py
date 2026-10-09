import pandas as pd
import numpy as np
import lightgbm as lgb
import json

with open('feature_order_v3.json') as f:
    order = json.load(f)
DREBIN_FEATS = order['drebin']
MODERN_FEATS = order['modern']
ALL_FEATURES = DREBIN_FEATS + MODERN_FEATS

model = lgb.Booster(model_file='drebin_hybrid_v3.txt')
df = pd.read_csv('extracted_v6.csv')

for f in ALL_FEATURES:
    if f not in df.columns:
        df[f] = 0

X = df[ALL_FEATURES].apply(pd.to_numeric, errors='coerce').fillna(0).astype('float32').values
y_true = df['_label'].values
names = df['_filename'].values
probs = model.predict(X)

# Base strong signals
STRONG_ALONE = {
    'MODERN_NFC_EMULATION': 0.30,
    'MODERN_MQTT_SERVICE': 0.25,
    'MODERN_DYNAMIC_CODE_LOAD': 0.25,
    'MODERN_SMS_TRIAD': 0.30,
    'MODERN_CRYPTO_WALLET_PATHS': 0.20,
    'MODERN_SHORT_SERVICE_NAME': 0.15,
    'MODERN_VPN_SERVICE': 0.20,
    'MODERN_RANDOM_PERMISSION': 0.20,
    'MODERN_RANDOM_SERVICE_CLASS': 0.20,
}

# NEW — V6 family-specific signals (very high specificity)
V6_STRONG = {
    'MODERN_NFC_RELAY': 0.35,              # NFC + INTERNET — very specific
    'MODERN_NFC_HCE': 0.35,                # HostApduService — almost never legit
    'MODERN_EMBEDDED_PAYLOAD': 0.30,       # Hidden APK in resources
    'MODERN_NO_LAUNCHER_ACTIVITY': 0.15,   # Hidden app — suspicious
    'MODERN_MEDIA_PROJECTION_FGS': 0.15,   # Screen recording
    'MODERN_UI_TREE_LOGGING': 0.25,        # Accessibility keylogging
    'MODERN_ACCESSIBILITY_KEYLOG': 0.25,   # Key event logging
    'MODERN_ACCESSIBILITY_VNC': 0.30,      # A11y + screen capture
    'MODERN_VNC_RAT': 0.30,                # VNC protocol
    'MODERN_DROPPER_LOADER': 0.25,         # Payload unpacking
    'MODERN_SECOND_STAGE_LOADER': 0.25,    # DexClassLoader + assets
    'MODERN_FAKE_UPDATE_UI': 0.20,         # Fake update + sideload
    'MODERN_FAKE_UPDATE_PROMPT': 0.20,
    'MODERN_WEBVIEW_OVERLAY': 0.20,        # WebView phishing overlay
    'MODERN_SMS_INTERCEPT': 0.25,          # SMS receiver + read
    'MODERN_DEVICE_ADMIN_PERSIST': 0.25,   # Persistence combo
    'MODERN_SPYNOTE_COMBO': 0.30,          # A11y + NFC + overlay
    'MODERN_BANK_IMPERSONATION': 0.25,     # Bank name + sideload
    'MODERN_RAT_STRINGS': 0.30,            # Explicit RAT indicators
    'MODERN_CRYPTO_STEALER': 0.25,         # Crypto + SMS
    'MODERN_APK_TAMPERED': 0.20,           # ZIP tampering
    'MODERN_FAKE_JOB_LURE': 0.15,          # Job scam
    'MODERN_TRADINGVIEW_LURE': 0.20,       # Fake trading app
    'MODERN_SCREEN_OFF_ACTIVITY': 0.15,    # Background activity
}

CO_OCCURRENCE = {
    'MODERN_RANDOM_CLASS_NAME': 0.15,
    'MODERN_OBFUSCATED_STRINGS': 0.15,
    'MODERN_ANTI_ANALYSIS': 0.10,
    'MODERN_AD_ID_PERMS': 0.10,
    'MODERN_MANY_CUSTOM_PERMS': 0.15,
    'MODERN_RANDOM_PKG_SEGMENT': 0.15,
    'MODERN_LOCATION_FGS': 0.10,
    'MODERN_MICROPHONE_FGS': 0.10,
    'MODERN_ANDROID13_PERMS': 0.05,
}

STRICT_TRIGGERS = [
    'MODERN_NFC_EMULATION','MODERN_NFC_RELAY','MODERN_NFC_HCE','MODERN_MQTT_SERVICE',
    'MODERN_DYNAMIC_CODE_LOAD','MODERN_SMS_TRIAD','MODERN_A11Y_SERVICE',
    'MODERN_SCREEN_CAPTURE','MODERN_CRYPTO_WALLET_PATHS','MODERN_SHORT_SERVICE_NAME',
    'MODERN_DEVICE_ADMIN','MODERN_VPN_SERVICE','MODERN_RANDOM_PERMISSION',
    'MODERN_RANDOM_SERVICE_CLASS','MODERN_EMBEDDED_PAYLOAD','MODERN_VNC_RAT',
    'MODERN_UI_TREE_LOGGING','MODERN_ACCESSIBILITY_KEYLOG','MODERN_ACCESSIBILITY_VNC',
]

WEAK = {'MODERN_FIREBASE_MESSAGING': 0.05}

final_probs = probs.copy()
for i in range(len(df)):
    row = df.iloc[i]
    boost = 0
    
    # Strong alone (v5 + v6)
    for s, w in STRONG_ALONE.items():
        if row.get(s, 0) == 1: boost += w
    for s, w in V6_STRONG.items():
        if row.get(s, 0) == 1: boost += w
    for s, w in WEAK.items():
        if row.get(s, 0) == 1: boost += w
    
    # Co-occurrence gated
    has_trigger = any(row.get(t, 0) == 1 for t in STRICT_TRIGGERS)
    if has_trigger:
        for s, w in CO_OCCURRENCE.items():
            if row.get(s, 0) == 1: boost += w
    
    final_probs[i] = min(probs[i] + boost, 1.0)

print("=== Threshold Analysis (v6 family features) ===")
print(f"{'Thresh':<8} {'Acc':<10} {'Recall':<10} {'Precision':<10} {'FP':<5} {'TP':<5}")
print("-" * 65)
best_f1, best_t = 0, 0.5
for t in [0.50, 0.30, 0.20, 0.15, 0.10, 0.05, 0.02, 0.01]:
    preds = (final_probs >= t).astype(int)
    correct = (preds == y_true).sum()
    tp = ((preds == 1) & (y_true == 1)).sum()
    fp = ((preds == 1) & (y_true == 0)).sum()
    fn = ((preds == 0) & (y_true == 1)).sum()
    recall = tp/(tp+fn) if (tp+fn)>0 else 0
    precision = tp/(tp+fp) if (tp+fp)>0 else 0
    f1 = 2*precision*recall/(precision+recall) if (precision+recall)>0 else 0
    if f1 > best_f1 and precision >= 0.7:
        best_f1, best_t = f1, t
    print(f"{t:<8.2f} {correct}/{len(y_true):<7} {recall*100:<9.1f}% {precision*100:<9.1f}% {fp:<5} {tp:<5}")

print(f"\nBest threshold: {best_t:.2f} (F1 = {best_f1:.4f})")

preds = (final_probs >= best_t).astype(int)
tp = ((preds == 1) & (y_true == 1)).sum()
fp = ((preds == 1) & (y_true == 0)).sum()
fn = ((preds == 0) & (y_true == 1)).sum()
tn = ((preds == 0) & (y_true == 0)).sum()

print(f"\n=== SUMMARY ===")
print(f"Accuracy: {tp+tn}/{len(y_true)} = {(tp+tn)/len(y_true)*100:.1f}%")
print(f"Recall:   {tp}/{tp+fn} = {tp/(tp+fn)*100 if (tp+fn)>0 else 0:.1f}%")
print(f"Precision: {tp}/{tp+fp} = {tp/(tp+fp)*100 if (tp+fp)>0 else 0:.1f}%")
print(f"F1: {2*tp/(2*tp+fp+fn) if (2*tp+fp+fn)>0 else 0:.4f}")

print(f"\n=== Missed Malware (if any) ===")
for i in range(len(names)):
    if y_true[i] == 1 and preds[i] == 0:
        print(f"  {names[i]:<40} prob={final_probs[i]:.4f}")

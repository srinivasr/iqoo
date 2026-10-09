import pandas as pd
import numpy as np
import lightgbm as lgb
from sklearn.model_selection import train_test_split
from sklearn.metrics import roc_auc_score, f1_score
import json

# All features (Drebin + v4 + v5)
MODERN_V4 = [
    'MODERN_A11Y_SERVICE','MODERN_OVERLAY_AND_A11Y','MODERN_NOTIFICATION_LISTENER',
    'MODERN_SCREEN_CAPTURE','MODERN_SMS_INTERNET_COMBO','MODERN_CLIPBOARD_ACCESS',
    'MODERN_CRYPTO_WALLET_PATHS','MODERN_INSTALL_PACKAGES_REQ','MODERN_REQUEST_INSTALL',
    'MODERN_DEVICE_ADMIN','MODERN_USAGE_STATS','MODERN_SYSTEM_ALERT_WINDOW',
    'MODERN_QUERY_ALL_PACKAGES','MODERN_FOREGROUND_SERVICE','MODERN_EXACT_ALARM',
    'MODERN_MANAGE_EXTERNAL_STORAGE','MODERN_ACCESSIBILITY_API_CALLS',
    'MODERN_CLIPBOARD_API_CALLS','MODERN_CRYPTO_KEYSTORE','MODERN_MULTIPLE_C2_DOMAINS',
    'MODERN_NFC_EMULATION','MODERN_MQTT_SERVICE','MODERN_FIREBASE_MESSAGING',
    'MODERN_AD_ID_PERMS','MODERN_GET_INSTALL_REFERRER','MODERN_RANDOM_CLASS_NAME',
    'MODERN_RANDOM_PACKAGE','MODERN_SHORT_SERVICE_NAME','MODERN_MULTI_FG_SERVICE',
    'MODERN_WORKMANAGER','MODERN_WEBVIEW','MODERN_TELEPHONY_COMBO',
    'MODERN_SMS_TRIAD','MODERN_EXT_STORAGE_COMBO','MODERN_LOCATION_COMBO',
    'MODERN_AD_SDK_PRESENT','MODERN_TINY_APK','MODERN_OBFUSCATED_STRINGS',
    'MODERN_WEBVIEW_JS_BRIDGE','MODERN_PERSISTENCE_COMBO','MODERN_ANTI_ANALYSIS',
    'MODERN_DYNAMIC_CODE_LOAD','MODERN_EXFIL_STORAGE',
]
MODERN_V5 = [
    'MODERN_RANDOM_PERMISSION','MODERN_MANY_CUSTOM_PERMS','MODERN_VPN_SERVICE',
    'MODERN_RANDOM_PKG_SEGMENT','MODERN_RANDOM_SERVICE_CLASS','MODERN_LOCATION_FGS',
    'MODERN_MICROPHONE_FGS','MODERN_ANDROID13_PERMS',
]
MODERN_FEATURES = MODERN_V4 + MODERN_V5

print(f"Total modern features: {len(MODERN_FEATURES)}")

# Load Drebin training data
train = pd.read_csv('/home/axotron/dhrashta-ml/drebin/drebin-215.csv', low_memory=False)
train['_label'] = train['class'].map({'S': 1, 'B': 0})
train = train.dropna(subset=['_label'])
drebin_feats = [c for c in train.columns if c not in ['class', '_label']]
print(f"Drebin features: {len(drebin_feats)}")

X_drebin = train[drebin_feats].apply(pd.to_numeric, errors='coerce').fillna(0).astype('float32').values
y = train['_label'].values
X_modern_pad = np.zeros((X_drebin.shape[0], len(MODERN_FEATURES)), dtype='float32')
X = np.hstack([X_drebin, X_modern_pad])

X_tr, X_te, y_tr, y_te = train_test_split(X, y, test_size=0.15, random_state=42, stratify=y)

print("\nTraining...")
model = lgb.LGBMClassifier(
    n_estimators=300, max_depth=8, num_leaves=63,
    learning_rate=0.05, objective='binary',
    class_weight='balanced', n_jobs=2, verbose=-1
)
model.fit(X_tr, y_tr)

preds = model.predict(X_te)
probs = model.predict_proba(X_te)[:, 1]
print(f"Held-out test AUC: {roc_auc_score(y_te, probs):.4f}")
print(f"Held-out test F1:  {f1_score(y_te, preds):.4f}")

model.booster_.save_model('drebin_hybrid_v3.txt')
with open('feature_order_v3.json', 'w') as f:
    json.dump({'drebin': drebin_feats, 'modern': MODERN_FEATURES}, f)
print("Saved: drebin_hybrid_v3.txt, feature_order_v3.json")

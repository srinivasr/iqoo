import os, logging, re, zipfile
logging.disable(logging.DEBUG)
logging.getLogger("androguard").setLevel(logging.CRITICAL)

import pandas as pd
from androguard.core.apk import APK
from androguard.core.dex import DEX

DREBIN_CSV = '/home/axotron/dhrashta-ml/drebin/drebin-215.csv'
header = pd.read_csv(DREBIN_CSV, nrows=0)
DREBIN_FEATURES = [c for c in header.columns if c != 'class']

# All previous v5 features (keep them)
MODERN_V5 = [
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
    'MODERN_RANDOM_PERMISSION','MODERN_MANY_CUSTOM_PERMS','MODERN_VPN_SERVICE',
    'MODERN_RANDOM_PKG_SEGMENT','MODERN_RANDOM_SERVICE_CLASS','MODERN_LOCATION_FGS',
    'MODERN_MICROPHONE_FGS','MODERN_ANDROID13_PERMS',
]

# V6 — Modern trojan family specific features (24 new)
MODERN_V6 = [
    # Albiriox family
    'MODERN_VNC_RAT',                 # VNC/RFB strings
    'MODERN_FAKE_UPDATE_PROMPT',      # fake update UI + sideload
    'MODERN_ACCESSIBILITY_VNC',       # A11y + screen control
    
    # Sturnus family
    'MODERN_UI_TREE_LOGGING',         # getRootInActiveWindow
    'MODERN_MEDIA_PROJECTION_FGS',    # FOREGROUND_SERVICE_MEDIA_PROJECTION
    'MODERN_ACCESSIBILITY_KEYLOG',    # TYPE_VIEW_TEXT_CHANGED
    
    # RecruitRat / SaferRat / Astrinox
    'MODERN_NO_LAUNCHER_ACTIVITY',    # no LAUNCHER intent
    'MODERN_APK_TAMPERED',            # ZIP anomalies
    'MODERN_FAKE_JOB_LURE',           # recruitment strings
    
    # Brokewell
    'MODERN_FAKE_UPDATE_UI',          # system update strings
    'MODERN_DROPPER_LOADER',          # loader → payload
    'MODERN_TRADINGVIEW_LURE',        # fake TradingView
    
    # WindRelay
    'MODERN_NFC_HCE',                 # HostApduService
    'MODERN_NFC_RELAY',               # NFC + INTERNET
    'MODERN_SPYNOTE_COMBO',           # A11y + NFC + overlay
    
    # Mamont
    'MODERN_EMBEDDED_PAYLOAD',        # APK in res/raw or assets
    'MODERN_WEBVIEW_OVERLAY',         # WebView + overlay
    'MODERN_SMS_INTERCEPT',           # SMS receiver
    'MODERN_DEVICE_ADMIN_PERSIST',    # device admin + A11y
    
    # Generic modern
    'MODERN_BANK_IMPERSONATION',      # bank name + non-official pkg
    'MODERN_RAT_STRINGS',             # RAT indicators
    'MODERN_SECOND_STAGE_LOADER',     # DexClassLoader + assets
    'MODERN_SCREEN_OFF_ACTIVITY',     # background activity abuse
    'MODERN_CRYPTO_STEALER',          # crypto wallet + SMS combo
]

ALL_MODERN = MODERN_V5 + MODERN_V6
ALL_FEATURES = DREBIN_FEATURES + ALL_MODERN


def normalize(s):
    return s.replace('L', '', 1).replace(';', '').replace('/', '.').lower()


def looks_random(s):
    if not s or len(s) < 4:
        return False
    letters = [c for c in s if c.isalpha()]
    if len(letters) < 4:
        return False
    vowels = sum(1 for c in letters if c.lower() in 'aeiou')
    if s.isupper() and len(s) >= 6 and vowels < 3:
        return True
    consonant_runs = 0
    cur = 0
    for c in letters:
        if c.lower() not in 'aeiou':
            cur += 1
            if cur >= 4:
                consonant_runs += 1
                cur = 0
        else:
            cur = 0
    if consonant_runs >= 2:
        return True
    if len(letters) >= 6 and vowels <= 1:
        return True
    return False


def extract_features(apk_path):
    try:
        apk = APK(apk_path)
        features = {f: 0 for f in ALL_FEATURES}
        
        perms = set()
        try:
            perms = set(apk.get_permissions())
            for perm in perms:
                short = perm.split('.')[-1]
                if short in features:
                    features[short] = 1
        except Exception:
            pass
        
        short_perms = set(p.split('.')[-1] for p in perms)
        custom_perms = [p for p in perms if not p.startswith('android.permission.') 
                        and not p.startswith('com.android.') and not p.startswith('com.google.')]
        
        # DEX
        all_strings = set()
        all_methods = set()
        all_classes = set()
        class_names_raw = []
        
        try:
            for dex_bytes in list(apk.get_all_dex()):
                try:
                    dex = DEX(dex_bytes)
                    for s in dex.get_strings():
                        try:
                            v = s.get()
                            if v and isinstance(v, str) and len(v) < 200:
                                all_strings.add(v.lower())
                        except Exception:
                            continue
                    for m in dex.get_methods():
                        try:
                            cls = normalize(m.get_class_name())
                            name = m.get_name().lower()
                            all_methods.add(name)
                            all_classes.add(cls)
                            all_classes.add(cls.split('.')[-1])
                            class_names_raw.append(cls)
                        except Exception:
                            continue
                except Exception:
                    continue
        except Exception:
            pass
        
        # Drebin API matching
        API_FEATURES = set(f for f in DREBIN_FEATURES if not f.isupper())
        for feat in API_FEATURES:
            fn = normalize(feat)
            parts = fn.split('.')
            method = parts[-1] if parts else fn
            cls = parts[-2] if len(parts) >= 2 else ''
            if fn in all_strings:
                features[feat] = 1
            elif len(method) > 5 and method in all_methods:
                if not cls or cls in all_classes:
                    features[feat] = 1
        
        # Components
        try:
            services = apk.get_services()
            activities = apk.get_activities()
            receivers = apk.get_receivers()
            providers = apk.get_providers()
        except Exception:
            services, activities, receivers, providers = [], [], [], []
        
        all_components = ' '.join(services + activities + receivers).lower()
        strings_blob = ' '.join(list(all_strings)[:30000])
        pkg = apk.get_package() or ''
        
        # ============ V5 FEATURES ============
        if 'accessibility' in all_components:
            features['MODERN_A11Y_SERVICE'] = 1
        if 'SYSTEM_ALERT_WINDOW' in short_perms and features['MODERN_A11Y_SERVICE']:
            features['MODERN_OVERLAY_AND_A11Y'] = 1
        if 'SYSTEM_ALERT_WINDOW' in short_perms:
            features['MODERN_SYSTEM_ALERT_WINDOW'] = 1
        if 'notificationlistener' in strings_blob:
            features['MODERN_NOTIFICATION_LISTENER'] = 1
        if any(x in strings_blob for x in ['mediarecorder','screencapture','virtualdisplay','mediaprojection']):
            features['MODERN_SCREEN_CAPTURE'] = 1
        if any(p in short_perms for p in ['SEND_SMS','RECEIVE_SMS','READ_SMS']) and 'INTERNET' in short_perms:
            features['MODERN_SMS_INTERNET_COMBO'] = 1
        if 'clipboardmanager' in strings_blob or 'getprimaryclip' in all_methods:
            features['MODERN_CLIPBOARD_ACCESS'] = 1
        if 'getprimaryclip' in all_methods or 'setprimaryclip' in all_methods:
            features['MODERN_CLIPBOARD_API_CALLS'] = 1
        if any(p in strings_blob for p in ['wallet.dat','bitcoin','ethereum','blockchain','metamask']):
            features['MODERN_CRYPTO_WALLET_PATHS'] = 1
        if 'REQUEST_INSTALL_PACKAGES' in short_perms:
            features['MODERN_REQUEST_INSTALL'] = 1
            features['MODERN_INSTALL_PACKAGES_REQ'] = 1
        if 'BIND_DEVICE_ADMIN' in strings_blob or 'deviceadmin' in strings_blob:
            features['MODERN_DEVICE_ADMIN'] = 1
        if 'PACKAGE_USAGE_STATS' in short_perms:
            features['MODERN_USAGE_STATS'] = 1
        if 'QUERY_ALL_PACKAGES' in short_perms:
            features['MODERN_QUERY_ALL_PACKAGES'] = 1
        if 'FOREGROUND_SERVICE' in short_perms:
            features['MODERN_FOREGROUND_SERVICE'] = 1
        if any(p in short_perms for p in ['SCHEDULE_EXACT_ALARM','USE_EXACT_ALARM']):
            features['MODERN_EXACT_ALARM'] = 1
        if 'MANAGE_EXTERNAL_STORAGE' in short_perms:
            features['MODERN_MANAGE_EXTERNAL_STORAGE'] = 1
        if any('accessibility' in c for c in list(all_classes)[:5000]):
            features['MODERN_ACCESSIBILITY_API_CALLS'] = 1
        if 'keystore' in strings_blob or 'keychain' in strings_blob:
            features['MODERN_CRYPTO_KEYSTORE'] = 1
        urls = set(re.findall(r'https?://([a-z0-9.-]+)', strings_blob))
        if len(urls) >= 5:
            features['MODERN_MULTIPLE_C2_DOMAINS'] = 1
        if 'NFC' in short_perms and ('emulation' in all_components or 'hostapdu' in all_components):
            features['MODERN_NFC_EMULATION'] = 1
        if 'mqtt' in all_components or 'paho' in all_components or 'mqtt' in strings_blob:
            features['MODERN_MQTT_SERVICE'] = 1
        if 'firebasemessagingservice' in all_components:
            features['MODERN_FIREBASE_MESSAGING'] = 1
        if any(p in short_perms for p in ['AD_ID','ACCESS_ADSERVICES_AD_ID','ACCESS_ADSERVICES_ATTRIBUTION']):
            features['MODERN_AD_ID_PERMS'] = 1
        if 'BIND_GET_INSTALL_REFERRER_SERVICE' in short_perms:
            features['MODERN_GET_INSTALL_REFERRER'] = 1
        random_count = sum(1 for c in class_names_raw[:500] if looks_random(c.split('.')[-1]))
        if random_count >= 3:
            features['MODERN_RANDOM_CLASS_NAME'] = 1
        if pkg and looks_random(pkg.replace('.', '')):
            features['MODERN_RANDOM_PACKAGE'] = 1
        for s in services:
            last = s.split('.')[-1] if s else ''
            if len(last) <= 3 and last.isalpha():
                features['MODERN_SHORT_SERVICE_NAME'] = 1
                break
        fg_types = [p for p in short_perms if p.startswith('FOREGROUND_SERVICE_')]
        if len(fg_types) >= 3:
            features['MODERN_MULTI_FG_SERVICE'] = 1
        if 'androidx.work' in all_components or 'workmanager' in strings_blob:
            features['MODERN_WORKMANAGER'] = 1
        if 'webview' in strings_blob or 'android.webkit' in all_classes:
            features['MODERN_WEBVIEW'] = 1
        if 'CALL_PHONE' in short_perms and 'READ_PHONE_STATE' in short_perms:
            features['MODERN_TELEPHONY_COMBO'] = 1
        if all(p in short_perms for p in ['SEND_SMS','RECEIVE_SMS','READ_SMS']):
            features['MODERN_SMS_TRIAD'] = 1
        if 'MANAGE_EXTERNAL_STORAGE' in short_perms and 'WRITE_EXTERNAL_STORAGE' in short_perms:
            features['MODERN_EXT_STORAGE_COMBO'] = 1
        if 'ACCESS_FINE_LOCATION' in short_perms and 'ACCESS_COARSE_LOCATION' in short_perms:
            features['MODERN_LOCATION_COMBO'] = 1
        ad_signals = ['admob','com.google.android.gms.ads','com.facebook.ads','applovin','unityads','vungle','inmobi']
        if any(s in strings_blob for s in ad_signals):
            features['MODERN_AD_SDK_PRESENT'] = 1
        size_kb = os.path.getsize(apk_path) / 1024
        if size_kb < 1024:
            features['MODERN_TINY_APK'] = 1
        short_random = sum(1 for s in list(all_strings)[:10000]
                          if 4 <= len(s) <= 12 and sum(1 for c in s if c.isalpha()) >= len(s) * 0.8 
                          and sum(1 for c in s.lower() if c in 'aeiou') == 0)
        if short_random > 200:
            features['MODERN_OBFUSCATED_STRINGS'] = 1
        if 'addjavascriptinterface' in all_methods:
            features['MODERN_WEBVIEW_JS_BRIDGE'] = 1
        if 'RECEIVE_BOOT_COMPLETED' in short_perms and (
            'androidx.work' in all_components or any('jobscheduler' in s.lower() for s in all_components.split())):
            features['MODERN_PERSISTENCE_COMBO'] = 1
        if any(x in strings_blob for x in ['/su','superuser','isrooted','emulator','qemu','genymotion']):
            features['MODERN_ANTI_ANALYSIS'] = 1
        if 'dexclassloader' in strings_blob or 'pathclassloader' in strings_blob:
            features['MODERN_DYNAMIC_CODE_LOAD'] = 1
        if ('WRITE_EXTERNAL_STORAGE' in short_perms and 'INTERNET' in short_perms and
            ('zip' in strings_blob or 'gzip' in strings_blob or 'compress' in strings_blob)):
            features['MODERN_EXFIL_STORAGE'] = 1
        
        random_perms = [p for p in custom_perms if looks_random(p.split('.')[-1])]
        if random_perms:
            features['MODERN_RANDOM_PERMISSION'] = 1
        if len(custom_perms) >= 3:
            features['MODERN_MANY_CUSTOM_PERMS'] = 1
        if any('vpn' in s.lower() for s in services):
            features['MODERN_VPN_SERVICE'] = 1
        if pkg:
            segments = pkg.split('.')
            random_segs = sum(1 for seg in segments if looks_random(seg))
            if random_segs >= 2:
                features['MODERN_RANDOM_PKG_SEGMENT'] = 1
        for s in services:
            parts = s.split('.')
            if parts and looks_random(parts[-1]):
                features['MODERN_RANDOM_SERVICE_CLASS'] = 1
                break
        if 'FOREGROUND_SERVICE_LOCATION' in short_perms:
            features['MODERN_LOCATION_FGS'] = 1
        if 'FOREGROUND_SERVICE_MICROPHONE' in short_perms:
            features['MODERN_MICROPHONE_FGS'] = 1
        android13_perms = ['NEARBY_WIFI_DEVICES','POST_NOTIFICATIONS','READ_MEDIA_IMAGES',
                          'FOREGROUND_SERVICE_LOCATION','FOREGROUND_SERVICE_MICROPHONE',
                          'FOREGROUND_SERVICE_MEDIA_PLAYBACK','NAVIGATION_TEMPLATES',
                          'ACCESS_SURFACE','RAISED_THREAD_PRIORITY']
        if any(p in short_perms for p in android13_perms):
            features['MODERN_ANDROID13_PERMS'] = 1
        
        # ============ V6 — MODERN TROJAN FAMILY FEATURES ============
        
        # 1. MODERN_VNC_RAT — VNC/RFB strings
        vnc_signals = ['vnc', 'rfb', 'tightvnc', 'ultravnc', 'vnckeyboard', 'vncconnection']
        if any(s in strings_blob for s in vnc_signals):
            features['MODERN_VNC_RAT'] = 1
        
        # 2. MODERN_FAKE_UPDATE_PROMPT — fake update + sideload combo
        update_signals = ['system update', 'security update', 'android update', 
                          'update required', 'critical update']
        has_update = any(s in strings_blob for s in update_signals)
        is_sideloaded = not pkg.startswith('com.android.') and not pkg.startswith('com.google.')
        if has_update and 'INTERNET' in short_perms and is_sideloaded:
            features['MODERN_FAKE_UPDATE_PROMPT'] = 1
        
        # 3. MODERN_ACCESSIBILITY_VNC — A11y + screen capture combo
        has_a11y = any('accessibility' in s.lower() for s in services)
        has_screen = any(x in strings_blob for x in ['mediaprojection','virtualdisplay','screencapture'])
        if has_a11y and has_screen:
            features['MODERN_ACCESSIBILITY_VNC'] = 1
        
        # 4. MODERN_UI_TREE_LOGGING — getRootInActiveWindow API
        if 'getrootinactivewindow' in all_methods or 'getrootinactivewindow' in strings_blob:
            features['MODERN_UI_TREE_LOGGING'] = 1
        
        # 5. MODERN_MEDIA_PROJECTION_FGS
        if 'FOREGROUND_SERVICE_MEDIA_PROJECTION' in short_perms:
            features['MODERN_MEDIA_PROJECTION_FGS'] = 1
        
        # 6. MODERN_ACCESSIBILITY_KEYLOG — TYPE_VIEW_TEXT_CHANGED
        if 'type_view_text_changed' in strings_blob or 'type_view_text_selection_changed' in strings_blob:
            features['MODERN_ACCESSIBILITY_KEYLOG'] = 1
        
        # 7. MODERN_NO_LAUNCHER_ACTIVITY
        try:
            launcher_intent = False
            for act in activities:
                # Simple check — if it's a launcher activity, it appears in activities list
                # But we check for MANIFEST-based absence
                pass
            # Check via PackageManager if launcher exists
            if not apk.get_main_activity():
                features['MODERN_NO_LAUNCHER_ACTIVITY'] = 1
        except Exception:
            pass
        
        # 8. MODERN_APK_TAMPERED — ZIP structure anomalies
        try:
            with zipfile.ZipFile(apk_path, 'r') as z:
                # Check for common APK entries
                names = z.namelist()
                has_manifest = any('AndroidManifest.xml' in n for n in names)
                has_classes = any('classes.dex' in n for n in names)
                # Check for duplicate entries (tampering sign)
                duplicates = len(names) - len(set(names))
                if duplicates > 0 or not has_manifest or not has_classes:
                    features['MODERN_APK_TAMPERED'] = 1
        except Exception:
            pass
        
        # 9. MODERN_FAKE_JOB_LURE — recruitment strings
        job_signals = ['recruitment', 'job offer', 'hiring', 'salary', 'work from home',
                      'part time job', 'earn money', 'daily payment']
        if sum(1 for s in job_signals if s in strings_blob) >= 2:
            features['MODERN_FAKE_JOB_LURE'] = 1
        
        # 10. MODERN_FAKE_UPDATE_UI — system update strings from non-system app
        if has_update and is_sideloaded:
            features['MODERN_FAKE_UPDATE_UI'] = 1
        
        # 11. MODERN_DROPPER_LOADER — 2-stage pattern
        loader_signals = ['payload', 'decrypt', 'aes', 'unpack', 'extract_apk', 
                         'load_dex', 'download_apk', 'install_payload']
        if sum(1 for s in loader_signals if s in strings_blob) >= 3:
            features['MODERN_DROPPER_LOADER'] = 1
        
        # 12. MODERN_TRADINGVIEW_LURE — fake financial app
        trade_signals = ['tradingview', 'binance', 'coinbase', 'metamask', 'trustwallet',
                        'crypto invest', 'forex trading']
        if any(s in strings_blob for s in trade_signals):
            features['MODERN_TRADINGVIEW_LURE'] = 1
        
        # 13. MODERN_NFC_HCE — HostApduService declared
        if 'hostapduservice' in all_components or 'hostapdu' in all_components:
            features['MODERN_NFC_HCE'] = 1
        
        # 14. MODERN_NFC_RELAY — NFC + INTERNET + continuous
        if 'NFC' in short_perms and 'INTERNET' in short_perms:
            # Check for socket + nfc combo
            if any(s in all_methods for s in ['getdefaultadapter', 'isenabled', 'enableforegrounddispatch']):
                features['MODERN_NFC_RELAY'] = 1
        
        # 15. MODERN_SPYNOTE_COMBO — A11y + NFC + overlay
        if (has_a11y and 'NFC' in short_perms and 'SYSTEM_ALERT_WINDOW' in short_perms):
            features['MODERN_SPYNOTE_COMBO'] = 1
        
        # 16. MODERN_EMBEDDED_PAYLOAD — APK in res/raw or assets
        try:
            with zipfile.ZipFile(apk_path, 'r') as z:
                names = z.namelist()
                for n in names:
                    low = n.lower()
                    if (low.startswith('res/raw/') or low.startswith('assets/')) and \
                       (low.endswith('.bin') or low.endswith('.dat') or low.endswith('.enc') or 
                        low.endswith('.apk') or low.endswith('.dex')):
                        # Check size — payload typically > 50KB
                        info = z.getinfo(n)
                        if info.file_size > 50000:
                            features['MODERN_EMBEDDED_PAYLOAD'] = 1
                            break
        except Exception:
            pass
        
        # 17. MODERN_WEBVIEW_OVERLAY
        if ('SYSTEM_ALERT_WINDOW' in short_perms and 
            any('webview' in s.lower() for s in services + activities)):
            features['MODERN_WEBVIEW_OVERLAY'] = 1
        
        # 18. MODERN_SMS_INTERCEPT — SMS receiver + read SMS
        sms_receiver = any('sms' in r.lower() for r in receivers) or \
                       any('smsreceiver' in r.lower() for r in receivers)
        if sms_receiver and 'READ_SMS' in short_perms:
            features['MODERN_SMS_INTERCEPT'] = 1
        
        # 19. MODERN_DEVICE_ADMIN_PERSIST — device admin + A11y
        has_device_admin = ('BIND_DEVICE_ADMIN' in strings_blob or 
                           any('deviceadmin' in s.lower() for s in services))
        if has_device_admin and has_a11y:
            features['MODERN_DEVICE_ADMIN_PERSIST'] = 1
        
        # 20. MODERN_BANK_IMPERSONATION — bank names + non-official pkg
        bank_names = ['sbi', 'hdfc', 'icici', 'axis', 'kotak', 'paytm', 'phonepe',
                     'gpay', 'googlepay', 'pnb', 'bob', 'canara']
        official_pkgs = {'com.sbi.lotusintouch', 'net.one97.paytm', 'com.phonepe.app',
                        'com.google.android.apps.nbu.paisa.user'}
        has_bank_name = any(b in strings_blob for b in bank_names)
        if has_bank_name and pkg not in official_pkgs and is_sideloaded:
            features['MODERN_BANK_IMPERSONATION'] = 1
        
        # 21. MODERN_RAT_STRINGS
        rat_signals = ['rat_client', 'remote_shell', 'reverse_shell', 'bind_shell',
                      'keylogger', 'screen_stream', 'mic_record', 'cam_capture']
        if any(s in strings_blob for s in rat_signals):
            features['MODERN_RAT_STRINGS'] = 1
        
        # 22. MODERN_SECOND_STAGE_LOADER — DexClassLoader + assets
        if ('dexclassloader' in strings_blob and 
            ('assets/' in strings_blob or 'res/raw' in strings_blob)):
            features['MODERN_SECOND_STAGE_LOADER'] = 1
        
        # 23. MODERN_SCREEN_OFF_ACTIVITY
        if any('wake_lock' in p.lower() for p in short_perms) and has_a11y:
            features['MODERN_SCREEN_OFF_ACTIVITY'] = 1
        
        # 24. MODERN_CRYPTO_STEALER — crypto + SMS combo
        has_crypto = features['MODERN_CRYPTO_WALLET_PATHS'] == 1 or features['MODERN_CRYPTO_KEYSTORE'] == 1
        has_sms = any(p in short_perms for p in ['SEND_SMS','READ_SMS','RECEIVE_SMS'])
        if has_crypto and has_sms:
            features['MODERN_CRYPTO_STEALER'] = 1
        
        modern_matched = sum(1 for f in ALL_MODERN if features[f] == 1)
        drebin_matched = sum(1 for f in DREBIN_FEATURES if features[f] == 1)
        return features, drebin_matched + modern_matched, drebin_matched, modern_matched
    except Exception as e:
        print(f"ERR: {e}", end=" ")
        return None, 0, 0, 0


def process_folder(folder, label, out):
    if not os.path.exists(folder):
        return
    apks = sorted([f for f in os.listdir(folder) if f.endswith('.apk')])
    print(f"\n=== {label}: {len(apks)} APKs ===")
    for i, apk in enumerate(apks, 1):
        path = os.path.join(folder, apk)
        size = os.path.getsize(path) / 1024 / 1024
        print(f"[{i}/{len(apks)}] {apk[:35]:<35} ({size:.1f}MB)", end=" ")
        r = extract_features(path)
        if r[0]:
            feats, total, dr, mf = r
            feats['_filename'] = apk
            feats['_label'] = label
            out.append(feats)
            print(f"→ dr={dr} mf={mf}", flush=True)


if __name__ == "__main__":
    out = []
    process_folder('/home/axotron/dhrashta-ml/validation/benign', 0, out)
    process_folder('/home/axotron/dhrashta-ml/validation/malware', 1, out)
    if out:
        df = pd.DataFrame(out)
        cols = ['_filename', '_label'] + [f for f in ALL_FEATURES if f in df.columns]
        df[cols].to_csv('extracted_v6.csv', index=False)
        print(f"\n=== Saved: extracted_v6.csv ({len(df)} rows) ===")
        
        # Show V6 family-specific features
        print("\n=== V6 FAMILY-SPECIFIC FEATURES ===")
        for _, row in df.iterrows():
            fired = [f.replace('MODERN_','') for f in MODERN_V6 if row.get(f, 0) == 1]
            if fired:
                label = 'Mal' if row['_label'] == 1 else 'Ben'
                print(f"  [{label}] {row['_filename'][:35]:<37} ({len(fired)}): {', '.join(fired)}")

<div align="center">

# DHRASHTA-X

On-device detection of accessibility-abuse and banking-trojan patterns on Android, with per-app network containment. No telemetry. Android 8.0+ (API 26), targets API 36.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg?style=flat&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android Target](https://img.shields.io/badge/Android-API%2026--36-3DDC84.svg?style=flat&logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg?style=flat&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![Zero Telemetry](https://img.shields.io/badge/Telemetry-Zero%20(Local%20Only)-0D9488.svg?style=flat)](https://github.com/srinivasr/iqoo)

<p align="center">
  <a href="#overview">Overview</a> •
  <a href="#visual-tour">Visual Tour</a> •
  <a href="#what-it-does-and-does-not-see">Coverage</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#temporal-causal-chains">Causal Chains</a> •
  <a href="#network-containment">Quarantine</a> •
  <a href="#quick-start">Quick Start</a>
</p>

</div>

## Overview

Android banking trojans usually work in steps: a sideloaded app asks for Accessibility, waits for a bank app to open, then acts. DHRASHTA-X looks for those steps **in order**, on the phone, and can cut the network for one suspicious app while every other app keeps working.

- Scoring, explanations and containment all run locally. DHRASHTA-X sends no telemetry.
- Order-aware causal chains (for example: sideload, then Accessibility within 10 minutes) add to the score only when at least one step belongs to the app being scored.
- Per-app containment: a VPN scoped to the paused app only. The app is shown as paused only after that tunnel exists.
- Optional decoy data (off by default) catches an app that tries to send a planted value through DNS or while it is paused. The packet is dropped, so it is recorded as a blocked attempt.
- Explanations are built only from the signals that actually fired, in English, Hindi and Bengali (other languages fall back to English for the newest text), with Text-to-Speech.

DHRASHTA-X raises evidence-based risk scores. A high score is a reason to review an app, not proof that it is malware.

## Visual Tour

<p align="center">
  <img src="docs/assets/app-showcase.png" width="100%" alt="DHRASHTA-X Mobile Application Showcase" />
</p>

<p align="center">
  <em>Left to right: (1) Home dashboard with threat scores and pending reviews; (2) Activity timeline with sensor telemetry and hourly breakdown; (3) First-launch language selection.</em>
</p>

<h3 align="center">Threat Evaluation & Network Containment</h3>

<p align="center">
  <a href="docs/assets/demo-video.mp4">
    <img src="docs/assets/demo-preview.gif" width="34%" alt="DHRASHTA-X Walkthrough Animation" style="display: inline-block; margin: 0 auto;" />
  </a>
</p>

<p align="center">
  <em>Screen capture of threat scoring and per-app isolation. Click the preview to watch the full recording (<a href="docs/assets/demo-video.mp4">demo-video.mp4</a>).</em>
</p>

## What it does and does not see

Settings → **What is monitored** shows this list live on the phone.

| Area | Status in this build |
|---|---|
| Accessibility services | Watched continuously (enable events + every scan) |
| DNS lookups | Watched when the DNS monitor is on (needs VPN consent); used for beacon timing |
| Full network traffic | Relayed and measured **only for watched apps** (live inspection, below). Other apps never enter the VPN except for DNS monitoring |
| Banking app in foreground | Needs Usage Access |
| Decoy data | Opt-in. Plain-text DNS/paused-app traffic only; TLS payloads are never matched |
| Installed APKs (static) | Every user-installed app is scanned on-device by the S0 static model: at first start, on every new install, and on "Scan now" |
| Live network model | Scores every watched app every 10 s from its real traffic; verified on-device by replaying 12 real Mischief sequences |
| 13-class network model (v3) | Loaded and verified with its golden self-test only; it needs 82 CIC-style flow features that the relay does not compute, so it gets no live input |
| Accessibility model | Not bundled. The loader only accepts a model with a `BENIGN` class |
| Threat list | Release ships an empty list with provenance (`meta.source`). Debug builds load labelled demo values |

## On-device models

Bundled from `dhrashta-x-v3`: a 13-class network MLP (82 features per 5-second window), quantized (QDQ, 25 KB) with a float fallback. `app/src/main/assets/models/network_model.json` holds the feature order, class order, scaler and file hashes.

- **Backend is reported, not assumed.** The app first tries Android NNAPI with NNAPI's CPU path and ONNX Runtime's CPU fallback both disabled; a session only exists in that mode if every node runs on an accelerator. Otherwise it runs on the ONNX Runtime CPU provider and says so.
- **Self-test:** Settings → *Run model self-test* replays the 13 golden windows from `test-data/test_vector.json` and compares with outputs recorded from ONNX Runtime on the same files (tolerance 0.05).
- Known result on those 13 vectors: 12/13 top-class correct. The `C2_BEACON` window is classified `BENIGN` by the MLPs (and by the XGBoost baseline).

### S0: static APK model

`drebin_hybrid_v3` (LightGBM, 300 trees, Drebin-215 + 51 modern features) exported with onnxmltools to `static_lgbm.onnx` (TreeEnsembleClassifier, ONNX Runtime **CPU**; tree ensembles have no NNAPI kernel, so S0 never claims the NPU). Then the stage-2 family rules from `predict_v6_family.py` (`static_rules.json`, copied by script, not retyped): `S0 = min(model + rule boost, 1)`.

- **Features are extracted on the phone** by a Kotlin port of `extract_v6.py` (`ai/staticml/`): permissions and components from PackageManager, `classes*.dex` method tables read by a small dex parser, ZIP entries read from the central directory (so duplicate-entry tampering is visible). No APK leaves the device.
- **Parity:** on 4 real APKs the Kotlin extractor fires exactly the same features as the Python script (`scripts/static/parity/`). The exported ONNX matches LightGBM to 1e-7 on all 44 samples of `extracted_v6.csv`, and the app replays those 44 samples as its S0 self-test.
- **Found while porting:** with androguard 4.x the script's `s.get()` on dex strings raised, so every string-content feature (e.g. `MODERN_VNC_RAT`, `MODERN_BANK_IMPERSONATION`, `android.intent.action.SEND`) was always 0 in the training CSV. The phone reproduces that (`USE_STRING_CONTENT = false`) because the weights were tuned on it; enabling real strings needs re-tuning first.
- **Validation (small, be careful):** 42 modern banking-trojan/RAT APKs + 2 benign. At S0 ≥ 0.5: 16/42 malware, 0/2 benign; at ≥ 0.2: 26/42, 0/2. The model alone (no rules) flags 1/42 at 0.5. A false-positive rate is not established: a benign SMS app (QKSMS) scores 1.0 because SMS apps share the SMS-stealer manifest pattern.
- **Use in scoring:** S0 ≥ 0.5 adds `S0_HIGH` (+25), ≥ 0.2 adds `S0_MEDIUM` (+10). It is one signal among the others, never a verdict on its own; a sideloaded app with S0 ≥ 0.5 gets a "review before granting accessibility" notification, not containment.

### Live network model (live traffic input)

**How traffic gets to the model.** An app chosen for live inspection (by the user: *Watch live traffic*; or automatically: a sideloaded app with static score ≥ 0.2, or an app the engine rates REVIEW/HIGH) is added to the app-scoped VPN tunnel. Its IPv4 TCP and UDP are terminated by a small userspace relay (`enforcement/relay/UserspaceNat.kt`) and re-sent on protected sockets, so the app keeps working while every packet in both directions is measured. IPv6 from watched apps is dropped so it cannot bypass inspection (apps fall back to IPv4). Paused apps in the same tunnel stay black-holed.

**Features and model.** The relay builds the same 12 features per 10-second window as `mischief_extract.py` (bytes and packets up/down, ratio, mean size, unique destination IPs and ports, inter-arrival mean/std, SYN count, UDP fraction). Every 10 s, each watched app's last 10 windows go to `network_live_mlp.onnx` (the 120→64→32→1 MLP from `train_mlp_loro.py`, signed log1p + StandardScaler, Gemm/ReLU only, so it can run fully on an NNAPI accelerator). Probability ≥ 0.9457 (1% FPR in leave-one-RAT-out) raises `E4` (+20), logs an event and alerts.

**Verified here, not assumed:**
- Relay tested against the real Linux TCP/IP stack through a TUN device: 5 MB downloads and 2 MB uploads byte-identical, 16 parallel downloads over a deliberately lossy link (go-back-N retransmission), UDP echo 20/20, refused ports fail in ms, all flows closed afterwards.
- Kotlin window features equal the Python extractor on 590 windows (max relative difference 6e-8).
- The 8 Mischief captures run through this windowing: in-sample AUC 0.991 and 0.1% alerts on the phone's normal background traffic. **Held-out (leave-one-RAT-out) numbers are lower and are the ones to quote: AUC 0.854, 15% recall at 1% FPR.**

**Limits.** Features are measured at the TUN (as the app's TCP/IP stack sees them), not on the radio. The model was trained on 8 RATs in a lab capture; per-app traffic on a real phone may differ, so `E4` is one signal among the others and never pauses an app by itself.

## Temporal causal chains

| Chain | Sequence | Window | Bonus |
|---|---|---|---|
| `CC-1` | Sideload → Accessibility enabled | 10 min | +15 |
| `CC-2` | Accessibility enabled → DNS beacon from this app | 5 min | +15 |
| `CC-3` | Banking app in foreground → this app tries to send a decoy (blocked) | 60 s | +25 |
| `CC-4` | ADB enabled → privilege grant to this app | 10 min | +15 |
| `CC-5` | Work profile created → this app appears in a profile | 30 min | +20 |

The total chain bonus is capped at +30. Device-wide events (ADB, bank foreground, work profile) can complete a chain only together with a step logged under the app itself.

## Scoring, bands and trust

`Score = max(0, sum of fired signal weights + chain bonus)`

| Band | Score | Default action |
|---|---|---|
| `SAFE` | 0–29 | None |
| `REVIEW` | 30–59 | Notify |
| `HIGH` | 60–89 | Notify with guided review |
| `CRITICAL` | ≥ 90 | Request per-app containment (shown as paused only once confirmed) |

**Trusted apps** keep their real score everywhere (engine, list, details). Trust only mutes automatic containment and alerts, except for hard evidence (certificate on the threat list, or a blocked decoy attempt), which still alerts. One policy (`TrustPolicy`) is used by the engine, the notifications and the UI.

Brand impersonation (`B6`) ignores official bank/government packages, Play-installed apps and system apps; short names such as "sbi" must be a whole word, so "Usb input" does not match.

## Network containment

`GuardVpnService` has two modes:

- **DNS monitor** (nothing paused): routes only the virtual resolver `10.0.0.53`. Each query gets its own relay ID (no cross-client mix-ups), IPv4 headers are validated, large answers are sent as IP fragments, TCP to the resolver gets an immediate RST, and IPv6 traffic is explicitly allowed to bypass. A query carrying a decoy is not forwarded.
- **Containment** (one or more apps paused): a tunnel scoped with `addAllowedApplication` to those apps only. If no package can be added, no tunnel is created (an empty allow-list would capture every app).

## Build

Requirements: Android Studio (recent, with Android SDK Platform 36) or JDK 17 + Android SDK. The Gradle wrapper (Gradle 8.11.1, Android Gradle Plugin 8.10.1) is included.

```bash
./gradlew test            # JVM unit tests
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

On Windows use `gradlew.bat`. `scripts/android-env.sh` is optional: it uses a project-local `.tooling/` folder only if one exists.

## Project structure

```
iqoo/
├── app/src/main/
│   ├── assets/models/        # ONNX models, metadata, self-test vectors
│   ├── assets/threats.json   # Empty indicator list with provenance (release)
│   └── java/com/dhrashta/x/
│       ├── ai/               # ONNX Runtime wrapper, network classifier, optional a11y model
│       │   └── staticml/     # S0: APK/dex reader, extract_v6 port, static model + family rules
│       ├── data/             # Room database (pruned to 7 days / row caps), allow list, state
│       ├── decision/         # RiskEngine, CausalChains, TrustPolicy, BrandHeuristic, explanations
│       ├── enforcement/      # GuardVpnService, DnsForwarder, decoys, beacon detector
│       │   └── relay/        # Userspace TCP/UDP relay + live 10 s window features
│       ├── sensing/          # Accessibility, package, posture, usage, profile watchers
│       └── ui/               # Compose screens
├── app/src/debug/assets/     # Labelled demo threat list (debug builds only)
├── dhrashta-x-v3/            # Model source package (training scripts, schema, test vectors)
└── docs/assets/              # Screenshots and demo video
```

## License

Copyright © 2026 the DHRASHTA-X authors. All rights reserved; see [LICENSE](LICENSE).

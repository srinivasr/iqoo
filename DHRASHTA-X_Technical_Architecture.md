# DHRASHTA-X — Technical Architecture (Complete Reference)

*On-device Android defense against banking trojans, RATs and spyware. 100% on-device; no data leaves the phone.*

> Every value in this document is taken from the actual source in the APK. Where something is a research experiment and not shipped, it is marked **[Research]**.

---

## 1. System overview

DHRASHTA-X scores every app with **four cooperating layers**, then responds. All inference, rules and storage run on the phone.

```
 Sensors / tripwires ─▶ Features ─▶ 4 detection layers ─▶ Risk score & band ─▶ Response
   (installs, a11y,       (static      L1 Static ML (CPU)     SAFE / REVIEW /      Per-app VPN pause
    DNS, decoys)          + traffic)   L2 Live network (NPU)   HIGH / CRITICAL      Decoy block
                                       L3 Rules (points)                            Explain + voice
                                       L4 Causal chains                             Guided recovery
```

**Code map (`com.dhrashta.x`):**

| Package | Role |
|---|---|
| `sensing/` | Watchers (accessibility, package install, posture, usage, work-profile) + the foreground monitor service |
| `ai/` | ONNX wrapper, accessibility MLP, live network model |
| `ai/staticml/` | S0: APK/dex reader, feature extractor, static model + boost rules |
| `enforcement/` | VpnService, DNS forwarder, decoys, beacon detector, packet inspector |
| `enforcement/relay/` | User-space TCP/UDP relay + live traffic window features |
| `decision/` | RiskEngine, SignalCatalogue, CausalChains, TrustPolicy, BrandHeuristic, Explanations |
| `data/` | Room (SQLite) database, DAOs, event logger, allow-list, scan/score stores |
| `ui/` | Jetpack Compose screens |

---

## 2. Sensors & tripwires (the raw event stream)

Running inside `DhrashtaForegroundService` (a foreground service, restarts on boot):

| Watcher | What it observes | Event written |
|---|---|---|
| `A11yObserver` | Accessibility services being enabled | `a11y_enabled`, `privilege_change` |
| `PackageWatcher` | New app installs (`ACTION_PACKAGE_ADDED`) | `sideload` (if installer ≠ Play Store) |
| `DevicePostureWatcher` | Wireless debugging (ADB) state | `adb_enabled` |
| `ProfileAppWatcher` | New work profile / cloned app | `work_profile_created`, `cloned_app_launched` |
| `UsageWatcher` | Bank app in foreground (needs Usage Access) | `bank_foreground` |
| `BeaconDetector` (via VPN DNS) | Periodic DNS to one host | `beacon_unknown_host` |
| `CanaryManager` + VPN | A planted decoy leaving the phone | `canary_send_blocked` |
| `GuardVpnService` live scoring | Live traffic classed as RAT | `network_ml_alert` |

Every event carries `(timestamp, package, signalId, weight)` and is the input to the causal chains and the activity timeline.

---

## 3. Layer 1 — Static app scan (S0)

**Goal:** when an app is installed (or on "Scan now"), decide how much its *code + manifest* resemble banking-trojan / RAT families — fully on the phone, APK never leaves.

### 3.1 On-device feature extraction (`ai/staticml/`)
- `ApkArchive.kt` — reads the APK's ZIP central directory itself (so duplicate-entry *tampering* is visible; `ZipFile` would refuse such files).
- `DexReader.kt` — a minimal dex parser that reads the **string table** and **every method_id** (name + class) from `classes*.dex`. Matches androguard's `get_methods()`.
- `StaticFeatureExtractor.kt` — a line-for-line Kotlin port of the Python training extractor (`extract_v6.py`). Produces a **266-feature** vector:
  - **215 Drebin features** (permissions, API calls, intents, components).
  - **51 MODERN features** (accessibility abuse, overlay+a11y, SMS theft, NFC relay/HCE, fake-update lures, droppers, bank-name impersonation, obfuscation, etc.).
- **Verified:** on 4 real APKs the Kotlin extractor fires the *identical* feature set as the Python reference.
- **Important honesty note:** in training (androguard 4.x) `get_strings()` returned plain strings so the script's `s.get()` raised — every *string-content* feature was always 0 in the training CSV. The phone reproduces that exactly (`USE_STRING_CONTENT = false`), so the model sees what it was trained on. Turning string features on would need the weights re-tuned.

### 3.2 Model + fusion (`StaticModel.kt`, `StaticBoost.kt`)
- **Model:** LightGBM `drebin_hybrid_v3` — **300 trees**, 266 features — exported with onnxmltools to `static_lgbm.onnx` (**TreeEnsembleClassifier**). ONNX↔LightGBM parity: max diff **9.6×10⁻⁸**.
- **Runtime:** ONNX Runtime **CPU** (tree ensembles are not NNAPI ops, so S0 is honestly CPU; the NPU path is Layer 2).
- **Stage-2 boost rules** (`static_rules.json`, copied verbatim from `predict_v6_family.py`):
  - `boost = Σ STRONG_ALONE + Σ V6_STRONG + Σ WEAK`, plus `Σ CO_OCCURRENCE` **only if** any `STRICT_TRIGGERS` feature fired.
  - **Final: `S0 = min(base_prob + boost, 1.0)`**.
- **Bands:** `S0 ≥ 0.5` → high; `S0 ≥ 0.2` → medium (constants `StaticVerdict.HIGH/MEDIUM`).

### 3.3 Into the score
`RiskEngine` adds **S0_HIGH (+25)** if S0 ≥ 0.5, else **S0_MEDIUM (+10)** if S0 ≥ 0.2, else nothing.

### 3.4 Numbers
- Classic Drebin-215 benchmark (215-feature sibling): AUC 0.9987, F1 0.987, recall 0.984.
- Shipped 266-feature model on 42 modern RAT/trojan APKs + 2 benign: `S0 ≥ 0.5` → **16/42 (38%)**, 0 FP; `S0 ≥ 0.2` → **26/42 (62%)**, 0 FP.
- **Self-test on device:** replays 44 real extracted samples; shows match count + max difference.

---

## 4. Layer 2 — Live network model (behavioral)

**Goal:** watch a *suspicious* app's real traffic and decide if it looks like a RAT talking to C2 — on-device, no root.

### 4.1 Getting live traffic without root (`enforcement/relay/`)
- A watched app (chosen by the user "Watch live traffic", or auto: sideloaded + S0 ≥ 0.2, or engine band REVIEW/HIGH) is added to the VPN tunnel.
- `UserspaceNat.kt` — a user-space **TCP/UDP relay**: it terminates the app's IPv4 flows and re-sends them on protected sockets, so the app works normally while every packet (both directions) is measured. Has a small TCP state machine with go-back-N retransmission; IPv6 from watched apps is dropped so it falls back to IPv4 (can't bypass inspection).
- **Verified:** against a real Linux TCP/IP stack via a TUN device — 5 MB download + 2 MB upload byte-identical; recovers from packet loss; UDP echo 20/20; all flows close cleanly.
- `LiveWindowFeatures.kt` — builds **12 features per 10-second window** (bytes up/down, pkts up/down, up/down ratio, mean pkt size, unique dst IPs, unique dst ports, inter-arrival mean/std, SYN count, UDP fraction), identical to the Python extractor (checked over 590 windows, max diff 6×10⁻⁸).

### 4.2 Model (`ai/LiveNetworkModel.kt`)
- **Model:** LightGBM compiled to tensor ops via **Hummingbird GEMM**, **FP16** (`network_live_gemm_fp16.onnx`, 909 KB). Ops are Gemm/MatMul/Sigmoid — **no TreeEnsemble op** — so it can run on an **NNAPI accelerator (NPU)**. `OnnxModel` tries a strict NNAPI-only session first and reports NPU vs CPU honestly.
- **Input:** last 10 windows × 12 features = 120 values, oldest first.
- **Preprocessing:** signed log1p only — `x = sign(v)·ln(1+|v|)` — **no scaler** (config-driven via `network_live.json`).
- **Batch-2 quirk:** the Hummingbird export squeezes a size-1 batch, so the phone feeds **2 identical rows and reads row 0** (verified identical to bulk inference, diff 0.000000).
- **Output:** probability (already after Sigmoid), column 1. **Alert threshold = 0.987** (≈1% FPR on benign Mischief windows).
- Alert → `E4 (+20)`, logs `network_ml_alert`, fires a notification (cooldown 120 s).

### 4.3 Numbers
- **Leave-one-RAT-out (fair): AUC 0.967, recall ~0.47** — the number to quote.
- In-sample AUC 0.958; ~0.1% alerts on benign background traffic.
- **Self-test on device:** replays 12 real Mischief sequences (6 benign, 6 RAT); shows matches, backend (NPU/CPU), latency.
- MLP (`network_live_mlp.onnx`, 40 KB, Linear/ReLU) is kept as a fallback if the GEMM model fails to load.

---

## 5. Accessibility behaviour model (signal E5)

- `AccessibilityFeatureExtractor` turns an enabled accessibility service's capabilities + context into a feature vector.
- `AccessibilityMlpClassifier` (ONNX) classifies behaviour; it **requires a `BENIGN` class** to load (so it can't silently flag everything). Non-benign class → **E5 (+20)**.
- Accessibility classes (model-dependent), e.g. `BENIGN / BlockAccess / ManipulateUI / ContentEavesdrop`.
- Not bundled by default; the loader only accepts a model that has the `BENIGN` class.

---

## 6. Layer 3 — Rules engine (`decision/`)

**`RiskEngine.evaluate(...)` returns `score`, `band`, fired signals, `action`.**
`Score = max(0, Σ fired signal weights + capped chain bonus)`.

### 6.1 Full signal catalogue (`SignalCatalogue.kt`)

| ID | Weight | Meaning |
|---|---|---|
| A1 | +15 | Enabled service that is **not** an accessibility tool |
| A2 | +10 | Can retrieve window content |
| A3 | +10 | Can perform gestures |
| A4 | +10 | Can filter key events |
| A5 | +5 | Listens to all packages |
| A6 | +15 | Accessibility enabled within **10 min** of install |
| B1 | +15 | Sideloaded (installer ≠ Play Store, not system) |
| B4 | +50 | Signing cert SHA-256 on local threat list |
| B5 | +15 | No launcher icon (hidden) |
| B6 | +20 | Name mimics a bank/government brand |
| C1 | +10 | `SYSTEM_ALERT_WINDOW` (overlay) requested |
| C5 | +5 | `QUERY_ALL_PACKAGES` |
| D1 | +10 | Wireless debugging on recently |
| D2 | +20 | New work profile appeared |
| E4 | +20 | **Live network model**: traffic resembles RAT C2 |
| E5 | +20 | **Accessibility model**: non-benign behaviour |
| S0_HIGH | +25 | Static model ≥ 0.5 |
| S0_MEDIUM | +10 | Static model ≥ 0.2 |
| HIGH_TAINT_MATCH | +50 | A planted decoy was caught leaving (packet blocked) |
| N1 | −30 | `isAccessibilityTool` + installed from Play |
| N3 | −40 | System pre-installed app |

### 6.2 Bands (`RiskEngine.bandFor`)
`score ≥ 90` **CRITICAL** · `≥ 60` **HIGH** · `≥ 30` **REVIEW** · else **SAFE**.

### 6.3 Trust policy (`TrustPolicy.kt`)
Trust **never rewrites the score** — the engine, list and details always show the same number. Trust only changes the **action**:

| Condition | Action |
|---|---|
| Trusted + hard evidence (`B4` or `HIGH_TAINT_MATCH`) | NOTIFY |
| Trusted (otherwise) | NONE |
| CRITICAL | CONTAIN (per-app pause) |
| HIGH / REVIEW | NOTIFY |
| else | NONE |

### 6.4 Brand heuristic (`BrandHeuristic.kt`)
`B6` ignores official bank/government packages, Play-installed apps and system apps; short brand tokens (e.g. "sbi") must be a **whole word** (so "Usb input" does not match); long names use fuzzy match.

---

## 7. Layer 4 — Causal chains (`CausalChains.kt`)

Sequences, not single events. The chain bonus is **capped at +30**. Device-wide events (ADB, bank foreground, work profile) can only complete a chain together with a step logged under the app itself.

| Chain | Sequence | Window | Bonus |
|---|---|---|---|
| CC-1 | Sideload → Accessibility enabled | 10 min | +15 |
| CC-2 | Accessibility enabled → DNS beacon from this app | 5 min | +15 |
| CC-3 | Bank app foreground → this app tries to send a decoy (blocked) | 60 s | +25 |
| CC-4 | ADB enabled → privilege grant to this app | 10 min | +15 |
| CC-5 | Work profile created → this app appears in a profile | 30 min | +20 |

---

## 8. Response & recovery

- **Per-app containment** (`GuardVpnService`): a VPN tunnel scoped with `addAllowedApplication` to *only* the paused app(s); its IPv4 + IPv6 are black-holed. If no package can be added, **no tunnel is created** (an empty allow-list would capture every app). A UID is shown "paused" only after the tunnel is actually established.
- **DNS beacon monitor** (when nothing is paused/watched): routes only the virtual resolver `10.0.0.53`, relays queries to the real resolver, and flags periodic beaconing (coefficient of variation < 0.15).
- **Decoys / canaries** (`CanaryManager`, opt-in): plant fake values; `CanaryMatcher` scans outbound packets; a hit is **blocked** and raises `HIGH_TAINT_MATCH` + CC-3.
- **Explanations** (`Explanations.kt`): deterministic, evidence-keyed text — one sentence per fired signal/chain — in **13 languages** (English + 12 Indic). **Not an LLM.**
- **Voice** (`MainActivity.speak`): Android's built-in **TextToSpeech** reads the explanation in the chosen language. No voice model, no audio files, works offline (needs the language's TTS voice installed).
- **Guided recovery** (`GuidedRecovery`): open app info, revoke accessibility, uninstall — step by step.

> **One VPN at a time:** Android allows a single VPN tunnel, so while an app is watched or paused, device-wide DNS beacon monitoring pauses. Documented, not hidden.

---

## 9. Data storage

### 9.1 SQLite via Room — `dhrashta-x.db` (`data/RoomDb.kt`, schema version 1, `exportSchema = true`)
Lives in the app's private sandbox: `/data/data/com.dhrashta.x/databases/dhrashta-x.db` (+ `-wal`/`-shm`). No other app can read it.

| Table | Columns | Purpose |
|---|---|---|
| `events` | id, timestamp, pkg, signalId, weight | Every signal/finding over time → causal chains + activity timeline |
| `connections` | id, timestamp, uid, remote, blocked | Observed/blocked connections per app |
| `risk_scores` | id, timestamp, pkg, score, band | Score + band history per app |

**Retention / pruning (`EventLogger`):** rows older than **7 days** are deleted, and each table is capped (events 20,000; connections 5,000; risk_scores 5,000); pruning runs every 500 writes. So the DB stays small and old data ages out.

**Access:** DAOs in `data/Daos.kt`; all reads/writes go through `EventLogger` on the IO dispatcher; the UI observes via Kotlin `Flow`.

### 9.2 Key-value (SharedPreferences XML, same private sandbox `/shared_prefs/`)
| Store | Holds |
|---|---|
| `static_scan` | S0 verdicts per app (with the APK's lastUpdateTime, so a reinstall re-scans) |
| `containment_state` | Paused UIDs, watched (inspected) UIDs, DNS-monitor flag |
| allow-list prefs | Trusted apps |
| canary prefs | Decoy on/off + planted contact id |
| UI prefs | Chosen language |

### 9.3 In-memory only (cleared on restart, by design)
`LiveNetworkStore` (latest live scores), `ModelStatusStore` (model/self-test status), `EvaluationStateStore` (current evaluation) — all `StateFlow`.

> **At rest:** relies on Android's per-app sandbox + OS full-disk encryption; SQLCipher (encrypted DB) is **not** added yet — can be, if wanted.

---

## 10. Concurrency, permissions, honesty

- **Threading:** the monitor service uses a supervised IO coroutine scope; each package is evaluated under its own `Mutex` so overlapping triggers cannot race; static scans run under a single lock to bound memory.
- **Models reported honestly:** every model shows on screen whether it ran on **NPU or CPU** and whether its **golden self-test passed**. Nothing claims "NPU" unless the strict accelerator session was created.
- **No network client:** DHRASHTA-X makes **no outbound requests of its own** (verified: no HTTP client, no analytics/Firebase, no hardcoded URL). Works in airplane mode; only the VPN handles *other* apps' traffic.
- **Permissions used:** `FOREGROUND_SERVICE(+SPECIAL_USE)`, `POST_NOTIFICATIONS`, `QUERY_ALL_PACKAGES`, `INTERNET`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED`, `WRITE_CONTACTS` (decoy), `PACKAGE_USAGE_STATS` (bank-foreground), plus `BIND_VPN_SERVICE` for the VPN.

---

## 11. Known limitations (our own threat model)

- A manifest-only static scan can miss runtime-loaded capabilities; a trusted-looking cert weakens the cert check; reflection can blunt permission inspection. **Answer:** the behavioral layers (live model, decoys, causal chains) catch the app when it *acts*, however it hid at install.
- Live network input exists only while a watched app is actually using the network (none in airplane mode).
- One VPN tunnel at a time (containment/watch vs. device-wide DNS monitor).
- **[Research, not shipped]:** a GRU on CIC-AndMal2017; an on-device LLM for explanations. Current explanations are templates + TTS.

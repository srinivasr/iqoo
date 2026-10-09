# DHRASHTA-X — Project Writeup (Evidence Edition)

*On-device defense against banking trojans, RATs and spyware for Android.*
*iQOO Hackathon 2026 — Grand Finale (Oct 9–11, Bengaluru).*

> **Honesty note:** every number below is what our build actually produces. Where something is research/experiment and **not** in the shipped APK, it is labeled **[Research]**. Where a number is not yet measured, it says **[TODO]**. We would rather show a smaller true number than a big unverifiable one.

---

## 0. Submission details (fill before submitting)

- **Team name:** __________
- **Members:** __________
- **GitHub repo:** __________
- **Demo video:** __________
- **Contact email:** __________

---

## 1. Why we chose this project

Android banking trojans and remote-access tools (RATs) cost Indians real money. They:

- Pose as a bank, a government app, or a "system update".
- Abuse the **Accessibility** permission to read the screen, tap and type for you, and approve transactions.
- Steal SMS OTPs.
- Quietly send data to the attacker's command-and-control (C2) server.

Google Play Protect is mostly **cloud + signature** based. It is weak on:

- **Sideloaded** apps (from WhatsApp, a link, a browser).
- **Brand-new** malware (no signature yet).
- **Behavior that appears only after install** (clean at scan time, bad later).

Cloud tools are also slow, need internet, and raise privacy concerns.

**Our idea:** a detector that runs **fully on the phone**, needs **no internet to decide**, watches **behavior** (not just signatures), and **explains itself in the user's language**.

---

## 2. Architecture (30-second view)

```
                 ┌───────────────────────── DHRASHTA-X (on device) ─────────────────────────┐
  App installed  │  LAYER 1  Static scan (S0)     reads the app's code+manifest on phone      │
   or running →  │           LightGBM 300 trees → ONNX (CPU)      → score                     │
                 │                                                                            │
  App behaves →  │  LAYER 2  Live network model   watches a suspicious app's real traffic     │
                 │           MLP 120→64→32→1 → ONNX (NPU-capable) → score every 10 s          │
                 │                                                                            │
                 │  LAYER 3  Rules engine         clear signals (A/B/C/D/E) → points          │
                 │  LAYER 4  Causal chains        sequences over time (CC-1 … CC-5) → bonus    │
                 │                                                                            │
                 │  Σ points → band: SAFE (0–29) · REVIEW (30–59) · HIGH (60–89) · CRITICAL(90+)│
                 │                                                                            │
  Response →     │  Per-app internet PAUSE (VPN) · DECOY/canary block · Explain (13 langs+TTS) │
                 └────────────────────────────────────────────────────────────────────────────┘
          No traffic content and no personal data leaves the phone to make a decision.
```

**Causal-chain timeline (example):**

```
 Sideload ──10 min──▶ Accessibility ON ──▶ Bank app opens ──▶ app reads decoy ──▶ beacon to unknown host
   (B1)                (A1..A5, CC-1)         (context)         (canary, CC-3)        (E4, CC-2)
```

---

## 3. What we built (and the evidence)

### Layer 1 — Static app scan (S0) — **shipped**
Reads an installed app's own bytecode + manifest **on the phone** and scores how much it looks like a banking-trojan / RAT family.

- **Model:** LightGBM, **300 trees**, **266 features** = 215 Drebin features + 51 modern features (accessibility abuse, SMS theft, overlays, NFC relay, fake-update lures, dropper patterns, etc.).
- **Runtime:** exported to ONNX (TreeEnsembleClassifier) and run with **ONNX Runtime on the CPU**. Tree ensembles are not NNAPI operators, so S0 honestly runs on CPU; the NPU path is Layer 2.
- **On-device feature extraction:** a Kotlin port of our Python training extractor (reads `classes*.dex` method tables + manifest via a small dex/APK parser). Verified identical to the Python extractor on real apps.
- **Model size:** 1.37 MB. **ONNX↔LightGBM parity:** max difference 9.6×10⁻⁸ (effectively exact).

**Numbers:**

| What | Dataset | Result |
|---|---|---|
| Classic static benchmark (215-feature sibling model) | Drebin-215 (15,036 apps) | AUC **0.9987**, F1 **0.987**, precision **0.989**, recall **0.984** |
| Modern malware check (shipped 266-feature model) | 42 real RAT/trojan APKs + 2 benign | score ≥0.5 → **16/42 (38%)** caught, **0** false positives; score ≥0.2 → **26/42 (62%)**, **0** FP |

> We report both honestly: old malware (Drebin) is easy and scores very high; **modern** RAT families are harder, which is exactly why Layers 2–4 exist. The 2-benign sample is too small to claim a real false-positive rate — we say so.

### Layer 2 — Live network model (behavior) — **shipped**
Watches a **suspicious** app's real traffic and decides if it looks like a RAT talking to C2.

- **Model:** MLP **120 → 64 → 32 → 1**, trained on **Android Mischief** (8 real RATs). Input = 10 windows × 12 features (10-second windows); preprocessing = signed-log1p + StandardScaler.
- **Runtime:** only Linear + ReLU, so it **can run on the NPU** (NNAPI). The app shows on screen whether it actually ran on NPU or fell back to CPU. **Size:** 40 KB (float) / 25 KB (quantized).
- **Live input without root:** a user-space TCP/UDP relay inside Android's VPN forwards a watched app's traffic for real while measuring packet size/direction/timing. Every 10 s we score the last 100 s. Only traffic *shape* is read, never content.

**Numbers:**

| Test | Result |
|---|---|
| Leave-one-RAT-out (fair test: hide a whole RAT family) | mean AUC **0.854**, recall **15%** at ~1% FPR (threshold 0.9457) |
| In-sample on the 8 Mischief captures | AUC **0.991**, **0.1%** alerts on normal background traffic |
| Relay correctness (vs real Linux TCP/IP via a TUN) | 5 MB download & 2 MB upload byte-identical; recovers from packet loss |
| Kotlin vs Python window features | identical over 590 windows (max diff 6×10⁻⁸) |

> The **held-out AUC 0.854 / 15% recall** is the number to quote. The 0.99 is in-sample and would be dishonest to present as accuracy.

### Layer 3 — Rules engine (deterministic) — **shipped**
Each warning sign adds points (actual weights from the build):

| Signal | Meaning | Points |
|---|---|---|
| A1 | Accessibility service that is not an accessibility tool | +15 |
| A2 / A3 / A4 | Can read window content / perform gestures / capture keys | +10 each |
| A5 | Listens to all apps | +5 |
| A6 | Accessibility enabled within 10 min of install | +15 |
| B1 | Sideloaded | +15 |
| B4 | Signing cert on local threat list | +50 |
| B5 | No launcher icon (hidden) | +15 |
| B6 | Name mimics a bank/government brand | +20 |
| C1 / C5 | Overlay permission / QUERY_ALL_PACKAGES | +10 / +5 |
| D1 / D2 | Wireless-debug recently / new work profile | +10 / +20 |
| E4 | Live network model: traffic looks like RAT C2 | +20 |
| E5 | Accessibility ML: non-benign behaviour | +20 |
| HIGH_TAINT_MATCH | A planted decoy was caught leaving (packet blocked) | +50 |
| S0_HIGH / S0_MEDIUM | Static model ≥0.5 / ≥0.2 | +25 / +10 |
| N1 / N3 | Play-installed a11y tool / system app | −30 / −40 |

Bands: **SAFE** 0–29 · **REVIEW** 30–59 · **HIGH** 60–89 · **CRITICAL** 90+.

### Layer 4 — Causal chains — **shipped**
Sequences, not single events. Bonus capped at **+30**.

| Chain | Sequence | Window |
|---|---|---|
| CC-1 | Sideload → Accessibility enabled | 10 min |
| CC-2 | Accessibility enabled → beacon to unknown host | 5 min |
| CC-3 | Bank app foreground → app tries to send a decoy | 60 s |
| CC-4 | ADB enabled → privilege grant to this app | 10 min |
| CC-5 | Work profile created → app appears in a profile | 30 min |

### Response & recovery — **shipped**
- **Per-app internet pause** via the VPN (contains just the risky app). Containment latency: **[TODO: measure on device]**.
- **Decoys/canaries:** plant fake values; block + flag any app that tries to exfiltrate them.
- **Explanations in 13 languages** (English + 12 Indic: Assamese, Bengali, Gujarati, Hindi, Kannada, Malayalam, Marathi, Odia, Punjabi, Tamil, Telugu, Urdu) with text-to-speech. *(Explanations are deterministic, evidence-keyed templates — not an LLM. The newest feature strings (S0, live network) are currently in English/Hindi/Bengali; other locales fall back to English for those specific strings.)*
- **Guided recovery:** walk the user through revoking access and uninstalling.

### Self-test (unique feature) — **shipped**
Each model ships **golden samples** and replays them on the phone, comparing against outputs recorded from ONNX Runtime on a PC:
- Static S0: replays 44 real extracted samples; shows match count + max difference.
- Live network: replays **12 real Mischief sequences** (6 benign, 6 RAT); shows match count, backend (NPU/CPU), and latency.
This lets a judge **see the model actually running correctly on the device**, live, instead of trusting a slide.

---

## 4. Demo scenario (with real score math)

Install **RiskSim** (our harmless test app) with monitoring on:

```
Sideloaded install                         B1  +15
Static scan: SMS-triad + overlay + install → S0 MEDIUM/HIGH  +10..+25
Enable RiskSim's accessibility service     A1 +15, A2 +10, A3 +10, A5 +5, A6 +15
   └─ chain CC-1 (sideload→a11y)           +15 (chain bonus, capped +30)
Show overlay bubble                        C1  +10
QUERY_ALL_PACKAGES                         C5  +5
Start beacon → live model flags it         E4  +20   └─ chain CC-2
Send decoy value → blocked                 HIGH_TAINT_MATCH +50  └─ chain CC-3
─────────────────────────────────────────────────────────────────
Running total ≈ 100–150  →  CRITICAL  →  internet paused automatically
```

*(Exact points depend on which signals fire; the accessibility + sideload + overlay signals alone already reach CRITICAL, and the live/decoy evidence confirms it.)*

---

## 5. Threat model — what we cover

| Attack vector | Covered? | By which layer |
|---|---|---|
| Sideloaded fake-bank trojan | ✅ | L1, L3 |
| Accessibility abuse | ✅ | L3 (A1–A6), L4 |
| Overlay attack | ✅ | L3 (C1) |
| SMS OTP theft (permission pattern) | ✅ | L1 static rules |
| C2 beaconing | ✅ | L2 live model, DNS beacon detector |
| Decoy/canary exfiltration | ✅ | Decoy block, L4 (CC-3) |
| Ransomware (file encryption) | ❌ | out of scope (no file-system monitor) |
| Root-level malware | ❌ | out of scope (assumes unrooted device) |
| Bluetooth / Wi-Fi-Direct relay | ❌ | out of scope (VPN sees IP traffic only) |

---

## 6. Known limitations (our own evasion analysis)

A static scan can be fooled: capabilities loaded at **runtime**, a freshly-signed **trusted-looking cert**, or permissions requested in unusual ways can weaken a manifest-only check.

**Our answer:** DHRASHTA-X never relies on the static scan alone. The **behavioral** layers (live network model, decoys, causal chains) catch the app when it *actually acts* — however it hid at install time. Hiding the permissions also hides them from the attacker's own static evasion payoff, but the moment the app beacons or exfiltrates, Layer 2 and the decoy catch it.
**Next steps:** flag runtime class-loading / reflection as its own suspicious signal; reduce trust in the cert allow-list.

---

## 7. Failure modes (robust engineering)

| If… | DHRASHTA-X does… |
|---|---|
| User denies the VPN permission | Static + rules + accessibility still work; containment is simply unavailable and the UI says so |
| Accessibility permission not granted to us | We still scan installs and watch traffic of watched apps; we cannot read a11y config of others |
| Phone reboots | Monitoring service and VPN restart on BOOT_COMPLETED; event history is kept (pruned to 7 days) |
| A model file is missing/corrupt | That layer reports "not loaded"; the others keep working; nothing is faked |
| NPU session fails | Falls back to CPU and labels it on screen |

---

## 8. How we compare (qualitative)

| Capability | DHRASHTA-X | Google Play Protect | Typical cloud mobile AV |
|---|---|---|---|
| Decision fully on-device | ✅ | partly (cloud) | ❌ (cloud) |
| Works offline | ✅ | limited | ❌ |
| Catches brand-new (no signature) | ✅ (behavior) | weak | partial |
| Watches live traffic behavior | ✅ | ❌ | some |
| Per-app containment by the tool | ✅ | ❌ | rare |
| Explains in Indic languages + voice | ✅ | ❌ | ❌ |
| Data leaves the phone | ❌ (none) | yes | yes |

*(We compare on architecture/capability, not on competitor accuracy numbers we cannot verify.)*

---

## 9. Datasets & sources

- **Drebin-215** — Android malware static features (Arp et al., NDSS 2014).
- **Android Mischief Dataset** — real RAT C2 captures (Stratosphere Lab, CTU).
- **CIC-AndMal2017** — Android malware traffic (Canadian Institute for Cybersecurity). **[Research]** used for an earlier GRU experiment; not shipped.
- **LAMDA** — **[Research / to confirm]** include only if we actually ran the concept-drift experiment and can show the numbers.

---

## 10. Privacy & India fit

- **DPDP Act alignment:** no traffic content or personal data leaves the device — detection is on-device by design (data minimisation).
- **Relevance:** targets UPI/banking-trojan fraud, which is a major Indian cybercrime category; explanations in Indic languages make warnings usable by non-technical users.
- **[Research / future]** optional reporting hooks (e.g., CERT-In / 1930 helpline) — **not built**; would be opt-in and user-initiated.

---

## 11. Performance **[TODO — measure on the demo phone, ~30 min]**

| Metric | Value |
|---|---|
| Battery/hour with VPN watching one app | [TODO] |
| Static scan time per app | [TODO] |
| Live inference time per window (NPU / CPU) | [TODO] |
| RAM usage | [TODO] |

*(Honest placeholder — fill with measured numbers before the final; do not guess.)*

---

## 12. Research & experiments (NOT in the shipped APK)

Kept separate so nothing here is mistaken for a shipped claim:

- **Network GRU** on CIC-AndMal2017 — an earlier model; **not in the build**. (Leave-one-RAT-out GRU on Mischief: AUC 0.869, recall 0.205 — comparable to the shipped MLP.)
- **Network LightGBM + Hummingbird GEMM (FP16)** — compiling a tree model to tensor ops to target the NPU. Explored; **not shipped**, because its 120-dim traffic scaler was lost and would need regeneration.
- **On-device LLM explanations** — **not built**; current explanations are deterministic templates + TTS.

---

## 13. 48-hour hackathon plan

**Day 0 — Evening (setup):** build runs on emulator + one phone; RiskSim + 3–4 benign apps ready; freeze the numbers you'll quote (held-out). Install RiskSim by turning off Play Protect on the test device.

**Day 1 — Morning (core):** all four layers load, **self-tests pass** on the demo device; static scan flags RiskSim, clears benign apps; clean logcat ready.

**Day 1 — Afternoon (behavior):** live traffic demo (score rises as RiskSim beacons); per-app pause; decoy block; tune thresholds so benign apps stay quiet.

**Day 1 — Evening (polish + numbers):** fill the **Performance** table (measure battery/latency/RAM); check Hindi/Bengali + TTS; record a **backup screen video** of the full flow.

**Day 2 — Morning (story):** finalize architecture diagram + metrics table + threat model + comparison; rehearse end-to-end **twice, timed**; prep answers (accuracy, privacy, NPU, false positives).

**Day 2 — Afternoon (demo):** device check (battery, network, emulator warm, app installed); keep backup video + one-page cheat-sheet; present **problem → live demo → honest numbers → limitations → future work**.

**Tips:** rehearse the live demo until it's boring; lead with honesty — judges trust "here's what it catches, here's what it misses, here's why our design still wins."

---

## 14. One-line summary

**DHRASHTA-X: a fully on-device, privacy-preserving, explainable Android defense that combines static code analysis, live-traffic behavioral ML (NPU-capable), deterministic rules, and time-based causal chains to catch banking trojans and RATs — and contain them per-app — without sending any data to the cloud.**

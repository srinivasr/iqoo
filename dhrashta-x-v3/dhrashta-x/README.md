# DHRASHTA-X
### On-Device Android Threat Detection with Network + Permission Fusion

> A passive, offline, rootless Android security agent that detects modern
> mobile threats by fusing network behaviour with device permission state.

Built for iQOO City Battles 2026 — Track 6 (Digital Security).

---

## 1. What DHRASHTA-X Is

DHRASHTA-X is an Android application that runs entirely on the phone. It:

1. Intercepts all outbound app traffic using Android's `VpnService` API
   — no root, no special firmware, works on any phone running Android 5+
2. Extracts 81 network behavioural features per 5-second window
3. Reads on-device permission and configuration state
   (accessibility services, notification listeners, overlay grants,
   unknown-sources, recent sideloads, device admins)
4. Runs a small quantized model that fuses both signals into a single
   threat verdict
5. Shows the user a plain-language alert with the specific evidence that
   drove the decision

The model runs on the phone's NPU. The evidence is readable. Nothing leaves
the device.

## 2. The Problem

Android security in 2026 is failing in three specific ways.

**First, threats have moved to the network layer.** The dominant Android
malware families — banking trojans, spyware, stalkerware — do not crash
your phone or show ads on your home screen. They connect to a remote
server on a schedule, upload stolen data, and stay quiet. The wire is where
they live.

**Second, existing defenses are signature-based.** They block known-bad
domains and known-bad APK hashes. A new banking trojan variant with a fresh
domain and a freshly-obfuscated APK bypasses all of them.

**Third, existing research looks at one signal at a time.** Academic Android
IDS papers analyse network flow OR permissions, never both. Real malware
requires the fusion to catch reliably.

The numbers are not ambiguous:

- Banking trojans: **52.96% of all mobile malware** in Q1 2026, and mobile
  banking attacks grew **56% YoY** in 2025
- Spyware detections: **+220% YoY**
- Stalkerware: continues to evade mainstream antivirus with **poor detection
  rates across the top 20 commercial products**
- Adware: **69% of total mobile threat detections** in one recent quarter,
  42M+ malicious downloads

None of the top-20 commercial Android security products combine passive
network behaviour with on-device permission context. That gap is what
DHRASHTA-X fills.

## 3. The Differentiator (USP)

> **DHRASHTA-X is the first consumer Android threat detector that fuses
> passive network behaviour with on-device permission state, on the phone,
> with zero cloud dependency.**

Every existing consumer product falls into one of three buckets:

| Category | Examples | What they do | What they miss |
|---|---|---|---|
| Firewall / blocklist | Firewall Security AI | DNS + IP blocklist | Unknown threats, no behaviour |
| Interactive scanner | Common Play Store AV | Scan on tap | Not continuous |
| Research prototypes | TSANet, academic IDS | Network-only ML | No permission fusion |

None of them do permission fusion. None of them do SHAP-style evidence.

DHRASHTA-X is not "another antivirus app". It is a behavioural sensor that
explains itself.

## 4. Architecture
mkdir -p ~/dhrashta-x
cat > ~/dhrashta-x/README.md << 'DHRASHTAX_EOF'
# DHRASHTA-X
### On-Device Android Threat Detection with Network + Permission Fusion

> A passive, offline, rootless Android security agent that detects modern
> mobile threats by fusing network behaviour with device permission state.

Built for iQOO City Battles 2026 — Track 6 (Digital Security).

---

## 1. What DHRASHTA-X Is

DHRASHTA-X is an Android application that runs entirely on the phone. It:

1. Intercepts all outbound app traffic using Android's `VpnService` API
   — no root, no special firmware, works on any phone running Android 5+
2. Extracts 81 network behavioural features per 5-second window
3. Reads on-device permission and configuration state
   (accessibility services, notification listeners, overlay grants,
   unknown-sources, recent sideloads, device admins)
4. Runs a small quantized model that fuses both signals into a single
   threat verdict
5. Shows the user a plain-language alert with the specific evidence that
   drove the decision

The model runs on the phone's NPU. The evidence is readable. Nothing leaves
the device.

## 2. The Problem

Android security in 2026 is failing in three specific ways.

**First, threats have moved to the network layer.** The dominant Android
malware families — banking trojans, spyware, stalkerware — do not crash
your phone or show ads on your home screen. They connect to a remote
server on a schedule, upload stolen data, and stay quiet. The wire is where
they live.

**Second, existing defenses are signature-based.** They block known-bad
domains and known-bad APK hashes. A new banking trojan variant with a fresh
domain and a freshly-obfuscated APK bypasses all of them.

**Third, existing research looks at one signal at a time.** Academic Android
IDS papers analyse network flow OR permissions, never both. Real malware
requires the fusion to catch reliably.

The numbers are not ambiguous:

- Banking trojans: **52.96% of all mobile malware** in Q1 2026, and mobile
  banking attacks grew **56% YoY** in 2025
- Spyware detections: **+220% YoY**
- Stalkerware: continues to evade mainstream antivirus with **poor detection
  rates across the top 20 commercial products**
- Adware: **69% of total mobile threat detections** in one recent quarter,
  42M+ malicious downloads

None of the top-20 commercial Android security products combine passive
network behaviour with on-device permission context. That gap is what
DHRASHTA-X fills.

## 3. The Differentiator (USP)

> **DHRASHTA-X is the first consumer Android threat detector that fuses
> passive network behaviour with on-device permission state, on the phone,
> with zero cloud dependency.**

Every existing consumer product falls into one of three buckets:

| Category | Examples | What they do | What they miss |
|---|---|---|---|
| Firewall / blocklist | Firewall Security AI | DNS + IP blocklist | Unknown threats, no behaviour |
| Interactive scanner | Common Play Store AV | Scan on tap | Not continuous |
| Research prototypes | TSANet, academic IDS | Network-only ML | No permission fusion |

None of them do permission fusion. None of them do SHAP-style evidence.

DHRASHTA-X is not "another antivirus app". It is a behavioural sensor that
explains itself.

## 4. Architecture
┌───────────────────────────────────────────────────────────────┐
│ ANDROID PHONE │
│ │
│ ┌─────────────────┐ ┌─────────────────────────┐ │
│ │ All Apps │ │ Android System │ │
│ │ (banking, chat,│ │ - Accessibility svcs │ │
│ │ browser, ...) │ │ - Notification listeners│ │
│ └────────┬────────┘ │ - Overlay grants │ │
│ │ │ - Unknown sources │ │
│ │ outbound │ - Recent sideloads │ │
│ ▼ │ - Device admins │ │
│ ┌─────────────────┐ └────────────┬────────────┘ │
│ │ VpnService │ │ │
│ │ (no root) │ │ │
│ └────────┬────────┘ │ │
│ │ │ │
│ ▼ ▼ │
│ ┌─────────────────┐ ┌─────────────────────────┐ │
│ │ Packet Parser │ │ Context Collector │ │
│ │ TCP/UDP/ICMP │ │ (reads system state) │ │
│ └────────┬────────┘ └────────────┬────────────┘ │
│ │ │ │
│ ▼ │ │
│ ┌─────────────────┐ │ │
│ │ Flow Aggregator │ │ │
│ │ 5-second window │ │ │
│ └────────┬────────┘ │ │
│ │ │ │
│ ▼ │ │
│ ┌─────────────────┐ │ │
│ │ Feature Engine │ │ │
│ │ 81 features │ │ │
│ └────────┬────────┘ │ │
│ │ │ │
│ ▼ │ │
│ ┌─────────────────────────────────────────────────┐ │
│ │ ONNX Runtime Mobile + NNAPI NPU delegate │ │
│ │ ┌──────────────┐ ┌──────────────────┐ │ │
│ │ │ XGBoost 13- │ │ Context-aware │ │ │
│ │ │ class model │──────▶│ fusion layer │ │ │
│ │ └──────────────┘ └────────┬─────────┘ │ │
│ └────────────────────────────────────┼────────────┘ │
│ │ │
│ ▼ │
│ ┌─────────────────┐ ┌─────────────────────────┐ │
│ │ Room DB │◀────────│ Alert Engine │ │
│ │ (local) │ │ confidence + evidence │ │
│ └────────┬────────┘ └────────────┬────────────┘ │
│ │ │ │
│ ▼ ▼ │
│ ┌───────────────────────────────────────────────────┐ │
│ │ Compose UI │ │
│ │ - Alert list │ │
│ │ - Evidence panel (top SHAP features) │ │
│ │ - Voice output via TextToSpeech │ │
│ └───────────────────────────────────────────────────┘ │
│ │
└───────────────────────────────────────────────────────────────┘

clear

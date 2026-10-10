# Privacy Policy for DHRASHTA-X

**Last updated:** October 10, 2026

DHRASHTA-X (`com.dhrashta.x`) is an autonomous on-device threat detection and endpoint response utility designed to combat malicious applications that exploit Android Accessibility Services.

Your privacy is the core design foundation of DHRASHTA-X. Our architecture is strictly zero-cloud and zero-telemetry.

---

### 1. Zero Cloud Telemetry Guarantee
- **No Data Leaves Your Device**: All behavioral telemetry, security scoring, machine learning inference (neural networks and local LLM), and packet inspection are executed 100% on-device.
- **No External Servers**: DHRASHTA-X does not operate any analytics, crash reporting, tracking, or user telemetry servers. No user identifiers, app lists, or network packets are ever transmitted across the internet to the developers or third parties.

---

### 2. Device Capabilities & Permissions

DHRASHTA-X requests permissions strictly necessary to deliver on-device defense:

1. **`android.permission.QUERY_ALL_PACKAGES`**:
   - **Purpose**: Enables local scanning of installed application packages, certificate signatures, and manifest permissions to identify malicious trojans and sideloaded impostor apps.
   - **Handling**: Package data is processed entirely in local memory and cached in local Room SQLite storage on your device.

2. **`android.permission.PACKAGE_USAGE_STATS` (Usage Access)**:
   - **Purpose**: Monitors foreground transitions of designated banking and payment apps to prevent real-time screen overlays and accessibility eavesdropping.
   - **Handling**: Usage events are evaluated locally in memory. No browsing history, app usage durations, or personal activity logs are recorded or transmitted.

3. **`android.permission.BIND_VPN_SERVICE` (Local Firewall / Containment)**:
   - **Purpose**: Establishes a local, on-device loopback tunnel to detect periodic command-and-control DNS beaconing and quarantine identified malicious applications by dropping their outbound traffic.
   - **Handling**: Operates strictly on-device without routing traffic through any remote VPN servers or proxies.

4. **`android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`**:
   - **Purpose**: Runs a lightweight continuous foreground service with a persistent notification to monitor `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` for zero-day accessibility abuse.

---

### 3. Data Storage & Retention
- All audit event history, risk scores, and user allowlists are stored in your device's private SQLite database (`com.dhrashta.x`).
- When DHRASHTA-X is uninstalled, all local data is automatically purged by the Android OS.

---

### 4. Children's Privacy
DHRASHTA-X does not collect personal information from any user, including children under the age of 13.

---

### 5. Policy Updates
Any updates to this policy will be reflected in this document and within the application's Settings screen.

---

### 6. Contact & Inquiries
For questions or inquiries regarding this Privacy Policy or on-device security controls, please contact:
- Email: privacy@dhrashta.com

#!/usr/bin/env bash
# ==============================================================================
# DHRASHTA-X Benign Threat Probe & Behavioral Verification Harness
# Exercises EDR detection and containment without deploying functional exploits.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/android-env.sh"

echo "=== DHRASHTA-X Detection & Containment Verification Harness ==="

MODE="${1:-all}"

GRADLE_BIN="$SCRIPT_DIR/../gradlew"
if [ ! -x "$GRADLE_BIN" ] && [ -f "$SCRIPT_DIR/../gradlew.bat" ]; then
  GRADLE_BIN="$SCRIPT_DIR/../gradlew.bat"
fi

case "$MODE" in
  test|unit)
    echo "[1/2] Running JVM Adversarial Behavioral Simulation Suite..."
    "$GRADLE_BIN" testDebugUnitTest --tests "com.dhrashta.x.decision.AdversarialProbeSimulationTest"
    echo "✓ Behavioral profiles (TeaBot, Canary Exfiltration, DNS Beacon, Benign Control) passed."
    ;;

  live|adb)
    echo "[2/2] Running Live ADB EDR Probe..."
    if ! command -v adb >/dev/null 2>&1; then
      echo "Error: 'adb' not found on PATH."
      exit 1
    fi

    DEVICE=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [ -z "$DEVICE" ]; then
      echo "Error: No authorized Android device/emulator found via adb."
      exit 1
    fi
    echo "Connected device: $DEVICE"

    echo "--- Simulating Posture Change: Wireless Debugging (Signal D1) ---"
    adb -s "$DEVICE" shell settings put global adb_wifi_enabled 1

    echo "--- Triggering DHRASHTA-X Engine Scan ---"
    adb -s "$DEVICE" shell am start -n com.dhrashta.x/.ui.MainActivity >/dev/null 2>&1 || true

    echo "--- Emitting Benign Canary Probe Packet over UDP (DHRX-* token) ---"
    # Sends a benign test packet containing the DHRASHTA canary marker to port 53 (timeout prevents toybox nc hanging)
    adb -s "$DEVICE" shell "echo 'DHRX-TEST-CANARY-TOKEN-7712' | timeout 2 nc -u -w1 -W1 10.0.0.53 53 2>/dev/null || true"

    echo "--- Checking DHRASHTA-X Logcat Output for Detections ---"
    adb -s "$DEVICE" logcat -d -s DhrashtaMonitor DhrashtaService DhrashtaVpn DhrashtaCanary DhrashtaOnnx | tail -n 25 || true

    echo "✓ Live probe stimulation complete."
    ;;

  all)
    echo "[1/2] Executing Adversarial Behavioral Simulation Suite..."
    "$GRADLE_BIN" testDebugUnitTest --tests "com.dhrashta.x.decision.AdversarialProbeSimulationTest"
    echo "✓ Behavioral profile simulation tests verified."
    echo ""
    echo "[2/2] To run live probe stimulation on a connected device/emulator:"
    echo "      ./scripts/test-probe.sh live"
    ;;

  *)
    echo "Usage: $0 [test|live|all]"
    exit 1
    ;;
esac

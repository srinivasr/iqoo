#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/android-env.sh" ]; then
  # shellcheck source=/dev/null
  source "$SCRIPT_DIR/android-env.sh"
fi

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ ! -w "$REPO_ROOT" ]; then
  REPO_ROOT="$(pwd)"
fi
CAPTURES_DIR="$REPO_ROOT/captures"
mkdir -p "$CAPTURES_DIR"

DURATION_MINUTES="${1:-30}"
if ! [[ "$DURATION_MINUTES" =~ ^[0-9]+$ ]] || [ "$DURATION_MINUTES" -le 0 ]; then
  echo "Usage: $0 [duration_in_minutes] (default: 30)"
  exit 1
fi
DURATION_SECS=$((DURATION_MINUTES * 60))

echo "=== PCAPdroid 30-Minute Traffic Capture Session ==="
echo "Target Duration: $DURATION_MINUTES minutes ($DURATION_SECS seconds)"

# 1. Verify ADB is available
if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' command not found."
  echo "Install via your package manager (e.g., apt install android-tools-adb, brew install android-platform-tools, pacman -S android-tools, or nix develop), or run: source scripts/android-env.sh"
  exit 1
fi

# 2. Check for connected Android device
adb start-server >/dev/null 2>&1 || true
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo ""
  echo "ERROR: No authorized Android device detected."
  echo "Ensure phone is plugged in with USB debugging enabled."
  exit 1
fi

DEVICE_ID=$(echo "$DEVICES" | head -n 1)
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || echo "Android Device")
echo "Connected Device: $DEVICE_MODEL ($DEVICE_ID)"

# 3. Check if PCAPdroid is installed
PKG_CHECK=$(adb -s "$DEVICE_ID" shell pm list packages com.emanuelef.remote_capture 2>/dev/null | tr -d '\r\n' || true)
if [ -z "$PKG_CHECK" ]; then
  echo "PCAPdroid is not installed on $DEVICE_MODEL."
  echo "Running installer first..."
  "$SCRIPT_DIR/pcapdroid-install.sh"
fi

# 4. Bring up PCAPdroid app
echo ""
echo "Launching PCAPdroid on phone..."
adb -s "$DEVICE_ID" shell am start -n com.emanuelef.remote_capture/.activities.MainActivity >/dev/null 2>&1 || true

cat <<'EOF'
--------------------------------------------------------------------------------
PCAPdroid Device Configuration Checklist:
  1. Dump Mode: Ensure "Dump mode" is set to "PCAP file".
  2. Target Apps: Set to "All apps" (or keep default to capture all apps).
  3. Start: Tap the "Ready" / "Start" (Play) button on PCAPdroid.
  4. Android VPN Prompt: Tap "OK" on the "Connection request" system dialog.
--------------------------------------------------------------------------------
EOF

# Attempt programmatic start broadcast as well (works if Control API is enabled)
adb -s "$DEVICE_ID" shell am broadcast -a com.emanuelef.pcapdroid.action.START_CAPTURE >/dev/null 2>&1 || true
adb -s "$DEVICE_ID" shell am start -n com.emanuelef.remote_capture/.activities.CaptureCtrl -e action start -e pcap_dump_mode pcap_file >/dev/null 2>&1 || true

echo ""
echo "Recording started! Open and use your target apps during this window:"
echo "  - Google Play Store"
echo "  - WhatsApp / Telegram"
echo "  - Instagram / Snapchat"
echo "  - Google Chrome / Web browser"
echo "  - Banking / UPI apps"
echo "  - YouTube / Media streaming"
echo "  - General daily apps"
echo ""
echo "Press Ctrl+C at any time to stop capture early and pull the PCAP file."
echo ""

START_TS=$(date +%s)
STOP_EARLY=0

cleanup_and_stop() {
  STOP_EARLY=1
  echo -e "\nCapture interrupted or finished. Finalizing..."
}
trap cleanup_and_stop INT

# 5. Countdown loop
while true; do
  CURRENT_TS=$(date +%s)
  ELAPSED=$((CURRENT_TS - START_TS))
  if [ "$ELAPSED" -ge "$DURATION_SECS" ] || [ "$STOP_EARLY" -eq 1 ]; then
    break
  fi
  REMAINING=$((DURATION_SECS - ELAPSED))

  printf "\r[%02d:%02d:%02d / %02d:%02d:%02d] Recording in progress... (Ctrl+C to finish)" \
    $((ELAPSED / 3600)) $(((ELAPSED % 3600) / 60)) $((ELAPSED % 60)) \
    $((DURATION_SECS / 3600)) $(((DURATION_SECS % 3600) / 60)) $((DURATION_SECS % 60))
  sleep 1
done
echo ""

# 6. Stop capture on device
echo "Stopping capture session..."
adb -s "$DEVICE_ID" shell am broadcast -a com.emanuelef.pcapdroid.action.STOP_CAPTURE >/dev/null 2>&1 || true
adb -s "$DEVICE_ID" shell am start -n com.emanuelef.remote_capture/.activities.CaptureCtrl -e action stop >/dev/null 2>&1 || true

echo "If PCAPdroid is still actively capturing on your phone, tap 'Stop' in the app or notification."
sleep 2

# 7. Locate PCAP file on device
echo "Locating recorded PCAP file on device..."
SEARCH_PATHS=(
  "/sdcard/Download/PCAPdroid"
  "/sdcard/Download"
  "/sdcard/Android/data/com.emanuelef.remote_capture/files"
)

LATEST_REMOTE_PCAP=""
for P in "${SEARCH_PATHS[@]}"; do
  CANDIDATE=$(adb -s "$DEVICE_ID" shell "ls -t $P/*.pcap 2>/dev/null | head -n 1" | tr -d '\r\n' || true)
  if [ -n "$CANDIDATE" ] && [[ "$CANDIDATE" == *".pcap"* ]]; then
    LATEST_REMOTE_PCAP="$CANDIDATE"
    break
  fi
done

if [ -z "$LATEST_REMOTE_PCAP" ]; then
  echo "Warning: Could not automatically locate a .pcap file in default directories."
  echo "Checking all .pcap files on device /sdcard/..."
  ALL_PCAPS=$(adb -s "$DEVICE_ID" shell "find /sdcard/ -maxdepth 4 -name '*.pcap' -type f 2>/dev/null" | tr -d '\r' || true)
  if [ -n "$ALL_PCAPS" ]; then
    LATEST_REMOTE_PCAP=$(echo "$ALL_PCAPS" | head -n 1)
  fi
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOCAL_PCAP="$CAPTURES_DIR/traffic_${TIMESTAMP}.pcap"

if [ -n "$LATEST_REMOTE_PCAP" ]; then
  echo "Found remote PCAP: $LATEST_REMOTE_PCAP"
  echo "Pulling file to $LOCAL_PCAP..."
  adb -s "$DEVICE_ID" pull "$LATEST_REMOTE_PCAP" "$LOCAL_PCAP"
  echo "Saved capture to: $LOCAL_PCAP"
else
  echo ""
  echo "Notice: No .pcap file was found on the device."
  echo "Please check PCAPdroid on the phone: in 'Dump mode', did you select 'PCAP file' before starting?"
  echo "You can pull it manually once saved with:"
  echo "  adb pull /sdcard/Download/PCAPdroid/<filename>.pcap $CAPTURES_DIR/"
  exit 0
fi

# 8. PCAP Verification & Analysis
if [ -f "$LOCAL_PCAP" ] && [ -s "$LOCAL_PCAP" ]; then
  echo ""
  echo "=== Capture Verification ==="
  if command -v capinfos >/dev/null 2>&1; then
    capinfos -c -d -u -y -s -z "$LOCAL_PCAP"
  else
    ls -lh "$LOCAL_PCAP"
  fi

  if command -v tshark >/dev/null 2>&1; then
    echo ""
    echo "=== Protocol Hierarchy Summary ==="
    tshark -r "$LOCAL_PCAP" -q -z io,phs 2>/dev/null || true
  fi
  echo ""
  echo "Capture successfully retrieved and verified at: $LOCAL_PCAP"
fi

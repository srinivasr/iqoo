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
CACHE_DIR="$REPO_ROOT/.tooling/cache"
mkdir -p "$CACHE_DIR"

echo "=== PCAPdroid Automated Installer ==="

# 1. Verify ADB is available
if ! command -v adb >/dev/null 2>&1; then
  echo "Error: 'adb' command not found."
  echo "Install via your package manager (e.g., apt install android-tools-adb, brew install android-platform-tools, pacman -S android-tools, or nix develop), or run: source scripts/android-env.sh"
  exit 1
fi

# 2. Check for connected Android device
echo "Checking connected Android devices via ADB..."
adb start-server >/dev/null 2>&1 || true

DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [ -z "$DEVICES" ]; then
  echo ""
  echo "ERROR: No authorized Android device detected."
  echo "Setup Checklist:"
  echo "  1. Connect your phone via USB cable to this PC."
  echo "  2. Enable Developer Options: Settings -> About Phone -> Tap 'Build Number' 7 times."
  echo "  3. Enable USB Debugging: Settings -> System -> Developer Options -> USB Debugging."
  echo "  4. Unlock phone screen and accept the 'Allow USB debugging?' RSA prompt on phone."
  echo "  5. Re-run this script once 'adb devices' shows your device attached."
  exit 1
fi

DEVICE_ID=$(echo "$DEVICES" | head -n 1)
DEVICE_MODEL=$(adb -s "$DEVICE_ID" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || echo "Unknown")
echo "Target Device: $DEVICE_MODEL ($DEVICE_ID)"

# 3. Resolve latest PCAPdroid APK URL from GitHub Releases
echo "Checking latest PCAPdroid release on GitHub..."
LATEST_URL=""
if command -v curl >/dev/null 2>&1; then
  API_RESP=$(curl -sL --max-time 10 https://api.github.com/repos/emanuele-f/PCAPdroid/releases/latest || true)
  if [ -n "$API_RESP" ]; then
    LATEST_URL=$(echo "$API_RESP" | grep -o 'https://github.com/emanuele-f/PCAPdroid/releases/download/[^"]*PCAPdroid[^"]*\.apk' | head -n 1 || true)
  fi
fi

if [ -z "$LATEST_URL" ]; then
  LATEST_URL="https://github.com/emanuele-f/PCAPdroid/releases/download/v2.0.2/PCAPdroid_v2.0.2.apk"
fi

APK_NAME=$(basename "$LATEST_URL")
APK_PATH="$CACHE_DIR/$APK_NAME"

if [ ! -f "$APK_PATH" ] || [ ! -s "$APK_PATH" ]; then
  echo "Downloading PCAPdroid APK ($APK_NAME)..."
  curl -fL --progress-bar -o "$APK_PATH" "$LATEST_URL"
else
  echo "Found cached APK: $APK_PATH"
fi

# 4. Install onto device
echo "Installing PCAPdroid onto device ($DEVICE_ID)..."
adb -s "$DEVICE_ID" install -r -g "$APK_PATH"

echo ""
echo "Installation complete!"
echo "Package name: com.emanuelef.remote_capture"
echo ""
echo "Next step: Launch PCAPdroid and accept the one-time Android VPN permission prompt."
echo "You can launch the capture session with: ./scripts/pcapdroid-record.sh 30"

#!/usr/bin/env bash
set -e

# Export Android SDK environment
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

PKG="com.dhrashta.x"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Ensuring laptop Android emulator is running..."
if ! adb devices | grep -q "emulator-"; then
    AVD_NAME=$(emulator -list-avds 2>/dev/null | head -n 1)
    if [ -n "$AVD_NAME" ]; then
        echo "==> Booting emulator window '$AVD_NAME' on your laptop desktop..."
        emulator -avd "$AVD_NAME" -no-snapshot-load >/tmp/emulator.log 2>&1 &
        echo "==> Waiting for laptop emulator to boot..."
        adb wait-for-device
        while [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
            sleep 2
        done
        echo "==> Laptop emulator is ready!"
    else
        echo "Error: No emulator AVD found."
        exit 1
    fi
fi

# Build if APK does not exist or if --build flag passed
if [ ! -f "$APK" ] || [ "$1" == "--build" ] || [ "$1" == "-b" ]; then
    echo "==> Building debug APK with Gradle..."
    ./gradlew assembleDebug
fi

echo "==> Installing to laptop emulator..."
adb -e install -r -g "$APK"

echo "==> Granting permissions (Usage Stats)..."
adb -e shell appops set "$PKG" GET_USAGE_STATS allow 2>/dev/null || true

echo "==> Launching DHRASHTA-X on laptop screen..."
adb -e shell am start -n "$PKG/.ui.MainActivity"

echo "==> App is running on your laptop emulator window!"


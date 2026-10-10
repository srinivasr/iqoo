#!/usr/bin/env bash
set -e

# Export Android SDK environment
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

# NixOS compatibility: inject libraries for QEMU emulator and Qt GUI
NIX_LIB_PATHS="/nix/store/4h7nc1rna8gwqg74r4lhrhy113izfl79-libSM-1.2.6/lib:/nix/store/3qxw7gkl0n76xjlxlz5rgvdsdj5wadkz-libice-1.1.2/lib:/nix/store/9n9n1hcm6bbksyya7dyxpxm8kda1xqsh-libxcb-cursor-0.1.6/lib:/nix/store/12j6lw8is3y38kh8mpcypp9sclshi2gm-libpng-apng-1.6.58/lib:/nix/store/gax6r1j1i28bvxjcs1v98npzaa4hwj7d-libbsd-0.12.2/lib"
export LD_LIBRARY_PATH="${NIX_LIB_PATHS}${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export QT_QPA_PLATFORM="xcb"

PKG="com.dhrashta.x"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "==> Ensuring laptop Android emulator is running..."
EMU_PID=""
if ! adb devices | grep -Eq '^emulator-[0-9]+[[:space:]]+device$'; then
    AVD_NAME=$(emulator -list-avds 2>/dev/null | head -n 1)
    if [ -n "$AVD_NAME" ]; then
        echo "==> Booting emulator window '$AVD_NAME' on your laptop desktop..."
        emulator -avd "$AVD_NAME" -no-snapshot-load -gpu software >/tmp/emulator.log 2>&1 &
        EMU_PID=$!
        echo "==> Waiting for laptop emulator to boot..."
        BOOTED=""
        for _ in {1..90}; do
            if ! kill -0 "$EMU_PID" 2>/dev/null; then
                echo "Error: Emulator closed during startup. Check /tmp/emulator.log."
                exit 1
            fi
            if [ "$(adb -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]; then
                BOOTED=1
                break
            fi
            sleep 2
        done
        if [ -z "$BOOTED" ]; then
            echo "Error: Emulator did not finish booting. Check /tmp/emulator.log."
            exit 1
        fi
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

# Keep a window started by this script alive until the user closes it.
if [ -n "$EMU_PID" ]; then
    echo "==> Keep this terminal open while using the emulator; close its window to exit."
    wait "$EMU_PID"
fi

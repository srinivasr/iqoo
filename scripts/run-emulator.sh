#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$SCRIPT_DIR/android-env.sh"

AVD_NAME="${1:-Pixel_10a}"
shift || true

# On NixOS, the Android SDK emulator binaries require an FHS environment for dynamic linking.
# On standard Linux distros (Ubuntu, Debian, Fedora, Arch), macOS, and Windows, execute emulator directly.
if [ -f /etc/NIXOS ] && command -v nix-build >/dev/null 2>&1; then
  FHS_BIN=$(nix-build -E '
  with import <nixpkgs> {};
  buildFHSEnv {
    name = "android-emulator-fhs";
    targetPkgs = pkgs: with pkgs; [
      libpng nss nspr glibc libGL vulkan-loader zlib alsa-lib fontconfig freetype
      libxkbcommon libx11 libxext libxdamage libxfixes libxcursor libxrandr libxi
      libxrender libxtst pulseaudio dbus systemd expat libdrm libxcb libxkbfile
      util-linux libbsd libSM libICE libxcb-cursor libxcb-image
      libxcb-keysyms libxcb-render-util libxcb-wm
    ];
    runScript = "bash";
  }
  ' --no-out-link)/bin/android-emulator-fhs

  exec "$FHS_BIN" -c "${ANDROID_HOME:-}/emulator/emulator -avd '$AVD_NAME' $*"
else
  EMU_BIN=""
  if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/emulator/emulator" ]; then
    EMU_BIN="$ANDROID_HOME/emulator/emulator"
  elif command -v emulator >/dev/null 2>&1; then
    EMU_BIN="$(command -v emulator)"
  fi

  if [ -z "$EMU_BIN" ]; then
    echo "Error: Android emulator binary not found in ANDROID_HOME or PATH." >&2
    echo "Ensure the Android SDK emulator is installed or run: source scripts/android-env.sh" >&2
    exit 1
  fi

  exec "$EMU_BIN" -avd "$AVD_NAME" "$@"
fi

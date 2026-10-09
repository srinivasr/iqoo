#!/usr/bin/env sh
# Cross-platform environment loader for command-line builds and tooling.
# Detects JDK and Android SDK across Linux distros (Ubuntu, Fedora, Arch, NixOS),
# macOS, and Windows (Git Bash/WSL/MSYS).

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]:-$0}")" && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

# 1. Resolve JAVA_HOME
if [ -z "${JAVA_HOME:-}" ] && [ -d "$PROJECT_DIR/.tooling/jdk-17" ]; then
  export JAVA_HOME="$PROJECT_DIR/.tooling/jdk-17"
fi
if [ -z "${JAVA_HOME:-}" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  # macOS Java discovery
  MACOS_JAVA=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null || true)
  [ -n "$MACOS_JAVA" ] && export JAVA_HOME="$MACOS_JAVA"
fi
if [ -z "${JAVA_HOME:-}" ] && [ -d "/usr/lib/jvm/java-17-openjdk" ]; then
  export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
fi

# 2. Resolve ANDROID_HOME / ANDROID_SDK_ROOT
if [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$PROJECT_DIR/.tooling/android-sdk" ]; then
  export ANDROID_HOME="$PROJECT_DIR/.tooling/android-sdk"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -f "$PROJECT_DIR/local.properties" ]; then
  # Parse sdk.dir from local.properties if configured
  PROP_SDK=$(sed -n -e 's/^[[:space:]]*sdk\.dir[[:space:]]*=[[:space:]]*//p' "$PROJECT_DIR/local.properties" 2>/dev/null | tr -d '\r')
  PROP_SDK=$(echo "$PROP_SDK" | sed 's/\\:/:/g; s/\\\\/\//g')
  [ -n "$PROP_SDK" ] && [ -d "$PROP_SDK" ] && export ANDROID_HOME="$PROP_SDK"
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  # Standard OS-specific default paths
  if [ -n "${HOME:-}" ] && [ -d "$HOME/Android/Sdk" ]; then
    # Standard Linux (Ubuntu, Debian, Fedora, Arch, openSUSE)
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -n "${HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
    # macOS
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  elif [ -n "${LOCALAPPDATA:-}" ] && [ -d "$LOCALAPPDATA/Android/Sdk" ]; then
    # Windows native
    export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"
  elif [ -n "${USER:-}" ] && [ -d "/c/Users/$USER/AppData/Local/Android/Sdk" ]; then
    # Windows Git Bash / MSYS
    export ANDROID_HOME="/c/Users/$USER/AppData/Local/Android/Sdk"
  fi
fi

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "Notice: ANDROID_HOME is not set. Install Android SDK (Android Studio) or set sdk.dir in local.properties." >&2
else
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

# 3. Path Exports
[ -n "${JAVA_HOME:-}" ] && [ -d "$JAVA_HOME/bin" ] && export PATH="$JAVA_HOME/bin:$PATH"
if [ -n "${ANDROID_HOME:-}" ]; then
  [ -d "$ANDROID_HOME/platform-tools" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"
  [ -d "$ANDROID_HOME/emulator" ] && export PATH="$ANDROID_HOME/emulator:$PATH"
fi

#!/usr/bin/env sh
# Optional helper for command-line builds. Android Studio users do not need it.
# Uses a project-local .tooling/ folder only if it exists; otherwise keeps your existing
# JAVA_HOME / ANDROID_HOME. Then run:  ./gradlew assembleDebug
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -d "$PROJECT_DIR/.tooling/jdk-17" ]; then
  export JAVA_HOME="$PROJECT_DIR/.tooling/jdk-17"
fi
if [ -d "$PROJECT_DIR/.tooling/android-sdk" ]; then
  export ANDROID_HOME="$PROJECT_DIR/.tooling/android-sdk"
fi
if [ -z "$ANDROID_HOME" ] && [ -n "$ANDROID_SDK_ROOT" ]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi
if [ -z "$ANDROID_HOME" ]; then
  echo "ANDROID_HOME is not set. Install the Android SDK (Android Studio) or create local.properties with sdk.dir=..." >&2
fi
export ANDROID_SDK_ROOT="$ANDROID_HOME"
[ -n "$JAVA_HOME" ] && export PATH="$JAVA_HOME/bin:$PATH"
[ -n "$ANDROID_HOME" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"

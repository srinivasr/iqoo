#!/usr/bin/env sh
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export JAVA_HOME="$PROJECT_DIR/.tooling/jdk-17"
export ANDROID_HOME="$PROJECT_DIR/.tooling/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

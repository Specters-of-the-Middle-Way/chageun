#!/usr/bin/env bash
# Sourced by the local development scripts. No shell profile is modified.
DRIVESTEP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ]; then
  if [ -x /opt/homebrew/opt/openjdk@17/bin/java ]; then
    export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
  elif [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi
if [ -d "$DRIVESTEP_ROOT/.tooling/android-sdk" ]; then
  export ANDROID_HOME="$DRIVESTEP_ROOT/.tooling/android-sdk"
elif [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [ -n "${ANDROID_HOME:-}" ]; then
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
fi

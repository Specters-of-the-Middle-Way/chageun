#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
adb install -r "$DRIVESTEP_ROOT/app/build/outputs/apk/debug/app-debug.apk"
adb forward tcp:8088 tcp:8088
adb shell am start --user current -n kr.drivestep.tutor/.MainActivity
printf '\nPleos Virtual Console: http://127.0.0.1:8088/\n'

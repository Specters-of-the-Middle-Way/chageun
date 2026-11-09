#!/bin/bash
# Double-click this file in Finder to open the prepared Pleos driving tutor.
# Uses only the AVD named DriveStep_Pleos; never stops devices or clears app data.
set -euo pipefail

DRIVESTEP_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$DRIVESTEP_SCRIPT_DIR/env.sh"
cd "$DRIVESTEP_ROOT"

export DRIVESTEP_AVD="DriveStep_Pleos"
DRIVESTEP_LOG="$DRIVESTEP_ROOT/.tooling/launch-tutor.log"
DRIVESTEP_APK="$DRIVESTEP_ROOT/app/build/outputs/apk/debug/app-debug.apk"
DRIVESTEP_ADB="${ANDROID_HOME:-}/platform-tools/adb"

fail() {
  printf '\n실행을 마치지 못했어요: %s\n' "$1" >&2
  printf '프로젝트: %s\n' "$DRIVESTEP_ROOT" >&2
  printf '에뮬레이터 로그: %s\n' "$DRIVESTEP_LOG" >&2
  exit 1
}

trap 'printf "\n명령 실행 중 오류가 발생했습니다. 위 오류 내용과 .tooling/launch-tutor.log를 확인해 주세요.\n" >&2' ERR

[ -x "$DRIVESTEP_ADB" ] || fail "프로젝트에 준비된 Android SDK의 adb를 찾지 못했어요."
[ -d "$DRIVESTEP_ROOT/.tooling/avd/DriveStep_Pleos.avd" ] || fail "준비된 DriveStep_Pleos 에뮬레이터 폴더를 찾지 못했어요. 원본 프로젝트에서 실행해 주세요."
mkdir -p "$DRIVESTEP_ROOT/.tooling"

# Return only a serial whose emulator reports the exact expected AVD name.
# A phone or a differently named emulator is never selected as a fallback.
find_target() {
  local candidate avd found=""
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    avd="$("$DRIVESTEP_ADB" -s "$candidate" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)" || avd=""
    if [ "$avd" = "$DRIVESTEP_AVD" ]; then
      if [ -n "$found" ]; then
        printf '같은 이름의 Pleos 에뮬레이터가 둘 이상이라 자동으로 선택하지 않았어요.\n' >&2
        return 2
      fi
      found="$candidate"
    fi
  done < <("$DRIVESTEP_ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  printf '%s' "$found"
}

printf '\n차근 · 플레오스 운전 튜터를 열고 있어요.\n'
printf '프로젝트: %s\n\n' "$DRIVESTEP_ROOT"
DRIVESTEP_SERIAL="$(find_target)" || fail "실행 대상을 하나로 확인할 수 없어요."

if [ -n "$DRIVESTEP_SERIAL" ]; then
  printf '이미 실행 중인 %s (%s)를 사용합니다.\n' "$DRIVESTEP_AVD" "$DRIVESTEP_SERIAL"
elif pgrep -f '(^|[[:space:]])-avd[[:space:]]+DriveStep_Pleos([[:space:]]|$)' >/dev/null 2>&1; then
  # The emulator may be starting but not yet registered with adb. Do not launch it twice.
  printf '플레오스가 이미 시작 중이에요. 연결될 때까지 기다립니다.\n'
else
  printf '플레오스 자동차 화면을 켭니다. 첫 부팅은 잠시 걸릴 수 있어요.\n'
  nohup "$DRIVESTEP_SCRIPT_DIR/run-emulator.sh" >> "$DRIVESTEP_LOG" 2>&1 < /dev/null &
fi

DRIVESTEP_DEADLINE=$((SECONDS + 180))
DRIVESTEP_NEXT_STATUS=$((SECONDS + 15))
DRIVESTEP_BOOTED=0
while [ "$SECONDS" -lt "$DRIVESTEP_DEADLINE" ]; do
  DRIVESTEP_SERIAL="$(find_target)" || fail "같은 이름의 기기가 여러 개여서 진행하지 않았어요."
  if [ -n "$DRIVESTEP_SERIAL" ]; then
    DRIVESTEP_BOOT="$("$DRIVESTEP_ADB" -s "$DRIVESTEP_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')" || DRIVESTEP_BOOT=""
    if [ "$DRIVESTEP_BOOT" = "1" ]; then
      DRIVESTEP_BOOTED=1
      break
    fi
  fi
  if [ "$SECONDS" -ge "$DRIVESTEP_NEXT_STATUS" ]; then
    printf '자동차 화면이 준비될 때까지 기다리는 중입니다…\n'
    DRIVESTEP_NEXT_STATUS=$((SECONDS + 15))
  fi
  sleep 2
done
[ "$DRIVESTEP_BOOTED" = "1" ] || fail "180초 안에 부팅 완료를 확인하지 못했어요. 실행 중인 에뮬레이터를 종료하지 않았으니 로그를 확인해 주세요."

export ANDROID_SERIAL="$DRIVESTEP_SERIAL"
printf '\n연결 확인: %s (%s)\n' "$DRIVESTEP_AVD" "$ANDROID_SERIAL"
if [ ! -f "$DRIVESTEP_APK" ]; then
  printf '설치 파일이 없어 프로젝트를 빌드합니다. 처음에는 다운로드가 필요할 수 있어요.\n'
  "$DRIVESTEP_SCRIPT_DIR/build.sh"
fi
[ -f "$DRIVESTEP_APK" ] || fail "빌드된 app-debug.apk를 찾지 못했어요."

# Confirm the target again immediately before the install; preserve the exact serial.
DRIVESTEP_CONFIRMED="$("$DRIVESTEP_ADB" -s "$ANDROID_SERIAL" emu avd name 2>/dev/null | tr -d '\r' | head -n 1)" || DRIVESTEP_CONFIRMED=""
[ "$DRIVESTEP_CONFIRMED" = "$DRIVESTEP_AVD" ] || fail "설치 전에 기기 연결이 바뀌었어요. 다른 기기에는 설치하지 않았습니다."
printf '차근을 설치하고 현재 차량 사용자로 엽니다. 기존 학습 기록은 유지합니다.\n'
"$DRIVESTEP_SCRIPT_DIR/install.sh"
printf '\n준비됐어요! 플레오스 자동차 화면에서 차근을 확인해 주세요.\n'
printf '권한 요청이 나오면 차량 앱 화면에서 선택할 수 있습니다.\n'
printf '다음에도 이 launch-tutor.command 파일을 더블클릭하면 됩니다.\n'

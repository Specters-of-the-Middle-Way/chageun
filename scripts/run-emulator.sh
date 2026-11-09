#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [ -d "$DRIVESTEP_ROOT/.tooling/avd/DriveStep_Pleos.avd" ]; then
  export ANDROID_AVD_HOME="$DRIVESTEP_ROOT/.tooling/avd"
fi
# Qt skips macOS UF_HIDDEN plugins. Keep the project-local toolchain launchable
# even if the workspace marks generated files as hidden; .tooling stays a dotdir.
DRIVESTEP_QT_PLUGINS="$DRIVESTEP_ROOT/.tooling/android-sdk/emulator/lib64/qt/plugins"
if [ "$(uname -s)" = Darwin ] && [ -d "$DRIVESTEP_QT_PLUGINS" ]; then
  chflags -R nohidden "$DRIVESTEP_QT_PLUGINS"
fi
exec emulator -avd "${DRIVESTEP_AVD:-DriveStep_Pleos}" -no-snapshot -gpu host -memory 4096 \
  -no-metrics -crash-report-mode never "$@"

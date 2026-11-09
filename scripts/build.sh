#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$DRIVESTEP_ROOT"
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug "$@"

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
./gradlew testDebugUnitTest lintDebug assemblePreview assembleRelease
if [[ "${1:-}" == "--device" ]]; then
  : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated tablet emulator/device before running UI tests}"
  ./gradlew connectedDebugAndroidTest
fi

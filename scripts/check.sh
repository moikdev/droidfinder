#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assemblePreview :app:assembleRelease
if [[ "${1:-}" == "--device" ]]; then
  : "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a dedicated tablet emulator/device before running UI tests}"
  ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
  python3 scripts/check-device.py --serial "$ANDROID_SERIAL" --install --output "captures/device-check-$ANDROID_SERIAL.txt"
fi

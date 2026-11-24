#!/usr/bin/env bash
# Build the debug APK without installing Android Studio — needs only Docker.
#   ./scripts/build-apk.sh            -> app/build/outputs/apk/debug/app-debug.apk
#   ./scripts/build-apk.sh test       -> run the JVM unit tests only
# First run downloads the image (~4 GB) and Gradle deps; later runs are cached in ~/.gradle.
set -euo pipefail
cd "$(dirname "$0")/.."
TASK="assembleDebug"; [ "${1:-}" = "test" ] && TASK="testDebugUnitTest"
docker run --rm -t \
  -v "$PWD":/project -w /project \
  -v "$HOME/.gradle":/root/.gradle \
  mingc/android-build-box:latest \
  bash -c "chmod +x gradlew && ./gradlew --no-daemon $TASK"
[ "$TASK" = "assembleDebug" ] && ls -lh app/build/outputs/apk/debug/*.apk
echo "Cài lên máy: adb install -r app/build/outputs/apk/debug/app-debug.apk"

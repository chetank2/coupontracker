#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPORT_DEST="${PROJECT_ROOT}/build/extraction-eval/android_smoke_report.json"
REPORT_DEVICE="/sdcard/Android/data/com.example.coupontracker/files/extraction_smoke_report.json"
TEST_CLASS="com.example.coupontracker.extraction.ExtractionSmokeTest"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" ]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not available on PATH." >&2
  exit 1
fi

cd "$PROJECT_ROOT"
mkdir -p "$(dirname "$REPORT_DEST")"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS"

if adb pull "$REPORT_DEVICE" "$REPORT_DEST"; then
  echo "Pulled Android extraction smoke report to $REPORT_DEST"
else
  echo "WARN: Connected smoke test passed, but report was not available at $REPORT_DEVICE after test cleanup." >&2
fi

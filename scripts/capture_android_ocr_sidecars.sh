#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REPORT_DEST="${PROJECT_ROOT}/build/extraction-eval/android_ocr_sidecars.json"
REPORT_DEVICE="/sdcard/Android/data/com.example.coupontracker/files/ocr_sidecars.json"
TEST_CLASS="com.example.coupontracker.extraction.OcrSidecarCaptureTest"
SIDECAR_DIR="${PROJECT_ROOT}/Coupons /ocr"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" ]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb is not available on PATH. Install Android platform-tools, connect the device, then rerun." >&2
  exit 1
fi

cd "$PROJECT_ROOT"
mkdir -p "$(dirname "$REPORT_DEST")" "$SIDECAR_DIR"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASS"

adb pull "$REPORT_DEVICE" "$REPORT_DEST"

PYTHONPATH="${PROJECT_ROOT}/scripts${PYTHONPATH:+:$PYTHONPATH}" "${PROJECT_ROOT}/scripts/.venv/bin/python" \
  "${PROJECT_ROOT}/scripts/extraction_eval/write_android_ocr_sidecars.py" \
  --report "$REPORT_DEST" \
  --out-dir "$SIDECAR_DIR"

echo "Pulled Android OCR report to $REPORT_DEST"
echo "Wrote sidecars to $SIDECAR_DIR"

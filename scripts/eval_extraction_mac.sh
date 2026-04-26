#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

JAR="${PROJECT_ROOT}/app/build/libs/extraction-tool.jar"
if [ ! -f "${JAR}" ]; then
  ./gradlew :app:extractionToolJar
fi

if [ ! -f "build/llama_cpp_mac/bin/llama-mtmd-cli" ]; then
  ./scripts/build_llama_cpp_mac.sh
fi

source "${PROJECT_ROOT}/scripts/.venv/bin/activate" 2>/dev/null || {
  python3 -m venv "${PROJECT_ROOT}/scripts/.venv"
  source "${PROJECT_ROOT}/scripts/.venv/bin/activate"
  pip install -r "${PROJECT_ROOT}/scripts/extraction_eval/requirements.txt"
}

cd "${PROJECT_ROOT}/scripts"
python -m extraction_eval --jar "${JAR}" "$@"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_CONFIG="${PROJECT_ROOT}/config/extraction/runtime.json"
BUILD_DIR="${PROJECT_ROOT}/build/llama_cpp_mac"
SRC_DIR="${BUILD_DIR}/src"
COMMIT="$(
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["llamaCppCommit"])' "${RUNTIME_CONFIG}"
)"
CMAKE_BIN="${CMAKE:-cmake}"

if ! command -v "${CMAKE_BIN}" >/dev/null 2>&1; then
  if [ -x "${HOME}/Library/Android/sdk/cmake/3.22.1/bin/cmake" ]; then
    CMAKE_BIN="${HOME}/Library/Android/sdk/cmake/3.22.1/bin/cmake"
  else
    echo "ERROR: cmake is not available on PATH and Android SDK CMake was not found." >&2
    exit 1
  fi
fi

if [ "${COMMIT}" = "PENDING" ] || [ "${COMMIT}" = "PENDING_DOWNLOAD" ] || [ -z "${COMMIT}" ]; then
  echo "ERROR: llamaCppCommit is not pinned in ${RUNTIME_CONFIG}" >&2
  exit 1
fi

mkdir -p "${BUILD_DIR}"

if [ ! -d "${SRC_DIR}/.git" ]; then
  git clone https://github.com/ggerganov/llama.cpp.git "${SRC_DIR}"
fi

git -C "${SRC_DIR}" fetch origin
git -C "${SRC_DIR}" checkout "${COMMIT}"

"${CMAKE_BIN}" -S "${SRC_DIR}" -B "${BUILD_DIR}/cmake" \
  -DGGML_METAL=ON \
  -DLLAMA_CURL=OFF \
  -DCMAKE_BUILD_TYPE=Release

"${CMAKE_BIN}" --build "${BUILD_DIR}/cmake" --target llama-mtmd-cli -j "$(
  sysctl -n hw.ncpu 2>/dev/null || echo 4
)"

mkdir -p "${BUILD_DIR}/bin"
if [ -x "${BUILD_DIR}/cmake/bin/llama-mtmd-cli" ]; then
  ln -sf "${BUILD_DIR}/cmake/bin/llama-mtmd-cli" "${BUILD_DIR}/bin/llama-mtmd-cli"
elif [ -x "${BUILD_DIR}/cmake/tools/mtmd/llama-mtmd-cli" ]; then
  ln -sf "${BUILD_DIR}/cmake/tools/mtmd/llama-mtmd-cli" "${BUILD_DIR}/bin/llama-mtmd-cli"
else
  found="$(find "${BUILD_DIR}/cmake" -type f -perm +111 -name llama-mtmd-cli | head -1 || true)"
  if [ -z "${found}" ]; then
    echo "ERROR: llama-mtmd-cli was not produced by the build" >&2
    exit 1
  fi
  ln -sf "${found}" "${BUILD_DIR}/bin/llama-mtmd-cli"
fi

echo "Built: ${BUILD_DIR}/bin/llama-mtmd-cli"

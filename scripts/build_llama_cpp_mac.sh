#!/usr/bin/env bash
#
# Build llama.cpp for macOS at the commit pinned in
# config/extraction/runtime.json. Output: build/llama_cpp_mac/bin/llama-mtmd-cli.
#
# Used by the Mac extraction harness (scripts/eval_extraction_mac.sh) for
# Phase 0 parity verification and Phase 1 evals.
#
# Prereqs:
#   - cmake (3.18+)
#   - Xcode command line tools (Apple silicon: builds Metal-accelerated)
#   - jq
#   - git
#
# Usage:
#   ./scripts/build_llama_cpp_mac.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
RUNTIME_JSON="${PROJECT_ROOT}/config/extraction/runtime.json"

if [ ! -f "${RUNTIME_JSON}" ]; then
  echo "ERROR: ${RUNTIME_JSON} not found. Phase 0.1 must create it first." >&2
  exit 1
fi

COMMIT="$(jq -r .llamaCppCommit "${RUNTIME_JSON}")"
if [ "${COMMIT}" = "PENDING" ] || [ -z "${COMMIT}" ] || [ "${COMMIT}" = "null" ]; then
  echo "ERROR: llamaCppCommit is PENDING in ${RUNTIME_JSON}." >&2
  echo "       Pin a llama.cpp commit (full 40-char SHA) before running this script." >&2
  exit 1
fi

# Read build flags as a bash array.
# jq emits one flag per line; mapfile collects into BUILD_FLAGS.
mapfile -t BUILD_FLAGS < <(jq -r '.llamaCppBuildFlags[]' "${RUNTIME_JSON}")

BUILD_DIR="${PROJECT_ROOT}/build/llama_cpp_mac"
SRC_DIR="${BUILD_DIR}/src"

mkdir -p "${BUILD_DIR}"

if [ ! -d "${SRC_DIR}/.git" ]; then
  echo "Cloning llama.cpp..."
  git clone https://github.com/ggerganov/llama.cpp "${SRC_DIR}"
fi

echo "Checking out ${COMMIT}..."
git -C "${SRC_DIR}" fetch --tags origin
git -C "${SRC_DIR}" checkout "${COMMIT}"

echo "Configuring build with flags: ${BUILD_FLAGS[*]}"
cmake -S "${SRC_DIR}" -B "${BUILD_DIR}/cmake" \
  "${BUILD_FLAGS[@]}" \
  -DCMAKE_BUILD_TYPE=Release

echo "Building llama-mtmd-cli..."
cmake --build "${BUILD_DIR}/cmake" --target llama-mtmd-cli -j

# Stable symlink so runtime.json's macBinary path is invariant across CMake
# directory layouts (some llama.cpp commits put binaries directly under cmake/,
# others under cmake/bin).
mkdir -p "${BUILD_DIR}/bin"
if [ -x "${BUILD_DIR}/cmake/bin/llama-mtmd-cli" ]; then
  ln -sf "${BUILD_DIR}/cmake/bin/llama-mtmd-cli" "${BUILD_DIR}/bin/llama-mtmd-cli"
elif [ -x "${BUILD_DIR}/cmake/llama-mtmd-cli" ]; then
  ln -sf "${BUILD_DIR}/cmake/llama-mtmd-cli" "${BUILD_DIR}/bin/llama-mtmd-cli"
else
  echo "ERROR: llama-mtmd-cli was not produced under ${BUILD_DIR}/cmake/." >&2
  exit 1
fi

echo
echo "Built: ${BUILD_DIR}/bin/llama-mtmd-cli"
echo "Verify: ${BUILD_DIR}/bin/llama-mtmd-cli --help | head"

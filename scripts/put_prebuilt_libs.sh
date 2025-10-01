#!/usr/bin/env bash
#
# Place prebuilt libraries into jniLibs for packaging
#
# This script helps you copy prebuilt .so files (like libllama.so)
# into the correct locations so Gradle will package them in the APK.
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNILIBS_DIR="$ROOT_DIR/app/src/main/jniLibs"

echo "📦 Preparing jniLibs directories..."

# Create jniLibs directories for each ABI
for abi in arm64-v8a armeabi-v7a x86_64; do
    mkdir -p "$JNILIBS_DIR/$abi"
    echo "  ✅ $JNILIBS_DIR/$abi"
done

echo ""
echo "📝 Instructions:"
echo ""
echo "To use llama.cpp backend:"
echo "  1. Build libllama.so for Android (see build_llama_cpp.sh)"
echo "  2. Copy to:"
echo "     $JNILIBS_DIR/arm64-v8a/libllama.so"
echo "     $JNILIBS_DIR/armeabi-v7a/libllama.so"
echo "     $JNILIBS_DIR/x86_64/libllama.so"
echo ""
echo "To use MLC-LLM backend:"
echo "  - No prebuilt libs needed in jniLibs!"
echo "  - The runtime .so is loaded dynamically from the model directory"
echo "  - Place runtime in: filesDir/models/runtime/<abi>/minicpm_llm_q4f16_1.so"
echo ""
echo "Example: Copy llama.cpp lib"
echo "  cp ~/Downloads/llama.cpp/build-android-arm64/libllama.so \\"
echo "     $JNILIBS_DIR/arm64-v8a/"
echo ""

# Check if any libs exist
any_libs=false
for abi in arm64-v8a armeabi-v7a x86_64; do
    if [ -d "$JNILIBS_DIR/$abi" ] && [ -n "$(ls -A "$JNILIBS_DIR/$abi" 2>/dev/null)" ]; then
        echo "✅ Found libs in $abi:"
        ls -lh "$JNILIBS_DIR/$abi"
        any_libs=true
    fi
done

if [ "$any_libs" = false ]; then
    echo "⚠️  No prebuilt libraries found yet"
    echo "   This is OK if using MLC-LLM backend (runtime loaded from model dir)"
fi

echo ""
echo "Done!"


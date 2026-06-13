#!/usr/bin/env bash
#
# Build native bridge for MiniCPM Vision
#
# Usage:
#   ./scripts/build_native.sh           # Build with MLC backend (default)
#   ./scripts/build_native.sh mlc       # Build with MLC backend
#   ./scripts/build_native.sh llama     # Build with llama.cpp backend
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Load NDK environment
if [ -f "$SCRIPT_DIR/ndk_env.sh" ]; then
    source "$SCRIPT_DIR/ndk_env.sh"
else
    echo "⚠️  Warning: ndk_env.sh not found"
    echo "   Copy ndk_env.example.sh to ndk_env.sh and edit paths"
    echo "   Trying to use default NDK location..."
    
    # Try to find NDK automatically
    if [ -d "$HOME/Library/Android/sdk/ndk" ]; then
        # Find latest NDK version
        NDK_VERSION=$(ls -1 "$HOME/Library/Android/sdk/ndk" | sort -V | tail -1)
        export ANDROID_NDK="$HOME/Library/Android/sdk/ndk/$NDK_VERSION"
        export ANDROID_HOME="$HOME/Library/Android/sdk"
        echo "   Found NDK: $ANDROID_NDK"
    fi
fi

# Check NDK
if [ -z "${ANDROID_NDK:-}" ]; then
    echo "❌ Error: ANDROID_NDK not set"
    echo "   Please create scripts/ndk_env.sh from ndk_env.example.sh"
    exit 1
fi

if [ ! -d "$ANDROID_NDK" ]; then
    echo "❌ Error: NDK not found at: $ANDROID_NDK"
    exit 1
fi

echo "✅ Using NDK: $ANDROID_NDK"

# Determine backend
BACKEND="${1:-mlc}"
if [ "$BACKEND" != "mlc" ] && [ "$BACKEND" != "llama" ]; then
    echo "❌ Error: Invalid backend: $BACKEND"
    echo "   Usage: $0 [mlc|llama]"
    exit 1
fi

echo ""
echo "==============================================  "
echo "Building MiniCPM Vision Native Bridge"
echo "  Backend: $BACKEND"
echo "  Root: $ROOT_DIR"
echo "=============================================="
echo ""

cd "$ROOT_DIR"

# Build with Gradle
if [ "$BACKEND" = "mlc" ]; then
    echo "📦 Building with MLC-LLM backend (vision-capable)..."
    ./gradlew :app:clean :app:assembleDebug \
        -Pandroid.externalNativeBuild.cmake.arguments="-DUSE_MLC_RUNTIME=ON;-DUSE_LLAMACPP_RUNTIME=OFF"
else
    echo "📦 Building with llama.cpp backend (text-only, vision TODO)..."
    ./gradlew :app:clean :app:assembleDebug \
        -Pandroid.externalNativeBuild.cmake.arguments="-DUSE_MLC_RUNTIME=OFF;-DUSE_LLAMACPP_RUNTIME=ON"
fi

echo ""
echo "==============================================  "
echo "Build complete!"
echo "=============================================="
echo ""

# Check output
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug"
if [ -f "$APK_PATH/app-universal-debug.apk" ]; then
    echo "✅ APK: $APK_PATH/app-universal-debug.apk"
    
    # Check native library
    if unzip -l "$APK_PATH/app-universal-debug.apk" | grep -q "libmlc_llm_android.so"; then
        echo "✅ Native library packaged:"
        unzip -l "$APK_PATH/app-universal-debug.apk" | grep "libmlc_llm_android.so"
    else
        echo "⚠️  Warning: libmlc_llm_android.so not found in APK"
    fi
else
    echo "⚠️  Warning: Universal APK not found"
    echo "   Check: $APK_PATH/"
    ls -la "$APK_PATH/" || true
fi

echo ""
echo "📱 Next steps:"
echo "   1. Install: adb install $APK_PATH/app-universal-debug.apk"
echo "   2. Import model (with runtime if using MLC backend)"
echo "   3. Test inference"
echo "   4. Check logs: adb logcat | grep MlcLlmNativeBridge"
echo ""

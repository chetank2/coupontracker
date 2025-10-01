#!/bin/bash
#
# Build llama.cpp for Android
#
# This script builds llama.cpp with vision support for Android devices
# Output: libllama.so for arm64-v8a, armeabi-v7a, x86_64
#
# Requirements:
#   - CMake 3.18+
#   - Android NDK (set ANDROID_NDK or auto-detect)
#   - Git
#
# Usage:
#   ./scripts/build_llama_cpp.sh
#

set -e  # Exit on error

echo "🔨 Building llama.cpp for Android"
echo "=================================="

# Find Android NDK
if [ -z "$ANDROID_NDK" ]; then
    # Try common locations
    NDK_LOCATIONS=(
        "$HOME/Library/Android/sdk/ndk"
        "$ANDROID_HOME/ndk"
        "$ANDROID_SDK_ROOT/ndk"
    )
    
    for ndk_base in "${NDK_LOCATIONS[@]}"; do
        if [ -d "$ndk_base" ]; then
            # Find latest version
            ANDROID_NDK=$(find "$ndk_base" -maxdepth 1 -type d | sort -V | tail -1)
            if [ -n "$ANDROID_NDK" ]; then
                echo "✅ Found NDK: $ANDROID_NDK"
                break
            fi
        fi
    done
    
    if [ -z "$ANDROID_NDK" ]; then
        echo "❌ Android NDK not found!"
        echo "   Set ANDROID_NDK environment variable or install NDK via Android Studio"
        exit 1
    fi
fi

# Setup paths
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$HOME/Downloads/llama.cpp-android-build"
LLAMA_SRC="$BUILD_DIR/llama.cpp"

echo "📂 Project root: $PROJECT_ROOT"
echo "📂 Build directory: $BUILD_DIR"

# Clone llama.cpp if needed
if [ ! -d "$LLAMA_SRC" ]; then
    echo "📥 Cloning llama.cpp..."
    mkdir -p "$BUILD_DIR"
    cd "$BUILD_DIR"
    git clone https://github.com/ggerganov/llama.cpp
    cd llama.cpp
    echo "✅ Cloned llama.cpp"
else
    echo "✅ Using existing llama.cpp at $LLAMA_SRC"
    cd "$LLAMA_SRC"
    git pull origin master || true
fi

# Build for each ABI
ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
ANDROID_PLATFORM=26

for abi in "${ABIS[@]}"; do
    echo ""
    echo "🔨 Building for $abi..."
    echo "-------------------------"
    
    BUILD_ABI_DIR="$LLAMA_SRC/build-android-$abi"
    rm -rf "$BUILD_ABI_DIR"
    mkdir -p "$BUILD_ABI_DIR"
    cd "$BUILD_ABI_DIR"
    
    # Configure CMake
    cmake .. \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
        -DCMAKE_BUILD_TYPE=Release \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_SERVER=OFF
    
    # Build
    make -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4) llama
    
    # Copy to project
    DEST_DIR="$PROJECT_ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$DEST_DIR"
    cp libllama.so "$DEST_DIR/"
    
    # Get file size
    SIZE=$(du -h "$DEST_DIR/libllama.so" | cut -f1)
    echo "✅ Built $abi: $SIZE"
done

echo ""
echo "🎉 Build complete!"
echo "=================="
echo ""
echo "📦 Libraries installed to:"
for abi in "${ABIS[@]}"; do
    lib_path="$PROJECT_ROOT/app/src/main/jniLibs/$abi/libllama.so"
    if [ -f "$lib_path" ]; then
        size=$(du -h "$lib_path" | cut -f1)
        echo "   ✅ $abi: $size"
    else
        echo "   ❌ $abi: FAILED"
    fi
done

echo ""
echo "🚀 Next steps:"
echo "   1. Rebuild your app: ./gradlew assembleDebug"
echo "   2. Test on device"
echo "   3. Check logs for 'llama.cpp vision JNI loaded'"
echo ""


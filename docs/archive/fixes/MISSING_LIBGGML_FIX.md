# Missing libggml.so - Fix Guide

**Date**: October 2, 2025  
**Issue**: `dlopen failed: library "libggml.so" not found`  
**Impact**: MiniCPM model cannot run

---

## **Problem**

The app has `libllama.so` but is missing `libggml.so`, which is a required dependency:

```
Error: dlopen failed: library "libggml.so" not found: needed by libllama.so
```

---

## **Why This Happened**

When llama.cpp was built, it created **two libraries**:
1. `libggml.so` - Low-level tensor operations
2. `libllama.so` - High-level llama model API (depends on libggml.so)

Only `libllama.so` was copied to the app, but `libggml.so` was forgotten.

---

## **Solution 1: Download Prebuilt Libraries** (FASTEST - 5 mins)

### **Step 1: Download from llama.cpp CI**

Visit: https://github.com/ggerganov/llama.cpp/releases

OR use a prebuilt Android library from:
- https://github.com/ggerganov/llama.cpp/actions (CI artifacts)
- Look for "Android" build artifacts

### **Step 2: Extract libggml.so**

From the downloaded ZIP, extract:
```
lib/arm64-v8a/libggml.so
lib/armeabi-v7a/libggml.so
lib/x86/libggml.so
lib/x86_64/libggml.so
```

### **Step 3: Copy to Project**

```bash
cd /Users/user/Downloads/CouponTracker3

# Copy for each ABI
cp /path/to/downloaded/lib/arm64-v8a/libggml.so app/src/main/jniLibs/arm64-v8a/
cp /path/to/downloaded/lib/armeabi-v7a/libggml.so app/src/main/jniLibs/armeabi-v7a/
cp /path/to/downloaded/lib/x86/libggml.so app/src/main/jniLibs/x86/
cp /path/to/downloaded/lib/x86_64/libggml.so app/src/main/jniLibs/x86_64/
```

### **Step 4: Rebuild & Install**

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

---

## **Solution 2: Rebuild llama.cpp** (THOROUGH - 30 mins)

### **Step 1: Clone llama.cpp**

```bash
cd /Users/user/Downloads
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
```

### **Step 2: Build for Android**

```bash
# Install Android NDK if needed
export ANDROID_NDK=/path/to/android-ndk

# Build for arm64-v8a
mkdir build-android-arm64
cd build-android-arm64
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_BUILD_EXAMPLES=OFF

make -j8

# This creates:
#   libggml.so
#   libllama.so
```

### **Step 3: Copy Both Libraries**

```bash
cd /Users/user/Downloads/CouponTracker3

# arm64-v8a
cp /path/to/llama.cpp/build-android-arm64/libggml.so app/src/main/jniLibs/arm64-v8a/
cp /path/to/llama.cpp/build-android-arm64/libllama.so app/src/main/jniLibs/arm64-v8a/

# Repeat for other ABIs (armeabi-v7a, x86, x86_64)
```

### **Step 4: Rebuild & Install**

```bash
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

---

## **Solution 3: Static Linking** (ALTERNATIVE - 20 mins)

Modify CMake to statically link libggml into libllama.

**app/src/main/cpp/CMakeLists.txt**:

```cmake
# BEFORE (separate libraries)
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${llama-lib}")

# AFTER (static linking)
# Rebuild llama.cpp with -DBUILD_SHARED_LIBS=OFF
# Then link statically:
add_library(llama STATIC IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "${llama-lib}")
```

But this requires **rebuilding llama.cpp** with static libs enabled.

---

## **Verification**

After applying the fix, verify:

```bash
# Extract libs from APK
unzip -l app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | grep "lib/arm64"

# Should show:
# lib/arm64-v8a/libggml.so         ← NOW PRESENT
# lib/arm64-v8a/libllama.so        ← ALREADY PRESENT
# lib/arm64-v8a/libmlc_llm_android.so  ← ALREADY PRESENT
```

### **Test in App**

1. Install APK
2. Upload coupon
3. Check logcat:

**BEFORE** (broken):
```
E MlcLlmNative: Failed to load MLC-LLM native library
E MlcLlmNative: dlopen failed: library "libggml.so" not found
```

**AFTER** (fixed):
```
I MlcLlmNative: MLC-LLM native library loaded from application package
I LlmRuntimeManager: Model loaded successfully
I ProgressiveExtractionService: ✅ MiniCPM extracted 4 fields
```

---

## **Quick Fix Script** (If You Have libggml.so)

If you already have `libggml.so` files somewhere:

```bash
#!/bin/bash
# quick_fix_libggml.sh

GGML_SOURCE="/path/to/prebuilt/libggml"
PROJECT_DIR="/Users/user/Downloads/CouponTracker3"

for ABI in arm64-v8a armeabi-v7a x86 x86_64; do
  cp "$GGML_SOURCE/$ABI/libggml.so" "$PROJECT_DIR/app/src/main/jniLibs/$ABI/"
  echo "✅ Copied libggml.so for $ABI"
done

cd "$PROJECT_DIR"
./gradlew assembleDebug
echo "✅ APK rebuilt with libggml.so"
```

---

## **Expected File Sizes**

After fix, each ABI folder should have:

```
arm64-v8a/
  libggml.so           ~8-12 MB    ← NEW
  libllama.so          ~20-25 MB   ← EXISTING
  libmlc_llm_android.so ~300 KB    ← EXISTING
```

---

## **Next Steps**

1. Choose Solution 1 (fastest) or Solution 2 (most reliable)
2. Copy `libggml.so` to all ABI folders
3. Rebuild APK
4. Test extraction - MiniCPM should now work!

---

**Status**: ⏳ **BLOCKED** until `libggml.so` is added to project


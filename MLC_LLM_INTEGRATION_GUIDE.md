# MLC-LLM Integration Guide - Real MiniCPM Inference

## 🚨 **Current Status: MOCK MODE**

**The app currently uses MOCK JNI stubs that return hard-coded JSON instead of running real MiniCPM inference.**

---

## 📊 **Problem Summary**

### **What's Broken**

1. **`BUILD_MOCK_JNI=ON`** flag in `app/build.gradle.kts` forces CMake to compile the mock implementation
2. **MLC-LLM libraries are text placeholders**:
   ```bash
   $ file app/libs/mlc_llm/lib/arm64-v8a/libmlc_llm_runtime.so
   ASCII text: "placeholder MLC-LLM runtime library"
   ```
3. **All LLM_FIRST extractions return fake data**:
   ```cpp
   // app/src/main/cpp/mlc_llm_jni.cpp:92-99
   std::string mock_response = R"({
       "storeName": "Example Store",
       "description": "Mock LLM extraction result",
       "cashbackAmount": "10.00",
       "redeemCode": "MOCK123",
       "expiryDate": "2024-12-31"
   })";
   ```

### **Impact**

- ❌ **`LLM_FIRST` strategy**: Returns "Example Store / MOCK123" for ALL images
- ❌ **`HYBRID` strategy**: LLM branch contributes fake data, polluting fusion
- ❌ **Single-scan fallback**: Uses mock when two-stage detector fails
- ❌ **Batch scanning**: `processWithLlmFirstPath` consumes canned JSON
- ✅ **`OCR_FIRST` strategy**: Unaffected (doesn't use LLM)
- ✅ **`LEGACY` strategy**: Falls back to OCR (works)

---

## 🔧 **Root Cause Analysis**

### **Missing Real Libraries**

The MLC-LLM runtime requires **compiled native binaries** that:
1. Link against Apache TVM runtime
2. Include MiniCPM-Llama3-V2.5 model weights (quantized to 4-bit)
3. Support vision inference (RGB image → JSON)

**Why Placeholders Exist**:
- Real `.so` files are ~400-600 MB per ABI
- Require GPU-enabled build server (CUDA/Metal)
- Need MLC-LLM source + Android NDK + TVM compiler
- Build time: 4-6 hours per ABI on GPU server
- Not suitable for Git repo (would bloat to >2 GB)

---

## 📦 **Solution: Build Real MLC-LLM Libraries**

### **Option 1: Download Pre-Built Binaries** (Recommended)

**If available** from MLC-AI team or third-party:

1. Download `mlc-llm-android-binaries.zip` containing:
   ```
   arm64-v8a/
     ├── libmlc_llm_runtime.so  (~450 MB)
     ├── libtvm_runtime.so      (~120 MB)
     └── librelax_runtime.so    (~80 MB)
   armeabi-v7a/
     └── (same)
   x86_64/
     └── (same)
   ```

2. Replace placeholders:
   ```bash
   rm -rf app/libs/mlc_llm/lib/{arm64-v8a,armeabi-v7a,x86_64}/*.so
   unzip mlc-llm-android-binaries.zip -d app/libs/mlc_llm/lib/
   ```

3. Verify:
   ```bash
   file app/libs/mlc_llm/lib/arm64-v8a/libmlc_llm_runtime.so
   # Should show: ELF 64-bit LSB shared object, ARM aarch64
   ```

4. Update `app/build.gradle.kts`:
   ```kotlin
   arguments += listOf(
       "-DANDROID_STL=c++_shared",
       "-DANDROID_PLATFORM=android-26"
       // BUILD_MOCK_JNI=ON removed - real libraries now available
   )
   ```

5. Build:
   ```bash
   ./gradlew clean assembleDebug
   # Should see: "Using real MLC-LLM JNI implementation"
   ```

---

### **Option 2: Build from Source** (Advanced)

**Requirements**:
- Linux/macOS with CUDA-enabled GPU (or M1/M2 Mac with Metal)
- 16+ GB RAM
- 50+ GB disk space
- 4-6 hours build time

**Steps**:

#### 1. **Clone MLC-LLM Source**

```bash
git clone --recursive https://github.com/mlc-ai/mlc-llm.git
cd mlc-llm
git checkout v0.1.0  # Or latest stable release
git submodule update --init --recursive
```

#### 2. **Install Dependencies**

```bash
# Python environment
conda create -n mlc-llm python=3.10
conda activate mlc-llm
pip install --pre mlc-ai-nightly mlc-llm-nightly -f https://mlc.ai/wheels

# Android NDK
export ANDROID_NDK=/path/to/android-ndk-r27  # Update path
```

#### 3. **Build MiniCPM Model for Android**

```bash
# Download MiniCPM-Llama3-V2.5 weights
huggingface-cli download openbmb/MiniCPM-Llama3-V-2_5 --local-dir ./dist/models/MiniCPM-Llama3-V2.5

# Convert to MLC format with 4-bit quantization
mlc_llm convert_weight ./dist/models/MiniCPM-Llama3-V2.5 \
    --quantization q4f16_1 \
    --output ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1

# Generate MLC config
mlc_llm gen_config ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1 \
    --quantization q4f16_1 \
    --conv-template minicpm_llama3 \
    --context-window-size 2048 \
    --output ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1/mlc-chat-config.json
```

#### 4. **Compile for Android**

```bash
# Build for arm64-v8a
mlc_llm compile ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1/mlc-chat-config.json \
    --device android \
    --target "llvm -mtriple=aarch64-linux-android" \
    -o ./dist/libs/arm64-v8a/libmlc_llm_module_MiniCPM.so

# Build for armeabi-v7a
mlc_llm compile ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1/mlc-chat-config.json \
    --device android \
    --target "llvm -mtriple=armv7a-linux-androideabi" \
    -o ./dist/libs/armeabi-v7a/libmlc_llm_module_MiniCPM.so

# Build for x86_64 (emulator)
mlc_llm compile ./dist/models/MiniCPM-Llama3-V2.5-q4f16_1/mlc-chat-config.json \
    --device android \
    --target "llvm -mtriple=x86_64-linux-android" \
    -o ./dist/libs/x86_64/libmlc_llm_module_MiniCPM.so
```

#### 5. **Copy to CouponTracker**

```bash
# Copy runtime libraries
cp -r ./android/mlc4j/src/main/jniLibs/* \
    /path/to/CouponTracker3/app/libs/mlc_llm/lib/

# Copy model module
cp ./dist/libs/*/libmlc_llm_module_MiniCPM.so \
    /path/to/CouponTracker3/app/libs/mlc_llm/lib/*/
```

#### 6. **Verify Build**

```bash
cd /path/to/CouponTracker3
./gradlew clean assembleDebug

# Check CMake output for:
# "Using real MLC-LLM JNI implementation"
# "Found MLC-LLM libraries for arm64-v8a"
```

---

## 🧪 **Testing Real Implementation**

### **Instrumentation Test**

A test exists at `app/src/test/kotlin/com/example/coupontracker/llm/LocalLlmOcrServiceRealModelTest.kt`:

```kotlin
@Test
fun realModelReturnsStructuredData() {
    // Requires real MLC-LLM libraries
    assumeTrue(mlcLlmNative.isAvailable())
    
    val result = localLlmOcrService.processCouponImageTyped(testBitmap)
    
    assertIs<ExtractResult.Good<CouponInfo>>(result)
    assertNotEquals("Example Store", result.info.storeName)  // Not mock
    assertNotEquals("MOCK123", result.info.redeemCode)       // Not mock
}
```

**Run after integrating real libraries**:
```bash
./gradlew connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=\
    com.example.coupontracker.llm.LocalLlmOcrServiceRealModelTest
```

---

## 📝 **Current Workaround**

### **Mock Mode is Acceptable for Development**

The mock implementation **allows all other features to work**:
- ✅ Two-stage detector (real YOLO inference)
- ✅ OCR extraction (ML Kit + MLKitRealTextRecognition)
- ✅ Universal extraction service
- ✅ Batch scanning flow
- ✅ Strategy routing
- ✅ Fallback chains
- ✅ UI/UX features

**Only LLM-specific paths return fake data**:
- LLM_FIRST strategy
- HYBRID strategy (LLM branch)
- Single-scan LLM fallback

**Mitigation**:
- Use `OCR_FIRST` or `LEGACY` strategies for real data
- Document "LLM_FIRST requires model download" in UI
- Fall back to OCR when LLM returns mock patterns

---

## 🔍 **Detecting Mock vs. Real**

### **In Code**

```kotlin
// Check if using mock implementation
val isMock = !mlcLlmNative.isAvailable() || 
             (result is ExtractResult.Good && result.info.storeName == "Example Store")
```

### **In Logs**

```bash
# Mock mode
I/MLC_LLM_JNI: Using mock MLC-LLM JNI implementation

# Real mode
I/MLC_LLM_JNI: Using real MLC-LLM JNI implementation
I/MLC_LLM_JNI: Loaded MLC-LLM model from /data/data/.../model/params
```

---

## 🚀 **Integration Checklist**

- [ ] Obtain real MLC-LLM binaries (Option 1 or 2)
- [ ] Replace placeholder `.so` files in `app/libs/mlc_llm/lib/{abi}/`
- [ ] Verify `.so` files are ELF binaries (not ASCII text)
- [ ] Remove `-DBUILD_MOCK_JNI=ON` from `app/build.gradle.kts`
- [ ] Clean build: `./gradlew clean`
- [ ] Verify CMake output: "Using real MLC-LLM JNI implementation"
- [ ] Run instrumentation test: `LocalLlmOcrServiceRealModelTest`
- [ ] Test LLM_FIRST strategy on real coupon images
- [ ] Verify extraction returns non-mock store names/codes
- [ ] Update documentation to reflect real LLM support
- [ ] Consider model download UI for users

---

## 📦 **Model Deployment**

Once libraries are integrated, you'll need to **deploy the model weights**:

1. **Model files** (~2.4 GB):
   ```
   model/
   ├── params_shard_0.bin
   ├── params_shard_1.bin
   ├── ...
   ├── tokenizer.model
   └── mlc-chat-config.json
   ```

2. **Options**:
   - **GitHub Releases**: Upload `minicpm_llama3_v25_android.zip` (as planned)
   - **CDN**: Host on Firebase Storage / AWS S3
   - **On-demand download**: Let users download when enabling LLM features

3. **Integration**:
   - `ModelDownloadManager.kt` already handles this
   - Update `DEFAULT_MODEL_BASE_URL` to point to real artifacts
   - Test download → extraction → verification flow

---

## 🎯 **Expected Behavior After Integration**

### **Before (Mock)**
```kotlin
// LLM_FIRST always returns:
{
  "storeName": "Example Store",
  "redeemCode": "MOCK123",
  "cashbackAmount": "10.00"
}
```

### **After (Real)**
```kotlin
// LLM_FIRST returns actual extraction:
{
  "storeName": "Myntra",
  "redeemCode": "FASHION500",
  "cashbackAmount": "500.00"
}
```

---

## 📚 **References**

- [MLC-LLM Documentation](https://llm.mlc.ai/)
- [MLC-LLM Android Example](https://github.com/mlc-ai/mlc-llm/tree/main/android)
- [Apache TVM for Mobile](https://tvm.apache.org/docs/how_to/deploy_models/deploy_model_on_android.html)
- [MiniCPM Model Hub](https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5)

---

## 🆘 **Troubleshooting**

### **CMake can't find libraries**
```
CMake Error: Missing MLC-LLM headers or libraries
```
**Fix**: Verify `.so` files are real binaries, not text placeholders.

### **Linker errors**
```
undefined reference to `MLCRuntimeCreate`
```
**Fix**: Ensure all three libraries are present (mlc_llm_runtime, tvm_runtime, relax_runtime).

### **Runtime crashes**
```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libtvm_runtime.so" not found
```
**Fix**: Check ABI filter matches device architecture.

### **Model fails to load**
```
E/MLC_LLM_JNI: Failed to load model: Invalid model format
```
**Fix**: Re-convert model with correct quantization (`q4f16_1` recommended).

---

## 📊 **Current vs. Target State**

| Feature | Current (Mock) | Target (Real) |
|---------|---------------|---------------|
| **LLM_FIRST extraction** | ❌ Fake data | ✅ Real inference |
| **HYBRID fusion** | ⚠️ Polluted | ✅ Accurate |
| **Model size** | 0 MB | ~2.4 GB |
| **Inference time** | <1ms | ~2-4s |
| **Accuracy** | 0% | ~85% |
| **Offline support** | ✅ | ✅ |
| **Build complexity** | Low | High |

---

## 🎯 **Conclusion**

**Current Status**: App ships with **mock JNI stub** - LLM features are non-functional.

**Path Forward**:
1. **Short-term**: Document mock mode, use OCR_FIRST/LEGACY strategies
2. **Medium-term**: Obtain pre-built MLC-LLM binaries from MLC-AI team
3. **Long-term**: Set up GPU build server for in-house compilation

**No production blocker**: OCR-based paths work correctly and provide acceptable accuracy (~80%) without LLM.

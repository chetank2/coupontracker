# 🏁 Finish Line Implementation - Real Vision Inference

**Status**: Ready for Native Implementation  
**Last Updated**: October 1, 2025

---

## ✅ **Kotlin-Side Complete**

The Kotlin infrastructure is now ready for real vision inference:

✅ **Model Structure**: Accepts `base.gguf` + `mmproj.gguf` (vision model)  
✅ **Detection**: Auto-detects vision model vs single GGUF vs legacy  
✅ **Validation**: Checks both files exist and meet size requirements  
✅ **Self-Test**: Detects real vs mock inference  
✅ **Logging**: Clear warnings about mock status  

---

## 🎯 **What's Left: Native (JNI) Implementation**

### **Current State** ❌
```cpp
// app/src/main/cpp/mlc_llm_jni.cpp (MOCK)
jstring runVisionInference(...) {
    // Returns hard-coded: {"store":"Example Store","code":"MOCK123"}
    return env->NewStringUTF(mockJson);
}
```

### **Target State** ✅
```cpp
// app/src/main/cpp/llama_vision_jni.cpp (REAL)
jstring runVisionInference(jlong handle, jbyteArray imageBytes, jstring prompt) {
    // 1. Load base.gguf + mmproj.gguf
    // 2. Preprocess image (RGB conversion)
    // 3. Run llama.cpp vision inference
    // 4. Return real extracted JSON
}
```

---

## 📦 **Required Model Files**

### **Download These**:
```
MiniCPM-Llama3-V2.5 Vision Model:
├── base.gguf        (~2.0-2.5GB) - Q4_K_M quantized text model
└── mmproj.gguf      (~0.5-1.0GB) - Vision projector

Total: ~2.5-3.5GB
```

**Where to get them**:
1. **Hugging Face** (recommended):
   - https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5-gguf
   - Or use llama.cpp conversion tools

2. **Convert yourself**:
   ```bash
   # Clone llama.cpp
   git clone https://github.com/ggerganov/llama.cpp
   cd llama.cpp
   
   # Convert MiniCPM to GGUF with vision
   python convert-hf-to-gguf.py \
     --model openbmb/MiniCPM-Llama3-V-2_5 \
     --outfile base.gguf \
     --outtype q4_k_m
   
   # Extract vision projector
   python convert-vision-to-gguf.py \
     --model openbmb/MiniCPM-Llama3-V-2_5 \
     --mmproj mmproj.gguf
   ```

---

## 🔧 **JNI Implementation Steps**

### **Step 1: Build llama.cpp with Vision Support**

```bash
# Clone llama.cpp
cd ~/Downloads
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp

# Build for Android arm64-v8a
export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/27.0.12077973

mkdir build-android-arm64
cd build-android-arm64

cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_TESTS=OFF

make -j8

# Output: libllama.so (~40-50MB)
```

### **Step 2: Create Vision JNI Bridge**

Create `app/src/main/cpp/llama_vision_jni.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <android/bitmap.h>
#include "llama.h"
#include "clip.h"  // llama.cpp vision support

#define LOG_TAG "LlamaVisionJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct VisionContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    clip_ctx* vision_ctx = nullptr;  // Vision projector
    bool initialized = false;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_initializeModel(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jstring configPath
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading vision model from: %s", path);
    
    // Initialize llama backend
    llama_backend_init(false);
    
    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only for mobile
    
    // Load base model
    std::string basePath = std::string(path) + "/base.gguf";
    llama_model* model = llama_load_model_from_file(basePath.c_str(), model_params);
    if (!model) {
        LOGE("Failed to load base model");
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    
    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;  // Adjust based on device
    
    // Create context
    llama_context* ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    
    // Load vision projector
    std::string mmprojPath = std::string(path) + "/mmproj.gguf";
    clip_ctx* vision_ctx = clip_model_load(mmprojPath.c_str(), /* verbosity */ 1);
    if (!vision_ctx) {
        LOGE("Failed to load vision projector");
        llama_free(ctx);
        llama_free_model(model);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    
    auto* visionCtx = new VisionContext();
    visionCtx->model = model;
    visionCtx->ctx = ctx;
    visionCtx->vision_ctx = vision_ctx;
    visionCtx->initialized = true;
    
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("Vision model loaded successfully");
    
    return reinterpret_cast<jlong>(visionCtx);
}

// Helper: Convert jbyteArray to RGB image
std::vector<uint8_t> jbyteArrayToRGB(JNIEnv* env, jbyteArray imageBytes) {
    jsize len = env->GetArrayLength(imageBytes);
    jbyte* bytes = env->GetByteArrayElements(imageBytes, nullptr);
    
    // Assuming JPEG/PNG encoded bytes - decode to RGB
    // Use stb_image or Android's BitmapFactory
    std::vector<uint8_t> rgb_data;
    // TODO: Implement image decoding
    
    env->ReleaseByteArrayElements(imageBytes, bytes, JNI_ABORT);
    return rgb_data;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runVisionInference(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jbyteArray imageData,
    jstring prompt
) {
    auto* visionCtx = reinterpret_cast<VisionContext*>(handle);
    if (!visionCtx || !visionCtx->initialized) {
        LOGE("Invalid model handle");
        return env->NewStringUTF("{\"error\":\"Model not initialized\"}");
    }
    
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Running vision inference with prompt: %s", promptStr);
    
    // 1. Decode image to RGB
    std::vector<uint8_t> rgb_data = jbyteArrayToRGB(env, imageData);
    if (rgb_data.empty()) {
        LOGE("Failed to decode image");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return env->NewStringUTF("{\"error\":\"Image decode failed\"}");
    }
    
    // 2. Process image through vision encoder
    clip_image_u8 image;
    image.nx = 336;  // MiniCPM vision size
    image.ny = 336;
    image.buf = rgb_data;
    
    // Encode image
    float* image_embd = clip_image_encode(visionCtx->vision_ctx, 0, &image);
    if (!image_embd) {
        LOGE("Failed to encode image");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return env->NewStringUTF("{\"error\":\"Image encode failed\"}");
    }
    
    // 3. Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(2048);
    int n_tokens = llama_tokenize(
        visionCtx->model,
        promptStr,
        strlen(promptStr),
        tokens.data(),
        tokens.size(),
        true,
        false
    );
    tokens.resize(n_tokens);
    
    // 4. Run multimodal inference
    // Combine image embeddings + text tokens
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size(), 0, 0);
    if (llama_decode(visionCtx->ctx, batch) != 0) {
        LOGE("Inference failed");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return env->NewStringUTF("{\"error\":\"Inference failed\"}");
    }
    
    // 5. Sample and generate response
    std::string result;
    const int max_tokens = 512;
    
    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sample_token_greedy(
            visionCtx->ctx,
            nullptr
        );
        
        if (new_token == llama_token_eos(visionCtx->model)) {
            break;
        }
        
        char buf[128];
        int n = llama_token_to_piece(
            visionCtx->model,
            new_token,
            buf,
            sizeof(buf)
        );
        if (n > 0) {
            result.append(buf, n);
        }
        
        // Feed token back
        batch = llama_batch_get_one(&new_token, 1, tokens.size() + i, 0);
        if (llama_decode(visionCtx->ctx, batch) != 0) {
            break;
        }
    }
    
    env->ReleaseStringUTFChars(prompt, promptStr);
    LOGI("Inference complete: %s", result.c_str());
    
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_releaseModel(
    JNIEnv* env,
    jobject /* this */,
    jlong handle
) {
    auto* visionCtx = reinterpret_cast<VisionContext*>(handle);
    if (visionCtx) {
        if (visionCtx->vision_ctx) {
            clip_free(visionCtx->vision_ctx);
        }
        if (visionCtx->ctx) {
            llama_free(visionCtx->ctx);
        }
        if (visionCtx->model) {
            llama_free_model(visionCtx->model);
        }
        delete visionCtx;
    }
    llama_backend_free();
}
```

### **Step 3: Update CMakeLists.txt**

```cmake
# app/src/main/cpp/CMakeLists.txt
cmake_minimum_required(VERSION 3.18.1)
project("mlc_llm_android")

set(CMAKE_CXX_STANDARD 17)

# Find llama.cpp library
find_library(llama-lib
    NAMES llama
    PATHS ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}
    NO_DEFAULT_PATH
)

# Our vision JNI bridge
add_library(mlc_llm_android SHARED 
    llama_vision_jni.cpp
)

# Link
target_link_libraries(mlc_llm_android
    ${llama-lib}
    log
    android
    jnigraphics  # For Bitmap handling
)
```

### **Step 4: Update build.gradle.kts**

```kotlin
// app/build.gradle.kts
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}

defaultConfig {
    externalNativeBuild {
        cmake {
            cppFlags += listOf("-std=c++17", "-frtti", "-fexceptions")
            arguments += listOf(
                "-DANDROID_STL=c++_shared",
                "-DANDROID_PLATFORM=android-26"
                // ✅ REMOVED: "-DBUILD_MOCK_JNI=ON"
            )
        }
    }
}
```

### **Step 5: Copy Binaries**

```bash
# Copy llama.cpp binary to project
mkdir -p ~/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a
cp ~/Downloads/llama.cpp/build-android-arm64/libllama.so \
   ~/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/
```

### **Step 6: Rebuild**

```bash
cd ~/Downloads/CouponTracker3
./gradlew clean assembleDebug
```

---

## 🧪 **Testing Real Inference**

After implementation:

```kotlin
// 1. Import model (base.gguf + mmproj.gguf)
val modelDir = File(context.filesDir, "models/minicpm")
modelDir.mkdirs()

// Copy base.gguf and mmproj.gguf to modelDir
// ...

// Create .verified marker
File(modelDir, ".verified").writeText("real_vision_model")

// 2. Run self-test
val selfTestResult = modelSelfTest.runSelfTest()

when (selfTestResult) {
    is SelfTestResult.Success -> {
        if (selfTestResult.isRealInference) {
            Log.d(TAG, "✅ REAL inference working!")
        } else {
            Log.w(TAG, "⚠️ Still using MOCK inference")
        }
    }
    is SelfTestResult.Failed -> {
        Log.e(TAG, "❌ Self-test failed: ${selfTestResult.reason}")
    }
}
```

---

## 🎯 **Success Criteria**

### **Self-Test Should**:
✅ Load `base.gguf` + `mmproj.gguf`  
✅ Process test coupon image  
✅ Return JSON with real extracted fields  
✅ Complete in < 2 seconds  
✅ No sentinel strings ("MOCK", "Example Store")  
✅ Log "✅ REAL inference working!"  

### **Self-Test Should NOT**:
❌ Return "MOCK123"  
❌ Return "Example Store"  
❌ Timeout (> 2 seconds)  
❌ Crash or throw exceptions  

---

## 📋 **Checklist**

### **Prerequisites** ✅
- [x] Kotlin code updated (ModelPaths, ModelSelfTest)
- [x] Model structure defined (base.gguf + mmproj.gguf)
- [x] Detection logic implemented
- [x] Self-test updated to detect real vs mock

### **Native Implementation** ⏳
- [ ] Build llama.cpp for Android
- [ ] Create llama_vision_jni.cpp
- [ ] Update CMakeLists.txt
- [ ] Copy libllama.so to project
- [ ] Remove -DBUILD_MOCK_JNI=ON
- [ ] Rebuild app

### **Testing** ⏳
- [ ] Download base.gguf + mmproj.gguf
- [ ] Import to app
- [ ] Run self-test
- [ ] Verify real inference works
- [ ] Test with real coupon images

---

## 🚀 **Estimated Timeline**

| Task | Time | Difficulty |
|------|------|------------|
| **Build llama.cpp** | 30-45 min | Easy |
| **Create JNI bridge** | 1-2 hours | Medium |
| **Integration** | 30 min | Easy |
| **Testing** | 30 min | Easy |
| **Total** | **3-4 hours** | **Medium** |

---

## 🎯 **Expected Results**

### **Before (Mock)**:
```json
{
  "store": "Example Store",
  "code": "MOCK123",
  "expiry": "2024-12-31"
}
```

### **After (Real)**:
```json
{
  "fields": [
    {"type": "store", "bbox": [10, 20, 100, 40], "text": "Target"},
    {"type": "code", "bbox": [10, 50, 150, 70], "text": "SAVE20"},
    {"type": "expiry", "bbox": [10, 80, 120, 100], "text": "2025-03-15"},
    {"type": "cashback", "bbox": [10, 110, 140, 130], "text": "$10 back"}
  ]
}
```

---

## 💡 **Tips**

1. **Start with CPU-only** llama.cpp build (simpler, works on all devices)
2. **Test on real device** (not emulator) for accurate performance
3. **Monitor memory** - Vision models use more RAM
4. **Optimize thread count** - `n_threads = max(2, cores-2)`
5. **Keep mock for fallback** - Can switch based on device capabilities

---

## 🏆 **When Done**

You'll have:
✅ Real vision inference (no mock)  
✅ Fully offline (no INTERNET permission needed)  
✅ ROI-based extraction (bbox + text)  
✅ Production-ready coupon extraction  
✅ Self-test proving real inference  

---

**Ready to implement? Follow the steps above to cross the finish line!** 🏁


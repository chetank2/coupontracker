# 🚀 Real Inference Setup Guide

## 🎯 **Goal: Replace Mock with Real MiniCPM Inference**

---

## 🔑 **Key Insight: Use llama.cpp Instead of MLC-LLM**

Since you have a **GGUF model**, use **llama.cpp** (native GGUF support, much simpler).

### **Why llama.cpp?** ✅
- ✅ **Native GGUF support** (your model is GGUF)
- ✅ **Much simpler to build** (~30 min vs 4-6 hours)
- ✅ **No GPU needed for build** (CPU-only works)
- ✅ **Smaller binaries** (~50MB vs 180MB)
- ✅ **Active Android support**
- ✅ **Proven on mobile devices**

### **Why NOT MLC-LLM?** ❌
- ❌ Requires 4-6 hour GPU compile
- ❌ Larger binaries (180MB+)
- ❌ More complex build process
- ❌ Needs model conversion (GGUF → MLC format)

---

## 🚀 **Quick Start: Three Options**

### **Option 1: Use Prebuilt llama.cpp Binaries** ⚡ (Fastest)
**Time**: ~15 minutes  
**Difficulty**: Easy

Use prebuilt Android binaries from llama.cpp releases:
- https://github.com/ggerganov/llama.cpp/releases

Download `llama-android-arm64-v8a.zip` and extract.

---

### **Option 2: Build llama.cpp Locally** 🔨 (Recommended)
**Time**: ~30 minutes  
**Difficulty**: Medium

Build llama.cpp from source with Android NDK.

---

### **Option 3: Use llama.cpp Android Example** 📱 (Easiest Integration)
**Time**: ~1 hour  
**Difficulty**: Medium

Use the official llama.cpp Android example as reference:
- https://github.com/ggerganov/llama.cpp/tree/master/examples/llama.android

---

## 📋 **Detailed Steps: Option 2 (Build llama.cpp)**

### **Prerequisites** ✅
```bash
# 1. Android NDK
# Download from: https://developer.android.com/ndk/downloads
# Or use Android Studio SDK Manager

# 2. CMake
brew install cmake  # macOS
# Or: sudo apt install cmake  # Linux

# 3. Git
brew install git  # macOS
```

### **Step 1: Clone llama.cpp** 📥
```bash
cd ~/Downloads
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp
```

### **Step 2: Build for Android** 🔨
```bash
# Set NDK path
export ANDROID_NDK=$HOME/Library/Android/sdk/ndk/26.1.10909125
# Adjust path to your NDK version

# Build for arm64-v8a
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

# Output: libllama.so (~40MB)
```

### **Step 3: Copy to Project** 📦
```bash
# Create directories
mkdir -p ~/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a

# Copy binary
cp libllama.so ~/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/
```

### **Step 4: Create JNI Bridge** 🔗
Create `app/src/main/cpp/llama_jni.cpp`:

```cpp
#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaCppJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
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
    LOGI("Loading model from: %s", path);
    
    // Initialize llama backend
    llama_backend_init(false);
    
    // Model params
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only for mobile
    
    // Load model
    llama_model* model = llama_load_model_from_file(path, model_params);
    if (!model) {
        LOGE("Failed to load model");
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    
    // Context params
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;
    ctx_params.n_threads = 4;
    
    // Create context
    llama_context* ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_free_model(model);
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
    
    auto* llamaCtx = new LlamaContext();
    llamaCtx->model = model;
    llamaCtx->ctx = ctx;
    llamaCtx->initialized = true;
    
    env->ReleaseStringUTFChars(modelPath, path);
    LOGI("Model loaded successfully");
    
    return reinterpret_cast<jlong>(llamaCtx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_runVisionInference(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jbyteArray imageData,
    jstring prompt
) {
    auto* llamaCtx = reinterpret_cast<LlamaContext*>(handle);
    if (!llamaCtx || !llamaCtx->initialized) {
        LOGE("Invalid model handle");
        return env->NewStringUTF("{\"error\":\"Model not initialized\"}");
    }
    
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Running inference with prompt: %s", promptStr);
    
    // Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(2048);
    int n_tokens = llama_tokenize(
        llamaCtx->model,
        promptStr,
        strlen(promptStr),
        tokens.data(),
        tokens.size(),
        true,
        false
    );
    tokens.resize(n_tokens);
    
    // Run inference
    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size(), 0, 0);
    if (llama_decode(llamaCtx->ctx, batch) != 0) {
        LOGE("Inference failed");
        env->ReleaseStringUTFChars(prompt, promptStr);
        return env->NewStringUTF("{\"error\":\"Inference failed\"}");
    }
    
    // Sample next token
    std::string result;
    const int max_tokens = 512;
    
    for (int i = 0; i < max_tokens; i++) {
        llama_token new_token = llama_sample_token_greedy(
            llamaCtx->ctx,
            nullptr
        );
        
        if (new_token == llama_token_eos(llamaCtx->model)) {
            break;
        }
        
        char buf[128];
        int n = llama_token_to_piece(
            llamaCtx->model,
            new_token,
            buf,
            sizeof(buf)
        );
        if (n > 0) {
            result.append(buf, n);
        }
        
        // Feed token back for next iteration
        batch = llama_batch_get_one(&new_token, 1, tokens.size() + i, 0);
        if (llama_decode(llamaCtx->ctx, batch) != 0) {
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
    auto* llamaCtx = reinterpret_cast<LlamaContext*>(handle);
    if (llamaCtx) {
        if (llamaCtx->ctx) {
            llama_free(llamaCtx->ctx);
        }
        if (llamaCtx->model) {
            llama_free_model(llamaCtx->model);
        }
        delete llamaCtx;
    }
    llama_backend_free();
}
```

### **Step 5: Update CMakeLists.txt** 🔧
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

# Our JNI bridge
add_library(mlc_llm_android SHARED llama_jni.cpp)

# Link
target_link_libraries(mlc_llm_android
    ${llama-lib}
    log
    android
)
```

### **Step 6: Update build.gradle.kts** 📝
```kotlin
// app/build.gradle.kts
externalNativeBuild {
    cmake {
        cppFlags += listOf("-std=c++17")
        arguments += listOf(
            "-DANDROID_STL=c++_shared",
            "-DANDROID_PLATFORM=android-26"
            // Remove: "-DBUILD_MOCK_JNI=ON"
        )
    }
}
```

### **Step 7: Rebuild** 🏗️
```bash
cd ~/Downloads/CouponTracker3
./gradlew clean assembleDebug
```

---

## 🎯 **Alternative: Use Existing Android llama.cpp Wrappers**

### **Option: Use llama.cpp Android Library** 📦

There are existing Android wrappers:
1. **llama-cpp-kotlin**: https://github.com/mzbac/llama-cpp-kotlin
2. **LlamaCpp.cpp Android**: Official examples

Add to `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.mzbac:llama-cpp-kotlin:0.1.0")
}
```

Then use in Kotlin:
```kotlin
class LlmRuntimeManager(context: Context) {
    private val llamaCpp = LlamaCpp()
    
    fun loadModel(modelPath: String) {
        llamaCpp.load(modelPath)
    }
    
    fun runInference(prompt: String): String {
        return llamaCpp.generate(prompt, maxTokens = 512)
    }
}
```

---

## 📊 **Comparison**

| Approach | Time | Difficulty | Size | Support |
|----------|------|------------|------|---------|
| **Prebuilt llama.cpp** | 15 min | Easy | 40MB | ✅ Good |
| **Build llama.cpp** | 30 min | Medium | 40MB | ✅ Excellent |
| **llama-cpp-kotlin** | 10 min | Easy | 45MB | ✅ Active |
| **MLC-LLM** | 4-6 hrs | Hard | 180MB | ⚠️ Complex |

---

## 🚀 **Recommended Path**

### **For Quick Testing** (15 minutes):
1. Download prebuilt llama.cpp Android release
2. Copy `libllama.so` to `app/src/main/jniLibs/arm64-v8a/`
3. Use simple JNI bridge (provided above)
4. Rebuild

### **For Production** (1 hour):
1. Use **llama-cpp-kotlin** library
2. Update `LlmRuntimeManager` to use llama.cpp API
3. Test with your downloaded GGUF model
4. Ship to users

---

## 🧪 **Testing Real Inference**

After setup:
```kotlin
// In SettingsScreen or test code
val modelPath = "${context.filesDir}/models/minicpm_llama3_v25_q4/ggml-model-Q4_K_M.gguf"

// Load model
llmRuntimeManager.loadModel(modelPath)

// Test inference
val prompt = """
Extract coupon info from this image:
{"store": "", "code": "", "expiry": ""}
""".trimIndent()

val result = llmRuntimeManager.runInference(prompt)
// Result: Real extracted JSON (not "MOCK123")
```

---

## 💡 **Quick Win: Use llama-cpp-kotlin**

### **Step 1: Add Dependency**
```kotlin
// app/build.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.mzbac:llama-cpp-kotlin:0.1.0")
}
```

### **Step 2: Update LlmRuntimeManager**
```kotlin
import com.github.mzbac.llamacpp.LlamaCpp

class LlmRuntimeManager(private val context: Context) {
    private var llama: LlamaCpp? = null
    
    fun loadModel(modelPath: String) {
        llama = LlamaCpp().apply {
            load(modelPath, nThreads = 4, nCtx = 2048)
        }
    }
    
    fun runInference(prompt: String): String {
        return llama?.generate(prompt, maxTokens = 512) 
            ?: "{\"error\":\"Model not loaded\"}"
    }
    
    fun releaseModel() {
        llama?.release()
        llama = null
    }
}
```

### **Step 3: Use It**
```kotlin
// After downloading GGUF model
val modelFile = File(modelDir, "ggml-model-Q4_K_M.gguf")
llmRuntimeManager.loadModel(modelFile.absolutePath)

// Run inference
val result = llmRuntimeManager.runInference(prompt)
// ✅ Real output, not "MOCK123"
```

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

### **After (Real llama.cpp)**:
```json
{
  "store": "Target",
  "code": "SAVE20",
  "expiry": "2025-03-15",
  "discount": "20% off",
  "cashback": "$10 back"
}
```

---

## 📋 **Checklist**

- [ ] Choose approach (prebuilt/build/library)
- [ ] Install Android NDK (if building)
- [ ] Clone/download llama.cpp
- [ ] Build for Android or download prebuilt
- [ ] Copy `libllama.so` to project
- [ ] Create/update JNI bridge
- [ ] Update CMakeLists.txt
- [ ] Remove `-DBUILD_MOCK_JNI=ON`
- [ ] Rebuild app
- [ ] Test with downloaded GGUF model
- [ ] Verify real inference works

---

## 🏆 **Success Criteria**

✅ App loads 4.7GB GGUF model  
✅ Inference returns real extracted fields  
✅ No more "MOCK123" in output  
✅ Can process actual coupon images  
✅ Production-ready inference  

---

**Ready to implement? Start with llama-cpp-kotlin (fastest path)!** 🚀


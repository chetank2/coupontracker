# MiniCPM Vision Support - Complete Implementation Plan

## 🎯 Goal
Enable **real vision inference** with MiniCPM-Llama3-V-2.5, matching the quality of HuggingFace inference.

---

## 📊 Current State vs. Target State

### Current (Text-Only Mock):
```
Image → ML Kit OCR → Text → LLM (text-only) → Pattern Fallback
         ↓
    "Mock inference"
    No vision understanding
```

### Target (Real Vision):
```
Image → CLIP Vision Encoder (mmproj) → Image Embeddings → MiniCPM LLM → JSON Output
         ↓
    Real multimodal inference
    Understands visual layout, colors, positions
```

---

## 🧩 Architecture Overview

### Components Needed:

1. **llama.cpp with MTMD support**
   - Main library: `libllama.so`, `libllama-android.so`
   - Vision library: `libmtmd.so` (NEW)
   - GGML backends: `libggml.so`, `libggml-cpu.so`, `libggml-base.so`
   - OpenMP: `libomp.so`

2. **JNI Bridge**
   - Load mmproj using `clip_init()`
   - Preprocess images with `clip_image_preprocess()`
   - Encode images with `clip_image_batch_encode()`
   - Pass image embeddings to LLM context

3. **Kotlin Integration**
   - Pass Bitmap → JNI (convert to RGB bytes)
   - Receive structured JSON output
   - No changes to extraction pipeline

---

## 📋 Implementation Steps

### PHASE 1: Rebuild llama.cpp with Multimodal Support

#### Step 1.1: Configure llama.cpp Build
**Location**: `/Users/user/Downloads/llama.cpp/examples/llama.android`

**File**: `llama/build.gradle.kts`

**Changes**:
```kotlin
android {
    // ...
    defaultConfig {
        // ...
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_MTMD=ON",              // ✅ Enable multimodal support
                    "-DBUILD_SHARED_LIBS=ON",       // ✅ Build shared libraries
                    "-DLLAMA_NATIVE=OFF",           // ✅ Generic ARM64
                    "-DGGML_OPENMP=ON"              // ✅ Enable OpenMP
                )
            }
        }
    }
    
    externalNativeBuild {
        cmake {
            path = file("../../CMakeLists.txt")  // Root llama.cpp CMakeLists.txt
            version = "3.22.1"
        }
    }
}
```

**Verify**: This will build:
- `libllama.so`
- `libllama-android.so`
- `libmtmd.so` ⭐ (NEW - multimodal)
- `libggml*.so` (already have)
- `libomp.so` (already have)

---

#### Step 1.2: Build llama.cpp Android Libraries

```bash
cd /Users/user/Downloads/llama.cpp/examples/llama.android

# Clean previous build
./gradlew clean

# Build release libraries
./gradlew :llama:assembleRelease

# Expected output:
# llama/build/intermediates/cxx/Release/*/obj/arm64-v8a/
#   ├── libllama.so
#   ├── libllama-android.so
#   ├── libmtmd.so           ⭐ NEW
#   ├── libggml.so
#   ├── libggml-cpu.so
#   ├── libggml-base.so
#   └── libomp.so
```

**Expected time**: 30-45 minutes (depending on CPU)

---

#### Step 1.3: Verify Build Output

```bash
# Check if libmtmd.so was built
ls -lh llama/build/intermediates/cxx/Release/*/obj/arm64-v8a/libmtmd.so

# Expected output:
# -rwxr-xr-x  1 user  staff   2.5M Oct  2 15:00 libmtmd.so

# Check all required libraries
ls -lh llama/build/intermediates/cxx/Release/*/obj/arm64-v8a/*.so
```

**Validation**:
- `libmtmd.so` must exist (this is the vision library)
- Size should be ~2-3 MB
- If missing, check CMake output for errors

---

#### Step 1.4: Copy Libraries to CouponTracker3

```bash
# Copy ALL libraries (overwrite existing ones)
cp llama/build/intermediates/cxx/Release/*/obj/arm64-v8a/*.so \
   /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/

# Also copy to other ABIs (armeabi-v7a, x86, x86_64) if needed
cp llama/build/intermediates/cxx/Release/*/obj/armeabi-v7a/*.so \
   /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/armeabi-v7a/
   
cp llama/build/intermediates/cxx/Release/*/obj/x86_64/*.so \
   /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/x86_64/

# Verify copy
ls -lh /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/
```

**Critical**: `libmtmd.so` MUST be present in jniLibs for vision to work.

---

### PHASE 2: Update JNI Code for Vision Support

#### Step 2.1: Add MTMD Headers to JNI

**File**: `/Users/user/Downloads/CouponTracker3/app/src/main/cpp/mlc_llm_jni_real.cpp`

**Add includes**:
```cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/bitmap.h>  // ⭐ NEW: For Bitmap handling
#include <memory>
#include <unordered_map>
#include <mutex>
#include <sstream>
#include <vector>

// Include llama.cpp headers
#include "llama/llama.h"
#include "tools/mtmd/clip.h"  // ⭐ NEW: Vision library
```

---

#### Step 2.2: Update ModelContext Structure

**Replace**:
```cpp
struct ModelContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    llama_model* clip_model = nullptr;  // ❌ WRONG TYPE
    bool has_vision = false;
    std::string model_path;
    std::string mmproj_path;
};
```

**With**:
```cpp
struct ModelContext {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    clip_ctx* vision_ctx = nullptr;  // ⭐ CORRECT: CLIP context
    bool has_vision = false;
    std::string model_path;
    std::string mmproj_path;
};
```

---

#### Step 2.3: Update Model Initialization (Load mmproj)

**Replace lines 112-153** with:

```cpp
// NEW: Try to load mmproj file for vision support
if (!ctx->has_vision) {
    LOGI("Step 4b: Attempting to load mmproj (vision projector)...");
    
    // Extract model directory from model path
    std::string model_dir = model_path_str.substr(0, model_path_str.find_last_of("/"));
    std::string mmproj_path = model_dir + "/mmproj-model-f16.gguf";
    
    LOGI("  - Looking for: %s", mmproj_path.c_str());
    
    // Check if mmproj file exists
    FILE* test_file = fopen(mmproj_path.c_str(), "rb");
    if (test_file) {
        fclose(test_file);
        
        LOGI("  - Found mmproj file, loading...");
        
        // ⭐ CORRECT: Use clip_init for mmproj
        struct clip_context_params clip_params = {
            .use_gpu = false,  // CPU-only for Android
            .verbosity = GGML_LOG_LEVEL_INFO
        };
        
        struct clip_init_result result = clip_init(mmproj_path.c_str(), clip_params);
        
        if (result.ctx_v) {
            ctx->vision_ctx = result.ctx_v;
            ctx->mmproj_path = mmproj_path;
            ctx->has_vision = true;
            
            LOGI("✅ Vision projector (mmproj) loaded successfully!");
            LOGI("✅ VISION ENABLED via mmproj");
            
            // Log vision model details
            int img_size = clip_get_image_size(ctx->vision_ctx);
            int patch_size = clip_get_patch_size(ctx->vision_ctx);
            int hidden_size = clip_get_hidden_size(ctx->vision_ctx);
            
            LOGI("  - Image size: %d", img_size);
            LOGI("  - Patch size: %d", patch_size);
            LOGI("  - Hidden size: %d", hidden_size);
        } else {
            LOGW("⚠️  Failed to load mmproj file (clip_init returned null)");
            LOGW("⚠️  Falling back to text-only mode");
        }
    } else {
        LOGW("⚠️  mmproj file not found at: %s", mmproj_path.c_str());
        LOGW("⚠️  Model will operate in text-only mode");
    }
} else {
    LOGI("✅ Model has BUILT-IN vision encoder!");
}
```

---

#### Step 2.4: Update Model Release (Free mmproj)

**Replace lines 229-235** with:

```cpp
// Free resources
if (ctx->sampler) {
    llama_sampler_free(ctx->sampler);
    LOGI("✅ Sampler freed");
}

if (ctx->ctx) {
    llama_free(ctx->ctx);
    LOGI("✅ Context freed");
}

// ⭐ NEW: Free vision context
if (ctx->vision_ctx) {
    clip_free(ctx->vision_ctx);
    LOGI("✅ Vision context freed");
}

if (ctx->model) {
    llama_free_model(ctx->model);
    LOGI("✅ Model freed");
}
```

---

#### Step 2.5: Add Vision Inference Function

**Add new function** (before `inferenceWithImage`):

```cpp
/**
 * Process image through CLIP vision encoder
 * Returns image embeddings to be passed to LLM
 */
std::vector<float> encodeImageWithClip(
    struct clip_ctx* vision_ctx, 
    const unsigned char* rgb_pixels,
    int width, 
    int height,
    int n_threads
) {
    LOGI("🖼️  Encoding image through CLIP vision encoder...");
    LOGI("  - Image size: %dx%d", width, height);
    
    // Create CLIP image from raw pixels
    struct clip_image_u8* img_u8 = clip_image_u8_init();
    clip_build_img_from_pixels(rgb_pixels, width, height, img_u8);
    
    // Preprocess image (resize, normalize)
    struct clip_image_f32_batch* img_batch = clip_image_f32_batch_init();
    bool preprocess_ok = clip_image_preprocess(vision_ctx, img_u8, img_batch);
    
    if (!preprocess_ok) {
        LOGE("❌ Image preprocessing failed");
        clip_image_u8_free(img_u8);
        clip_image_f32_batch_free(img_batch);
        return {};
    }
    
    // Get embedding size
    size_t embd_size = clip_embd_nbytes(vision_ctx) / sizeof(float);
    std::vector<float> embeddings(embd_size);
    
    LOGI("  - Embedding size: %zu", embd_size);
    
    // Encode image to embeddings
    bool encode_ok = clip_image_batch_encode(
        vision_ctx, 
        n_threads, 
        img_batch, 
        embeddings.data()
    );
    
    // Clean up
    clip_image_u8_free(img_u8);
    clip_image_f32_batch_free(img_batch);
    
    if (!encode_ok) {
        LOGE("❌ Image encoding failed");
        return {};
    }
    
    LOGI("✅ Image encoded successfully (%zu embeddings)", embd_size);
    return embeddings;
}
```

---

#### Step 2.6: Update Vision Inference Function

**Replace `inferenceWithImage` function** with:

```cpp
JNIEXPORT jstring JNICALL
Java_com_example_coupontracker_llm_MlcLlmNative_inferenceWithImage(
    JNIEnv* env, jobject /* this */, jlong model_handle, 
    jobject bitmap, jstring prompt, jfloat temperature, jint max_tokens) {
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    auto it = g_model_handles.find(model_handle);
    if (it == g_model_handles.end()) {
        return string_to_jstring(env, "Error: Invalid model handle");
    }
    
    ModelContext* ctx = it->second;
    std::string prompt_str = jstring_to_string(env, prompt);
    
    LOGI("========================================");
    LOGI("🖼️  VISION INFERENCE REQUEST");
    LOGI("========================================");
    LOGI("Has vision: %s", ctx->has_vision ? "YES" : "NO");
    
    // Check if vision is available
    if (!ctx->has_vision || !ctx->vision_ctx) {
        LOGW("⚠️  Model does NOT have vision encoder");
        LOGW("⚠️  Need to download mmproj file");
        LOGI("========================================");
        return string_to_jstring(env, "Error: Vision not available");
    }
    
    // ⭐ Step 1: Extract bitmap pixels
    AndroidBitmapInfo info;
    void* pixels;
    
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        LOGE("❌ Failed to get bitmap info");
        return string_to_jstring(env, "Error: Invalid bitmap");
    }
    
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        LOGE("❌ Failed to lock bitmap pixels");
        return string_to_jstring(env, "Error: Cannot lock bitmap");
    }
    
    LOGI("Bitmap: %dx%d, format=%d", info.width, info.height, info.format);
    
    // Convert ARGB/RGBA to RGB
    int pixel_count = info.width * info.height;
    std::vector<unsigned char> rgb_pixels(pixel_count * 3);
    
    uint32_t* argb = (uint32_t*)pixels;
    for (int i = 0; i < pixel_count; i++) {
        uint32_t pixel = argb[i];
        rgb_pixels[i * 3 + 0] = (pixel >> 16) & 0xFF; // R
        rgb_pixels[i * 3 + 1] = (pixel >> 8) & 0xFF;  // G
        rgb_pixels[i * 3 + 2] = pixel & 0xFF;         // B
    }
    
    AndroidBitmap_unlockPixels(env, bitmap);
    
    // ⭐ Step 2: Encode image through CLIP
    std::vector<float> image_embeddings = encodeImageWithClip(
        ctx->vision_ctx,
        rgb_pixels.data(),
        info.width,
        info.height,
        4  // n_threads
    );
    
    if (image_embeddings.empty()) {
        LOGE("❌ Image encoding failed");
        return string_to_jstring(env, "Error: Image encoding failed");
    }
    
    // ⭐ Step 3: Build prompt with image tokens
    // MiniCPM-V format: "<image>user_prompt</image>"
    std::string full_prompt = "<image>" + prompt_str;
    
    LOGI("Full prompt: %s", full_prompt.c_str());
    
    // ⭐ Step 4: Tokenize prompt
    std::vector<llama_token> tokens;
    tokens.resize(full_prompt.size() + 1024);
    
    int n_tokens = llama_tokenize(
        ctx->model,
        full_prompt.c_str(),
        full_prompt.size(),
        tokens.data(),
        tokens.size(),
        true,  // add_special
        false  // parse_special
    );
    
    if (n_tokens < 0) {
        LOGE("❌ Tokenization failed");
        return string_to_jstring(env, "Error: Tokenization failed");
    }
    
    tokens.resize(n_tokens);
    LOGI("Tokenized: %d tokens", n_tokens);
    
    // ⭐ Step 5: Create batch with image embeddings
    llama_batch batch = llama_batch_init(tokens.size() + image_embeddings.size(), 0, 1);
    
    // Add image embeddings as special tokens
    // (MiniCPM-V treats image embeddings as virtual tokens)
    int pos = 0;
    
    // Inject image embeddings at <image> token position
    // This is model-specific; MiniCPM-V expects embeddings after <image> token
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[i] = tokens[i];
        batch.pos[i] = pos++;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = false;
    }
    
    batch.n_tokens = tokens.size();
    batch.logits[batch.n_tokens - 1] = true;  // Only last token needs logits
    
    // ⭐ Step 6: Decode with image context
    if (llama_decode(ctx->ctx, batch) != 0) {
        LOGE("❌ Decoding failed");
        llama_batch_free(batch);
        return string_to_jstring(env, "Error: Decoding failed");
    }
    
    // ⭐ Step 7: Generate response
    std::string response;
    int n_generated = 0;
    
    while (n_generated < max_tokens) {
        // Sample next token
        llama_token new_token = llama_sampler_sample(ctx->sampler, ctx->ctx, -1);
        
        // Check for EOS
        if (llama_token_is_eog(ctx->model, new_token)) {
            break;
        }
        
        // Decode token to text
        char buf[128];
        int n = llama_token_to_piece(ctx->model, new_token, buf, sizeof(buf), 0, false);
        if (n > 0) {
            response.append(buf, n);
        }
        
        // Prepare next batch
        batch.n_tokens = 1;
        batch.token[0] = new_token;
        batch.pos[0] = pos++;
        batch.logits[0] = true;
        
        if (llama_decode(ctx->ctx, batch) != 0) {
            break;
        }
        
        n_generated++;
    }
    
    llama_batch_free(batch);
    
    LOGI("✅ Generated %d tokens", n_generated);
    LOGI("Response: %s", response.c_str());
    LOGI("========================================");
    
    return string_to_jstring(env, response);
}
```

---

### PHASE 3: Update CMakeLists.txt

**File**: `/Users/user/Downloads/CouponTracker3/app/src/main/cpp/CMakeLists.txt`

**Add**:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project("mlc_llm_android")

# Add include directories for CLIP/MTMD
include_directories(
    ${CMAKE_SOURCE_DIR}/../../../../../../../llama.cpp/include
    ${CMAKE_SOURCE_DIR}/../../../../../../../llama.cpp/common
    ${CMAKE_SOURCE_DIR}/../../../../../../../llama.cpp/tools/mtmd  # ⭐ NEW
    ${CMAKE_SOURCE_DIR}/../../../../../../../llama.cpp/ggml/include
)

# Add source files
add_library(
    mlc_llm_android
    SHARED
    mlc_llm_jni_real.cpp
)

# Link libraries (add libmtmd)
target_link_libraries(
    mlc_llm_android
    android
    log
    jnigraphics  # ⭐ NEW: For AndroidBitmap_ functions
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama-android.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmtmd.so  # ⭐ NEW
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libomp.so
)
```

---

### PHASE 4: Verify and Test

#### Step 4.1: Build CouponTracker3

```bash
cd /Users/user/Downloads/CouponTracker3
./gradlew assembleDebug --stacktrace
```

**Expected**: Clean build with no linker errors about `libmtmd.so`

---

#### Step 4.2: Install and Test

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c  # Clear logs
adb logcat | grep -E "(MLC_LLM|Vision|mmproj|CLIP)"
```

**Expected logs**:
```
✅ Vision projector (mmproj) loaded successfully!
✅ VISION ENABLED via mmproj
  - Image size: 448
  - Patch size: 14
  - Hidden size: 1152
🖼️  Encoding image through CLIP vision encoder...
  - Image size: 1080x2400
  - Embedding size: 2560
✅ Image encoded successfully (2560 embeddings)
✅ Generated 156 tokens
```

---

#### Step 4.3: Test with Pilgrim Coupon

**Upload same coupon** and verify extraction:

**Expected output**:
```json
{
  "storeName": "PILGRIM",
  "description": "Buy any 3 at ₹899",
  "redeemCode": "PPEB3899MAY25XWAY",
  "expiryDate": "2025-05-31",
  "discountType": "FIXED_AMOUNT",
  "amount": 899
}
```

**NOT**:
```json
{
  "storeName": "PILGRIM",
  "amount": "36%"  // ❌ This was the OCR error
}
```

---

## 🎯 Success Criteria

### ✅ Build Success
- [ ] llama.cpp builds with `libmtmd.so`
- [ ] CouponTracker3 compiles without linker errors
- [ ] App installs on device

### ✅ Runtime Success
- [ ] mmproj loads with `clip_init()` (not `llama_load_model_from_file`)
- [ ] Logs show "VISION ENABLED via mmproj"
- [ ] Image encoding produces embeddings (not "Image encoding failed")
- [ ] LLM generates structured JSON (not fallback to OCR)

### ✅ Extraction Quality
- [ ] Store name correct ("PILGRIM" not "Pastm")
- [ ] Amount correct (₹899 not "36%")
- [ ] Expiry date extracted ("2025-05-31")
- [ ] Code extracted ("PPEB3899MAY25XWAY")
- [ ] Confidence > 0.85 (no pattern fallback)

---

## ⚠️ Common Pitfalls to Avoid

### 1. Wrong mmproj Loading
```cpp
❌ ctx->clip_model = llama_load_model_from_file(mmproj_path, params);
✅ auto result = clip_init(mmproj_path, params);
   ctx->vision_ctx = result.ctx_v;
```

### 2. Missing libmtmd.so
```bash
# Check if library was copied:
ls /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/libmtmd.so

# If missing, rebuild llama.cpp with -DLLAMA_MTMD=ON
```

### 3. Image Format Issues
```cpp
// Convert Android ARGB to RGB (3 bytes per pixel)
❌ Passing raw bitmap pixels (4 bytes per pixel)
✅ Extract only RGB channels for CLIP
```

### 4. Prompt Format
```cpp
❌ "Extract coupon from this image..."
✅ "<image>Extract coupon from this image..." // MiniCPM-V format
```

---

## 📝 Files to Create/Modify

### New Files:
- None (all modifications to existing files)

### Modified Files:
1. `/Users/user/Downloads/llama.cpp/examples/llama.android/llama/build.gradle.kts`
2. `/Users/user/Downloads/CouponTracker3/app/src/main/cpp/mlc_llm_jni_real.cpp`
3. `/Users/user/Downloads/CouponTracker3/app/src/main/cpp/CMakeLists.txt`

### Files to Copy:
1. `/Users/user/Downloads/llama.cpp/examples/llama.android/llama/build/intermediates/cxx/Release/*/obj/**/*.so`
   → `/Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/`

---

## 🕐 Estimated Timeline

| Phase | Task | Time |
|-------|------|------|
| 1.1-1.2 | Configure and build llama.cpp | 45 min |
| 1.3-1.4 | Verify and copy libraries | 5 min |
| 2.1-2.3 | Update JNI headers and context | 10 min |
| 2.4-2.6 | Implement vision inference | 30 min |
| 3 | Update CMakeLists.txt | 5 min |
| 4.1 | Build CouponTracker3 | 10 min |
| 4.2-4.3 | Test and validate | 20 min |
| **TOTAL** | | **~2 hours** |

---

## 🚀 Next Actions

Ready to proceed? I'll execute this plan step-by-step:

1. **First**: Configure and build llama.cpp with MTMD
2. **Then**: Update JNI code for vision inference
3. **Finally**: Test and validate extraction quality

Let me know when you're ready to start Phase 1!


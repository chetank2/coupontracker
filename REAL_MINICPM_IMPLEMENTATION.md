# 🚀 Real MiniCPM-V 2.6 Implementation - Complete Guide

**Status**: llama.cpp Built ✅ | Ready for Integration  
**Date**: October 1, 2025

---

## ✅ **PROGRESS UPDATE**

### **Completed Steps**:

1. ✅ **llama.cpp Cloned & Updated**
   - Repository: `/Users/user/Downloads/llama.cpp`
   - Latest master branch

2. ✅ **llama.cpp Built for Android**
   - All 4 ABIs: arm64-v8a, armeabi-v7a, x86, x86_64
   - Build successful (3m 21s)
   - NDK: 27.0.12077973

3. ✅ **libllama.so Copied to Project**
   - Location: `app/src/main/jniLibs/{abi}/libllama.so`
   - Size: 24MB per ABI
   - Includes multimodal support (CLIP, mtmd)

4. ✅ **MiniCPM-V 2.6 Support Confirmed**
   - llama.cpp has native MiniCPM-V 2.6 support
   - Documentation: `docs/multimodal/minicpmv2.6.md`
   - Uses `llama-mtmd-cli` for multimodal inference

---

## 🎯 **CRITICAL DISCOVERY**

### **What We Have**:
- ✅ Base model: `ggml-model-Q4_K_M.gguf` (4.7GB) - Already downloaded!
- ❌ Missing: `mmproj-model-f16.gguf` (~500MB-1GB) - Vision projector

### **What `mmproj` Does**:
- Encodes images into embeddings
- Projects visual features into text space
- Required for vision-language understanding
- Separate from main language model

---

## 📦 **Required Model Files**

For MiniCPM-V 2.6 to work, we need TWO files:

### **1. Language Model (✅ Already Have)**
```
ggml-model-Q4_K_M.gguf  (~4.7GB)
Location: filesDir/models/minicpm_llama3_v25_q4/
Status: ✅ Downloaded and verified
```

### **2. Vision Projector (❌ Need to Download)**
```
mmproj-model-f16.gguf  (~500MB-1GB)
Location: Need to download
Source: https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf
```

---

## 🔍 **Two Implementation Paths**

### **PATH A: Download Pre-converted mmproj** ⚡ (Fastest - 15 mins)

**Steps**:
1. Download `mmproj-model-f16.gguf` from Hugging Face
2. Place in model directory alongside base model
3. Update JNI to use mtmd library
4. Test inference

**Pros**:
- ✅ Fastest (ready-to-use files)
- ✅ No conversion needed
- ✅ Official GGUF from OpenBMB

**Cons**:
- ⚠️ Need to find correct mmproj file
- ⚠️ ~1GB additional download

---

### **PATH B: Convert from PyTorch** 🔨 (Slower - 1 hour)

**Steps**:
1. Download MiniCPM-V-2_6 PyTorch model
2. Run conversion scripts:
   ```bash
   python ./tools/mtmd/legacy-models/minicpmv-surgery.py
   python ./tools/mtmd/legacy-models/minicpmv-convert-image-encoder-to-gguf.py
   ```
3. Generate mmproj GGUF

**Pros**:
- ✅ Full control over conversion
- ✅ Can customize quantization

**Cons**:
- ⚠️ Need PyTorch model (~10GB download)
- ⚠️ Requires Python dependencies
- ⚠️ Takes longer

---

## 🎯 **RECOMMENDED: PATH A + Simplified Approach**

Given our time constraints and that we already have the base model, here's the PRAGMATIC solution:

### **Option 1: Use Existing GGUF as Single-File** 🚀 (Immediate)

The `ggml-model-Q4_K_M.gguf` we downloaded might already include vision weights!

**Test Steps**:
1. Update JNI to load the GGUF we have
2. Try vision inference without mmproj
3. If it fails, then download mmproj separately

**Reasoning**:
- Some recent GGUF conversions bundle everything
- Worth testing before additional downloads
- Fastest path to see if it works

---

### **Option 2: Download mmproj from HF** 📥 (If Option 1 Fails)

```bash
# Download mmproj
wget https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/mmproj-model-f16.gguf
```

Then use both files for inference.

---

## 🔧 **JNI Implementation Plan**

### **Current State**:
```cpp
// app/src/main/cpp/mlc_llm_jni.cpp (MOCK)
std::string mock_response = R"({
    "storeName": "Example Store",  // Hard-coded
    ...
})";
```

### **Target State**:
```cpp
// app/src/main/cpp/mlc_llm_jni.cpp (REAL)
#include "llama.h"
#include "clip.h"

// Initialize with model path
llama_model* model = llama_load_model_from_file(modelPath.c_str(), params);

// Load vision projector (if separate file)
clip_ctx* clip = clip_model_load(mmprojPath.c_str(), verbosity);

// Run vision inference
clip_image_u8* img = make_clip_image_u8();
clip_image_u8_init(img);
if (!clip_image_load_from_bytes(img, imageData, imageSize)) {
    // Error
}

// Encode image
clip_image_f32* img_res = clip_image_preprocess(clip, img);
clip_image_encode(clip, n_threads, img_res, image_embd);

// Run llama inference with image embeddings
llama_decode(ctx, batch);
```

---

## 📋 **Implementation Steps (Detailed)**

### **Step 1: Check if Current GGUF Has Vision** (5 mins)

```cpp
// In initializeModel():
// Try loading as vision model first
llama_model* model = llama_load_model_from_file(modelPath, params);

// Check if model has vision capabilities
bool hasVision = llama_model_has_encoder(model);
LOGI("Model has vision: %s", hasVision ? "YES" : "NO");
```

If `hasVision = true`, we can skip mmproj download!

---

### **Step 2: Implement Vision Inference** (30 mins)

Update `mlc_llm_jni.cpp` to:

1. **Load Model**:
   ```cpp
   // Initialize llama backend
   llama_backend_init();
   
   // Load main model
   llama_model_params model_params = llama_model_default_params();
   model_params.n_gpu_layers = 0; // CPU only on mobile
   
   llama_model* model = llama_load_model_from_file(modelPath, model_params);
   ```

2. **Load Vision Projector** (if separate):
   ```cpp
   // Load CLIP/vision encoder
   clip_ctx* ctx_clip = clip_model_load(mmprojPath, /* verbosity */ 1);
   ```

3. **Process Image**:
   ```cpp
   // Decode image from bytes
   clip_image_u8* img = clip_image_u8_init();
   clip_image_load_from_bytes(img, imageBytes, imageSize);
   
   // Preprocess for model
   clip_image_f32* img_res = clip_image_preprocess(ctx_clip, img);
   
   // Encode to embeddings
   float* image_embd = (float*)malloc(clip_n_mmproj_embd(ctx_clip) * sizeof(float));
   clip_image_encode(ctx_clip, n_threads, img_res, image_embd);
   ```

4. **Run Text Generation with Image Context**:
   ```cpp
   // Create batch with image embeddings + text prompt
   llama_batch batch = llama_batch_init(n_ctx, 0, 1);
   
   // Add image embeddings to batch
   // (specific implementation depends on model architecture)
   
   // Run inference
   llama_decode(ctx, batch);
   
   // Sample output
   llama_token new_token_id = llama_sampler_sample(smpl, ctx, -1);
   ```

---

### **Step 3: Update CMakeLists.txt** (5 mins)

```cmake
# Link against libllama.so
find_library(llama-lib llama PATHS ${CMAKE_SOURCE_DIR}/jniLibs/${ANDROID_ABI})

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${llama-lib}
    android
    log
)
```

---

### **Step 4: Test Build** (5 mins)

```bash
cd /Users/user/Downloads/CouponTracker3
./gradlew assembleDebug
```

---

### **Step 5: Test Inference** (5 mins)

1. Install APK
2. Open Settings → Test Model
3. Check logs for:
   ```
   D/MlcLlmNative: Model has vision: YES
   D/MlcLlmNative: Running vision inference...
   D/MlcLlmNative: Inference result: {"storeName": "Leaf", ...}
   ```

---

## 🎯 **IMMEDIATE NEXT STEPS**

Based on our progress, here's what to do RIGHT NOW:

### **Step 1: Test Current GGUF** (Recommended First)

Try using the existing `ggml-model-Q4_K_M.gguf` WITHOUT mmproj:

1. Update JNI to call llama.cpp directly
2. Test if vision works
3. If yes → DONE!
4. If no → Download mmproj

### **Step 2: Download mmproj** (If Needed)

```bash
cd /Users/user/Downloads/CouponTracker3/android_models
wget https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/mmproj-model-f16.gguf
```

Then update model download to include this file.

---

## 🔍 **Current vs Target**

### **Current (Mock)**:
```
User selects image
    ↓
Mock JNI returns: {"store": "Example Store"}
    ↓
Display mock data
```

### **Target (Real)**:
```
User selects image
    ↓
Load image into memory
    ↓
clip_image_encode() → Image embeddings
    ↓
llama_decode() with image context
    ↓
Real AI extraction: {"store": "Leaf", "amount": 16099.0}
    ↓
Display REAL data
```

---

## 📊 **Expected Performance**

### **Inference Time**:
- Cold start: ~3-5s (model load)
- Warm inference: ~1-2s per image
- Image encoding: ~200-500ms
- Text generation: ~500ms-1s

### **Memory Usage**:
- Model: ~4.7GB (main) + ~1GB (mmproj) = ~5.7GB disk
- Runtime: ~2-3GB RAM
- Peak: ~3-4GB RAM during inference

### **Accuracy**:
- Better than pattern matching
- Understands context and layout
- Handles complex coupons
- May still need post-processing for specific fields

---

## ✅ **SUCCESS CRITERIA**

1. ✅ libllama.so loaded successfully
2. ✅ Model file loaded (GGUF)
3. ✅ Vision projector loaded (mmproj)
4. ✅ Image encoding works
5. ✅ Inference returns structured JSON
6. ✅ Self-test passes with real data
7. ✅ No more "Example Store" in output

---

## 🎯 **DECISION POINT**

**What should we do NEXT?**

**Option A**: Test with existing GGUF (no mmproj) - 15 mins
**Option B**: Download mmproj first - 10 mins
**Option C**: Full JNI rewrite with vision support - 1 hour

**Recommendation**: **Option A** - Try existing GGUF first, see if it has vision built-in.

---

**Ready to proceed?** 🚀


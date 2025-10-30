# mmproj Loading Issue - DIAGNOSED ✅

## 📋 Summary

**mmproj file downloaded successfully** (1.1GB) but **fails to load** due to using the wrong API.

---

## ✅ What Works

1. **Download**: Both files downloaded successfully
   - Main model: `ggml-model-Q4_K_M.gguf` (4.9GB) ✅
   - Vision projector: `mmproj-model-f16.gguf` (1.1GB) ✅
   - Total: 5.8GB ✅

2. **File Location**: Correct
   ```
   /data/user/0/com.example.coupontracker/files/models/minicpm_llama3_v25_q4/
     ├── ggml-model-Q4_K_M.gguf
     └── mmproj-model-f16.gguf
   ```

3. **Main Model Loading**: Works perfectly (11-14 seconds)

---

## ❌ What Doesn't Work

**mmproj loading fails** with this log:
```
2025-10-02 14:46:29.172  MLC_LLM_JNI_REAL    - Found mmproj file, loading...
2025-10-02 14:46:29.187  MLC_LLM_JNI_REAL    ⚠️  Failed to load mmproj file
2025-10-02 14:46:29.187  MLC_LLM_JNI_REAL    ⚠️  Falling back to text-only mode
```

---

## 🔍 Root Cause

**Using wrong API to load mmproj:**

### Current Code (WRONG):
```cpp
// File: mlc_llm_jni_real.cpp:135
ctx->clip_model = llama_load_model_from_file(mmproj_path.c_str(), mmproj_params);
```

**Problem**: `llama_load_model_from_file()` is designed for **text models**, not **vision projectors**. mmproj files have a different format and require specialized loading functions.

### Correct API (from llama.cpp):
```cpp
// Include the multimodal library
#include "tools/mtmd/clip.h"

// Load mmproj using clip_init
struct clip_context_params clip_params = {
    .use_gpu = false,
    .verbosity = GGML_LOG_LEVEL_INFO
};

struct clip_init_result result = clip_init(mmproj_path.c_str(), clip_params);
if (result.ctx_v) {
    // Vision context loaded successfully
    ctx->clip_ctx = result.ctx_v;
    ctx->has_vision = true;
} else {
    // Failed to load
}
```

---

## 📚 Reference: Official llama.cpp Support

From `/Users/user/Downloads/llama.cpp/docs/multimodal/minicpmv2.5.md`:

```bash
# Official way to use MiniCPM-V 2.5 with mmproj:
./build/bin/llama-mtmd-cli \
  -m ../MiniCPM-Llama3-V-2_5/model/ggml-model-Q4_K_M.gguf \
  --mmproj ../MiniCPM-Llama3-V-2_5/mmproj-model-f16.gguf \
  -c 4096 --temp 0.7 --top-p 0.8
```

Key points:
- Uses `llama-mtmd-cli` (multimodal tool)
- Requires `libmtmd` library
- mmproj loaded separately using `clip_init()`

---

## 🛠️ Solution Options

### Option 1: Rebuild llama.cpp with Multimodal Support (RECOMMENDED)

**Pros**:
- Official support for MiniCPM-V vision
- Proper mmproj loading with `clip_init()`
- Full vision inference capabilities

**Cons**:
- Requires rebuilding llama.cpp Android libraries
- More complex build process

**Steps**:
1. Navigate to llama.cpp Android example:
   ```bash
   cd /Users/user/Downloads/llama.cpp/examples/llama.android
   ```

2. Ensure multimodal support is enabled in `llama/build.gradle.kts`:
   ```kotlin
   // Add mtmd (multimodal) library to CMake
   arguments += listOf(
       "-DLLAMA_MTMD=ON",
       "-DBUILD_SHARED_LIBS=ON"
   )
   ```

3. Rebuild:
   ```bash
   ./gradlew :llama:assembleRelease
   ```

4. Copy new .so files to CouponTracker3:
   ```bash
   cp llama/build/intermediates/cxx/Release/*/obj/arm64-v8a/*.so \
      /Users/user/Downloads/CouponTracker3/app/src/main/jniLibs/arm64-v8a/
   ```

5. Update JNI code to use `clip_init()` instead of `llama_load_model_from_file()`

---

### Option 2: Use Text-Only Mode (CURRENT)

**Pros**:
- Already working
- No rebuild required
- Fast extraction (12 seconds)

**Cons**:
- No vision inference
- Falls back to OCR + patterns

**Current behavior**:
```kotlin
// MiniCPM runs in "diagnostic mode"
// Outputs JSON schema but no actual vision understanding
// Falls back to ML Kit OCR + TextExtractor patterns
```

**Results**:
- Store: "PILGRIM" ✅ (from OCR patterns)
- Code: "PPEB3899MAY25XWAY" ✅ (from OCR patterns)
- Amount: "36%" ⚠️ (incorrect, from "5G 36%" OCR error)

---

### Option 3: Hybrid Approach (PRAGMATIC)

**Combine**:
1. Keep current text-only LLM for speed
2. Enhance pattern-based extraction to fix "36%" error
3. Use MiniCPM LLM on text only (no vision)

**Benefits**:
- No rebuild required
- Fix immediate extraction issues
- Keep 12-second extraction speed

---

## 🎯 Recommended Action

### **Short Term** (Fix 36% error NOW):

Pattern extractor incorrectly picks up "5G 36%" battery indicator. Fix:

```kotlin
// In StructuredFieldExtractor.kt
// Improve percentage regex to exclude battery indicators
val percentPattern = Regex("""
    (?<!5G\s)           # Negative lookbehind: not preceded by "5G "
    (?<!LTE\s)          # Negative lookbehind: not preceded by "LTE "
    (?<!4G\s)           # Negative lookbehind: not preceded by "4G "
    (\d{1,2})%          # Actual percentage (1-2 digits)
    (?!\s*battery)      # Negative lookahead: not followed by "battery"
""".trimIndent(), RegexOption.IGNORE_CASE or RegexOption.COMMENTS)
```

### **Long Term** (Full Vision Support):

Rebuild llama.cpp with multimodal support and integrate `clip_init()` API.

---

## 📊 Current Extraction Quality

| Field | Extracted Value | Correct? | Source |
|-------|----------------|----------|---------|
| Store | PILGRIM | ✅ Yes | OCR Pattern |
| Code | PPEB3899MAY25XWAY | ✅ Yes | OCR Pattern |
| Amount | 36% | ❌ No | OCR Error (battery indicator) |
| Description | (Full text) | ⚠️ Verbose | OCR |
| Expiry | null | ❌ Missing | Not extracted |

**Actual coupon details** (from image):
- Store: PILGRIM ✅
- Offer: Buy 3 at ₹899
- Code: PPEB3899MAY25XWAY ✅
- Expires: 31 May 2025 ❌ (not extracted)
- Amount: Not "36%" ❌ (that's battery level)

---

## 🔧 Next Steps

1. **Immediate**: Fix percentage extraction to ignore battery indicators
2. **Soon**: Extract expiry date ("Expires on 31 May, 2025")
3. **Future**: Consider rebuilding with multimodal support for true vision inference

---

## 📝 Files to Update

1. `/Users/user/Downloads/CouponTracker3/app/src/main/kotlin/com/example/coupontracker/extraction/StructuredFieldExtractor.kt`
   - Improve percentage regex
   - Add expiry date extraction (already exists but failing)

2. **(Future)** `/Users/user/Downloads/CouponTracker3/app/src/main/cpp/mlc_llm_jni_real.cpp`
   - Replace `llama_load_model_from_file()` with `clip_init()`
   - Add multimodal image encoding logic


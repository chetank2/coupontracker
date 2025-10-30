# Log Analysis & Critical Fixes - October 6, 2025

**Branch:** `feature/schema-driven-architecture`  
**Session:** Post-device test analysis  
**Commit:** `88ba5d6d4`

---

## 📊 Log Analysis Summary

### Test Scenario
- **Coupon:** XYXX polo t-shirts from AERTEK
- **OCR Text:** 200 chars including UI chrome
- **Model:** Qwen2.5-1.5B-Instruct (Q4_K_M, 986MB)
- **First inference:** 166 seconds (~2.8 minutes)

---

## 🚨 Critical Issues Discovered

### **Issue 1: LLM Generating Incomplete JSON**

**Symptoms:**
```json
{
"storeName": "AERTEK",
"description": null,
"cashback": {"type":"percent","valueNum":49,"currency":""},
"offerText": "\nYou get XYXX polo t-shirts from 75.99...
```

**Error:**
```
Schema validation failed: [Invalid JSON syntax: Unterminated object at character 104]
```

**Analysis:**
- LLM stopped at 400 tokens (max limit)
- Missing fields: `redeemCode`, `expiryDate`, `minOrderAmount`, closing `}`
- Grammar enforcement worked (no `END_OCR` markers!) ✅
- But output was truncated mid-generation

**Root Cause:**
```cpp
int max_tokens = 400;  // TOO LOW for 7-field schema
```

**Impact:**
- LLM extraction always fails with "Unterminated object"
- Falls back to traditional OCR pattern matching
- Defeats entire purpose of schema-driven architecture

---

### **Issue 2: UI Chrome in LLM Input**

**OCR Text Received:**
```
9:57 (9           ← Time display
vouchers
active : 18 lifetime : 428    ← Status bar
XYXX4.31
Details
you get XYXX polo t-shirts from
7599 + 50 cashback via CRED pay
Vo 5G             ← Signal indicator
```

**LLM Extracted:**
```json
"storeName": "AERTEK"  ← CORRECT from OCR (later in text)
```

**Traditional OCR Fallback Extracted:**
```
storeName='428'        ← WRONG! Extracted from "lifetime : 428"
description='Vouchers active 18 lifetime 428'  ← UI chrome!
redeemCode='EXPIRES'   ← WRONG! Extracted from "EXPIRES IN"
```

**Analysis:**
- `OcrTextCleaner` was created but **never applied** before LLM inference
- LLM handled UI noise better than pattern matching
- Traditional fallback extracted complete garbage

**Root Cause:**
```kotlin
private fun createCouponExtractionPrompt(ocrText: String): String {
    val sanitizedOcr = sanitizeOcrSnippet(ocrText)  // ← Only truncates, doesn't clean!
    // ... never calls OcrTextCleaner
}
```

---

### **Issue 3: Slow Inference Performance** ⚠️

**Observed:**
```
First inference: 166,161ms (166 seconds = 2.8 minutes)
Expected: ~60s warmup + 10-20s generation = 70-80s max
```

**Warning Triggered:**
```
⚠️  Slow inference detected: 166161ms (expected ~10-20s after warmup)
⚠️  Check KV cache clearing and model state reset
```

**Analysis:**
- KV cache IS being cleared correctly ✅
- Grammar IS loaded and enforcing JSON ✅
- Possible causes:
  1. CPU throttling (device was under load?)
  2. Prompt too long (3158 chars → 920 tokens)
  3. Generation of 400 tokens taking ~100s (2.5 tokens/sec)

**Context:**
```
Step 1: Tokenized: 920 tokens (prompt)
Step 2: Running LLM inference...
  [115 seconds = context processing]
Step 3: Generating response...
  [51 seconds = generating 400 tokens]
```

**Not urgent** - still completes within 180s timeout, but monitoring needed.

---

## ✅ Fixes Applied

### **Fix 1: Increase Token Limit**

**File:** `app/src/main/cpp/mlc_llm_jni_real.cpp:186`

```cpp
// BEFORE
int max_tokens = 400;  // Insufficient for 7-field schema

// AFTER
int max_tokens = 600;  // Allows complete JSON with all fields
```

**Reasoning:**
- Schema has 7 top-level fields + nested `cashback` object
- Average: ~70 tokens per field + structure = ~500 tokens
- 600 provides safe margin for verbose descriptions

---

### **Fix 2: Apply OCR Text Cleaner**

**File:** `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt:647`

```kotlin
// BEFORE
private fun createCouponExtractionPrompt(ocrText: String): String {
    val sanitizedOcr = sanitizeOcrSnippet(ocrText)
    // ... generate prompt
}

// AFTER
private fun createCouponExtractionPrompt(ocrText: String): String {
    // CRITICAL: Clean OCR text first (remove UI chrome)
    val cleanedOcr = OcrTextCleaner.cleanOcrText(ocrText)
    val finalOcr = cleanedOcr.ifBlank { ocrText }  // Fallback
    
    val sanitizedOcr = sanitizeOcrSnippet(finalOcr)
    // ... generate prompt
}
```

**Impact:**
- Removes: `9:57`, `active : 18 lifetime : 428`, `Vo 5G`, `LTE`
- LLM sees only: `vouchers`, `XYXX4.31`, `Details`, `you get XYXX...`
- Cleaner input = better extraction accuracy

---

## 📈 Expected Improvements

| **Metric** | **Before** | **After (Expected)** |
|------------|------------|----------------------|
| **JSON Completeness** | ❌ Truncated at 400 tokens | ✅ Complete (600 tokens) |
| **Validation Pass Rate** | 0% (unterminated object) | 95%+ (complete JSON) |
| **Store Name** | "428" or "AERTEK" | "AERTEK" (correct) |
| **Description** | "Vouchers active 18 lifetime 428" | "You get XYXX polo t-shirts..." |
| **Redeem Code** | "EXPIRES" (wrong) | Correctly extracted or null |
| **Fallback Rate** | 100% (always fails) | <10% (only on real failures) |

---

## 🧪 Next Test

**Install updated APK:**
```bash
adb install app/build/outputs/apk/debug/app-universal-debug.apk
```

**Test same coupon again:**
1. Verify LLM generates **complete JSON** (all 7 fields)
2. Verify schema validation **passes**
3. Check store name = "AERTEK" (not "428")
4. Check description is clean (no "active : 18 lifetime : 428")
5. Monitor inference time (should still be ~166s first run)

**Success Criteria:**
✅ No "Unterminated object" errors  
✅ Schema validation passes  
✅ Store name extracted correctly  
✅ Description is clean offer text  
✅ No fallback to traditional OCR (unless real LLM failure)

---

## 🎯 What's Working Well

Despite the issues, several things worked correctly:

1. ✅ **Grammar enforcement** - No `END_OCR` or malformed JSON structure
2. ✅ **KV cache clearing** - Verified in logs after each inference
3. ✅ **Model loading** - Qwen2.5 loaded correctly, 986MB
4. ✅ **Schema prompts** - Generated correctly from `CouponSchema`
5. ✅ **Progressive pipeline** - All 6 passes executed
6. ✅ **Pattern extraction** - Detected "49%" from text
7. ✅ **Timeout handling** - Completed within 180s limit

---

## 📝 Remaining Observations

### Performance Breakdown
```
Total time: 166s
├─ Model load: ~3s
├─ Context processing: ~115s  ← Prompt tokenization + KV cache
└─ Generation: ~51s  ← 400 tokens at ~8 tokens/sec
```

**Notes:**
- Context processing (115s) is the bottleneck, not generation
- 920 tokens prompt → 115s = **8 tokens/sec processing**
- This is CPU-bound, expected for Q4 quantization
- Generation speed (~8 tokens/sec) is reasonable for mobile CPU

### Grammar Performance
```
✅ Grammar file loaded (1095 bytes)
✅ JSON GRAMMAR ENFORCEMENT ENABLED!
✅ Grammar sampler added (STRICT JSON enforcement)
```

Grammar is working! The truncation was purely due to token limit, not grammar issues.

---

## 🔄 Summary

**2 Critical Fixes Applied:**
1. ✅ Increased max_tokens: 400 → 600
2. ✅ Applied OcrTextCleaner before LLM

**Expected Result:**
- Complete JSON generation
- Clean input (no UI chrome)
- Schema validation passes
- Correct extraction

**Build Status:** ✅ Successful (commit `88ba5d6d4`)  
**APK Location:** `app/build/outputs/apk/debug/app-universal-debug.apk`  
**Ready for testing!** 🚀


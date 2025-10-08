# Prompt Optimization Results

**Date:** October 8, 2025  
**Commits:** `508b091fa`, `3b6cd7160`, `c40f0fc0c`, `ace5910d7`

---

## ✅ **What Worked: Compact Prompts**

### **Token Reduction**
```
BEFORE: 920 tokens (verbose prompts)
AFTER:  365 tokens (compact prompts)
REDUCTION: 60% ✅
```

### **Context Processing Speed**
```
BEFORE: 115s for 920 tokens
AFTER:  ~35s for 365 tokens  
IMPROVEMENT: 70% faster ✅
```

### **JSON Structure**
```
✅ Grammar enforcement working
✅ All 7 fields present
✅ No "END_OCR" garbage
✅ Valid JSON structure
```

---

## ❌ **What Didn't Work: Inference Speed**

### **Total Inference Time**
```
Run 1: 65s  (expected: ~10-20s)
Run 2: 128s (expected: ~10-20s)

BREAKDOWN:
- Context processing: ~35s (365 tokens)
- Generation: ~30-90s (86 tokens)
```

**Not a prompt issue** - This is model/hardware performance.

---

## 🚨 **Critical Issues Found (Unrelated to Prompts)**

### **Issue 1: UI Chrome Contamination**

**OCR Input (BEFORE cleaning):**
```
8:06 (m M ū 9         ← Time, battery, signal
Vouchers
active : 25 lifetime : 279   ← UI stats
code: PBXWOF110K
Details
o lenskart
```

**LLM Output (garbage):**
```json
{
  "expiryDate": "20 days ago (m M ū)",  ← Parsed UI noise
  "cashback": {"valueNum": -638}         ← Parsed "25" or "279" as cashback
}
```

**Root Cause:** `ProgressiveExtractionService` was using raw OCR without cleaning.

**Fix (commit `ace5910d7`):**
```kotlin
// Clean OCR before creating ExtractionContext
val cleanedOcr = OcrTextCleaner.cleanOcrText(ocrText)
val finalOcr = cleanedOcr.ifBlank { ocrText }

val context = ExtractionContext(
    ocrText = finalOcr  // Use cleaned text
)
```

---

### **Issue 2: Low Quality Extraction**

**LLM Output:**
```json
{
  "storeName": "lenskart",           ✅ Correct!
  "description": "",                  ❌ Empty
  "cashback": {"valueNum": -638},     ❌ Negative (wrong)
  "expiryDate": "20 days ago (m M ū)", ❌ Garbage
  "redeemCode": "AFFLCRDG-OKTYZX6-6TOZ" ✅ Correct!
}
```

**Quality Score:** 30/100 → Failed validation → Fell back to pattern matching

**Pattern Matching Output:**
```
storeName: "Details"  ❌ Wrong! (Should be "lenskart")
```

**Root Cause:** UI chrome caused LLM to extract garbage → low confidence → fallback.

**Expected After Fix:** With clean OCR, LLM should extract correctly.

---

## 📊 **Performance Summary**

| **Metric** | **Before** | **After** | **Status** |
|------------|-----------|-----------|------------|
| **Prompt tokens** | 920 | 365 | ✅ 60% reduction |
| **Context processing** | 115s | ~35s | ✅ 70% faster |
| **Total inference** | 166s | 65-128s | ⚠️ Still slow |
| **JSON structure** | ❌ Truncated | ✅ Complete | ✅ Fixed |
| **OCR cleaning** | ❌ Not applied | ✅ Applied | ✅ Fixed |
| **Extraction accuracy** | ❌ "Details" | ⚠️ TBD | 🔄 Test needed |

---

## 🧪 **Test Plan**

### **1. Install New APK**
```bash
adb install app/build/outputs/apk/debug/app-universal-debug.apk
```

### **2. Test Same Lenskart Coupon**

**Expected Results:**

✅ **OCR Cleaning:**
```
OCR cleaning: 252 → ~180 chars
```

✅ **LLM Output:**
```json
{
  "storeName": "lenskart",  // Not "Details"
  "description": "Gold Max membership at just 49",
  "cashback": {"type": "amount", "valueNum": 49},
  "expiryDate": "EXPIRES IN 29 DAYS",  // Not "20 days ago (m M ū)"
  "redeemCode": "PBXWOF110K"  // Or other visible code
}
```

✅ **Quality Score:** > 70 (should pass validation)

✅ **No Fallback:** Should NOT fall back to pattern matching

---

### **3. Monitor Logs**

```bash
adb logcat -s ProgressiveExtractionService:* LocalLlmOcrService:* MLC_LLM_JNI_REAL:*
```

**Look for:**
```
✅ OCR cleaning: 252 → 180 chars
✅ Tokenized: 365 tokens
✅ Context processed (~35s)
✅ Extraction quality score: 70+ (was 30)
✅ storeName: "lenskart" (not "Details")
```

---

## 🎯 **What We Fixed**

1. ✅ **Prompt bloat** (920 → 365 tokens, 60% reduction)
2. ✅ **Context processing** (115s → 35s, 70% faster)
3. ✅ **Token limit** (400 → 600, complete JSON)
4. ✅ **OCR cleaning** (now applied in `ProgressiveExtractionService`)
5. ✅ **Temperature** (0.3 → 0.1, more deterministic)

---

## 🔴 **What's Still Slow (Not Fixable with Prompts)**

**Generation Time:** 30-90s for 86 tokens

**This is hardware/model-bound:**
- ARM CPU: ~1-3 tokens/sec
- Q4 quantization: Accuracy tradeoff
- 1.5B parameters: Large for mobile

**Options if speed is still unacceptable:**
1. **Accept it** - First run is slow, subsequent ~faster
2. **Smaller model** - TinyLlama 1.1B (~30% faster, less accurate)
3. **Server hybrid** - First extraction on server, cache patterns
4. **GPU acceleration** - If device supports (not all do)

---

## 📝 **Bottom Line**

**Prompt optimization worked:**
- 60% fewer tokens
- 70% faster context processing
- Clean, structured JSON output

**But exposed deeper issues:**
- UI chrome contamination (now fixed)
- Slow generation (hardware-bound, not fixable with prompts)
- Quality validation too strict (might need tuning)

**Next test should show:**
- ✅ Clean LLM input
- ✅ Correct extraction ("lenskart", not "Details")
- ✅ Passing quality score (>70, not 30)
- ⚠️ Still slow total time (~60-90s, but accurate)

**If accuracy improves but speed is still an issue → consider model swap.**  
**If accuracy doesn't improve → dig deeper into quality scoring logic.**

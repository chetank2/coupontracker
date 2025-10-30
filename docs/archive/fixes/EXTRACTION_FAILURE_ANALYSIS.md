# Extraction Failure Root Cause Analysis

## 📊 Problem Summary

From the screenshots provided, the extraction has **4-5 critical discrepancies**:

### Coupon 1: BOAT → McDonald Issue
- ❌ Store: BOAT → McDonald (WRONG)
- ❌ Code: BTXS5T13LI9V5 → VONTIME (WRONG)
- ✅ Discount: 80% (CORRECT)

### Coupon 2: PhonePe Bus
- ✅ Store: PhonePe (CORRECT)
- ✅ Code: PANDOCSCOPY (CORRECT)
- ❌ Expiry: 15 Nov 2025 → Unknown (WRONG)

---

## 🔍 Root Cause Investigation

### Current Extraction Strategy
```kotlin
// From ExtractionStrategy.kt line 52
private var _strategy: ExtractionStrategy = ExtractionStrategy.OCR_FIRST
```

**Extraction Flow:**
1. ✅ OCR extracts text using ML Kit
2. ✅ Text passed to `UniversalExtractionService` or `TextExtractor`
3. ❌ **TextExtractor uses WORD FREQUENCY HEURISTICS, not LLM**

---

## ⚠️ Critical Finding: LLM is NOT Being Used

### Why the LLM Isn't Working:

**1. Extraction Strategy is `OCR_FIRST`**
```kotlin
// Line 226 in ScannerViewModel.kt
com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST -> {
    scanWithOcrFirstPath(imageUri, bitmap, persistImmediately)
}
```

**2. OCR_FIRST Path Uses Pattern Matching**
```kotlin
// Line 456 in ScannerViewModel.kt
val extractionResult = universalExtractionService.extractCoupon(
    image = bitmap,
    ocrText = ocrText,
    context = context
)
```

**3. TextExtractor Uses Heuristics, Not LLM**
```kotlin
// TextExtractor.kt lines 138-174
// Extracts store name using:
- Word frequency analysis
- Position-based scoring
- Title case detection
- NOT LLM inference
```

**Store Name Extraction Logic:**
```kotlin
val baseScore = if (isTitleCase) 4.0 else 2.0
val frequency = wordFrequency[normalized] ?: 1
val frequencyScore = if (frequency > 1) frequency * 2.5 else frequency.toDouble()
val lineBonus = (lines.size - lineIndex).coerceAtLeast(1)
val positionScore = (text.length - firstIndex).toDouble() / text.length.toDouble() * 3.0
val totalScore = baseScore + frequencyScore + lineBonus + positionScore
```

**This explains the McDonald error:**
- "McDonald" probably appeared in the OCR text (location/map)
- Word frequency scoring picked it up
- It was more prominent than "BOAT" in the text

---

## 🧪 Verification from Logs

From user's logcat:
```
2025-10-11 12:03:25.184 ExtractionConfig: Loaded strategy: LEGACY
```

**The app is using LEGACY strategy, not even OCR_FIRST!**

**LEGACY Strategy Path:**
```kotlin
// Line 218 in ScannerViewModel.kt
ExtractionStrategy.LEGACY -> {
    scanWithLegacyPath(imageUri, bitmap, persistImmediately)
}
```

**Legacy path goes through:**
1. ImageProcessor
2. ModelBasedOCRService (uses pattern matching)
3. TextExtractor (word frequency heuristics)
4. **No LLM at all**

---

## 🔥 Why LLM Strategies Are Disabled

From `ExtractionConfig.kt` lines 69-80:
```kotlin
// Block non-functional strategies due to missing models
_strategy = when (savedStrategy) {
    ExtractionStrategy.LLM_FIRST -> {
        Log.w(TAG, "LLM_FIRST requires real MLC-LLM binaries - falling back to OCR_FIRST")
        ExtractionStrategy.OCR_FIRST
    }
    ExtractionStrategy.HYBRID -> {
        Log.w(TAG, "HYBRID requires real MLC-LLM binaries - falling back to OCR_FIRST")
        ExtractionStrategy.OCR_FIRST
    }
    else -> savedStrategy
}
```

**LLM strategies are intentionally blocked!**

From comments in code:
```kotlin
// Line 51: Default strategy (OCR_FIRST - most reliable with current model availability)
// LLM_FIRST and HYBRID disabled due to missing MLC-LLM binaries
```

---

## 📝 The LocalLlmOcrService Situation

Even when `LocalLlmOcrService` is called, it:

1. **Times out** (180 second timeout)
```kotlin
// Line 40
private const val INFERENCE_TIMEOUT_MS = 180_000L
```

2. **Falls back to pattern matching**
```kotlin
// Line 584
Log.d(TAG, "Falling back to traditional OCR")
val fallbackResult = fallbackToTraditionalOCR(bitmap, captureTimestamp)
```

3. **Traditional OCR uses TextExtractor**
```kotlin
// Line 1309
val textExtractor = TextExtractor()
val extractedInfo = textExtractor.extractCouponInfoSync(ocrText, captureTimestamp)
```

---

## 🎯 The Real Problem

### The LLM is NOT running because:

1. ✅ **Model files exist** (4.9GB qwen25_1.5b_instruct_q4.gguf)
2. ❌ **Extraction strategy blocks LLM use** (LEGACY mode active)
3. ❌ **Code comments say "missing MLC-LLM binaries"**
4. ❌ **Even if called, LLM times out and falls back**

### Current Flow (No LLM):
```
Image → ML Kit OCR → TextExtractor (word frequency) → Wrong Results
```

### Expected Flow (With LLM):
```
Image → ML Kit OCR → Qwen2.5 LLM → Structured JSON → Correct Results
```

---

## 💡 Solution

### Option 1: Enable LLM Strategies (Recommended)

**Change strategy to use LLM:**
```kotlin
// In ExtractionConfig.kt
private var _strategy: ExtractionStrategy = ExtractionStrategy.HYBRID

// Remove the blocking code that forces fallback
```

**Verify LLM model is loaded:**
```kotlin
// Check if model file exists and is loaded
com.example.coupontracker.llm.MlcLlmNative.isAvailable()
```

### Option 2: Fix Pattern Matching (Fallback)

**Improve TextExtractor logic:**
- Better brand recognition patterns
- Coupon code validation
- Date extraction from specific formats

### Option 3: Use ProgressiveExtractionService

**Enable MiniCPM-FIRST pipeline:**
```kotlin
// ImageProcessor.kt line 131
private const val USE_PROGRESSIVE_EXTRACTION = true
```

This uses:
```kotlin
progressiveExtractionService.extractCoupon(
    androidContext = context,
    image = bitmap,
    ocrText = ocrText
)
```

---

## 🚨 Immediate Actions Needed

### 1. Check Model Status
```bash
# Verify model file exists
ls -lh android_models/
```

### 2. Enable LLM Strategy
```kotlin
// Force HYBRID mode in ExtractionConfig
fun init(context: Context) {
    _strategy = ExtractionStrategy.HYBRID
    // Don't block LLM strategies
}
```

### 3. Check LLM Runtime
```kotlin
// Verify LlmRuntimeManager is working
val isModelLoaded = llmRuntimeManager.isModelLoaded()
Log.d(TAG, "LLM Model loaded: $isModelLoaded")
```

### 4. Test Actual LLM Call
```kotlin
// Call LocalLlmOcrService directly
val result = localLlmOcrService.processCouponImageTyped(bitmap)
Log.d(TAG, "LLM extraction result: $result")
```

---

## 📌 Summary

**The LLM is NOT extracting coupons because:**
1. App is using `LEGACY` strategy (pure pattern matching)
2. LLM strategies are intentionally blocked in code
3. Even if enabled, LLM may timeout and fallback to patterns
4. Pattern matching uses word frequency (picks up "McDonald" from map UI)

**Fix: Enable LLM strategy and verify model is loaded properly**

---

## Next Steps

1. **Verify model file exists and is valid**
2. **Enable HYBRID or LLM_FIRST strategy**
3. **Remove strategy blocking code**
4. **Test LLM inference directly**
5. **Check inference timeout logs**
6. **If LLM still fails, investigate model loading**


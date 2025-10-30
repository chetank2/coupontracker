# 🔍 Extraction Diagnosis

## Problem
User reports that extraction still shows:
- Store Name: "Unknown Store"
- Description: "Error processing coupon"

## Expected Flow

```
User selects image from gallery
↓
CouponFormViewModel.processImageUri()
↓
CouponInputManager.processCouponFromImageUriWithPersistence()
↓
CouponInputManager.processCouponFromBitmap()  (line 168)
↓
ImageProcessor.processImage(bitmap, timestamp)  (line 212)
↓
[KEY DECISION POINT - line 127-130 of ImageProcessor.kt]
IF (USE_PROGRESSIVE_EXTRACTION && progressiveExtractionService != null)
  → processWithProgressivePipeline()
ELSE
  → Legacy flow (Model-based OCR)
```

## Root Cause Hypothesis

### Hypothesis #1: Progressive service is NULL
**Likelihood**: HIGH

The dependency might not be injecting properly. Check:
- Is `@Singleton` annotation present on `ProgressiveExtractionService`? ✅ YES (line 29)
- Is it being provided in Hilt module? ✅ YES (`ExtractionModule.kt` line 44-56)
- Is `ImageProcessor` getting the injected service? ✅ YES (`LlmModule.kt` line 66-68)

**BUT**: What if the instance is somehow null at runtime?

### Hypothesis #2: Exception in Progressive Pipeline
**Likelihood**: HIGH

Line 214-217 of `ImageProcessor.kt`:
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Progressive extraction failed, falling back to legacy", e)
    return@withContext fallbackToModelBasedOcr(bitmap, captureTimestamp)
}
```

If progressive extraction throws an exception, it silently falls back to `ModelBasedOCRService`.

Then `ModelBasedOCRService.processCouponImage()` probably still has hardcoded error messages!

### Hypothesis #3: OCR Text is Empty
**Likelihood**: MEDIUM

Line 182-185:
```kotlin
if (ocrText.isBlank()) {
    Log.w(TAG, "OCR text is empty, falling back to legacy")
    return@withContext fallbackToModelBasedOcr(bitmap, captureTimestamp)
}
```

If OCR can't extract text from the screenshot, it falls back to legacy.

### Hypothesis #4: ModelBasedOCRService STILL has hardcoded messages
**Likelihood**: CRITICAL

Let me check what `ModelBasedOCRService` actually returns...

## Investigation Plan

1. ✅ Check if `ProgressiveExtractionService` is properly injected
2. ⏳ Check `ModelBasedOCRService` for hardcoded strings
3. ⏳ Check `LocalLlmOcrService` for hardcoded strings
4. ⏳ Check `TextExtractor` for hardcoded strings
5. ⏳ Add extensive logging to trace execution path
6. ⏳ Build and test with real device

## Next Steps

BUILD THE APP AND CHECK LOGCAT TO SEE ACTUAL EXECUTION PATH.


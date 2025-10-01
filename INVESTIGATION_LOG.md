# 🔍 Deep Investigation: Why Progressive Extraction Still Shows "Unknown Store" & "Error processing coupon"

## Problem Statement

**Observed Behavior**:
- Store Name: "Unknown Store"
- Description: "Error processing coupon"
- Amount: 0.0
- Redeem Code: Empty
- Expiry Date: Empty

**Expected Behavior** (from Leaf Halo Smart Ring coupon):
- Store Name: "Leaf"
- Description: "you won ₹16099 off on Leaf Halo Smart Ring"
- Amount: ₹16099 or relevant amount
- Other fields populated

**Image shows**: "you won ₹16099 off on Leaf Halo Smart Ring" with Leaf branding visible

---

## Investigation Phases

### Phase 1: Entry Point Analysis
- [ ] Find where image scanning starts
- [ ] Verify OCR is being called
- [ ] Check OCR text output
- [ ] Verify extraction service is invoked

### Phase 2: Progressive Pipeline Verification
- [ ] Confirm USE_PROGRESSIVE_PIPELINE flag is actually true at runtime
- [ ] Check if extractWithProgressivePipeline() is being called
- [ ] Verify no exceptions are caught silently

### Phase 3: Pass-by-Pass Execution
- [ ] Pass 1: Structured - verify patterns match
- [ ] Pass 2: Semantic - verify sentence analysis
- [ ] Pass 3: Heuristic - verify fallback logic
- [ ] Pass 5: Defaults - verify OCR text preservation

### Phase 4: Data Flow Validation
- [ ] Check FieldCandidate → ExtractionCandidate conversion
- [ ] Verify ProgressiveExtractionResult → UniversalExtractionResult conversion
- [ ] Check Coupon object construction from fields

### Phase 5: Database & UI Layer
- [ ] Verify Coupon is saved correctly to Room DB
- [ ] Check UI data binding retrieves correct Coupon
- [ ] Verify no default values are overwriting extracted data

---

## Findings Log

### Finding 1: ❌ CRITICAL - Progressive Pipeline NOT Integrated in Main Extraction Path

**Root Cause**: The progressive extraction pipeline is ONLY integrated into `UniversalExtractionService`, which is ONLY called by `ScannerViewModel.scanWithOcrFirstPath()`. 

**Problem Path**: 
```
CouponFormViewModel.processImageUri()
  → CouponInputManager.processCouponFromImageUriWithPersistence()
    → CouponInputManager.processCouponFromImageUri()
      → CouponInputManager.processCouponFromBitmap()
        → ImageProcessor.processImage(bitmap)
          → ModelBasedOCRService.processCouponImage()  ❌ OLD PATH
          → tryMlKit() → TextExtractor                  ❌ OLD PATH
```

**Missing**: Progressive pipeline completely bypassed in the most common path (image upload/selection).

**Impact**: Edit Coupon screen (screenshot 1) uses CouponFormViewModel, which never calls progressive pipeline.

---

### Finding 2: ❌ CRITICAL - Multiple Extraction Paths Not Unified

**Paths Found**:
1. `ScannerViewModel` (4 strategies) - Uses `UniversalExtractionService` (✅ has progressive)
2. `CouponFormViewModel` - Uses `CouponInputManager` → `ImageProcessor` (❌ no progressive)
3. `SmartCaptureViewModel` - Uses `ImageProcessor` directly (❌ no progressive)
4. `AddFragment` - Uses `ImageProcessor` directly (❌ no progressive)
5. `BatchScannerViewModel` - Uses `UniversalExtractionService` (✅ has progressive)

**Problem**: Only 2 out of 5 entry points use the progressive pipeline!

---

### Finding 3: ❌ ImageProcessor Still Uses Old Flow

`ImageProcessor.processImage()` routes to:
- `LocalLlmOcrService` (if model downloaded)
- `ModelBasedOCRService` (fallback)
- `tryMlKit()` → `TextExtractor` (final fallback)

None of these use `UniversalExtractionService` or `ProgressiveExtractionService`.

---

### Finding 4: ❌ ModelBasedOCRService Returns CouponInfo (not using progressive)

`ModelBasedOCRService.processCouponImage()` still uses old extraction:
- `couponPatternRecognizer.recognizeElements()`
- `mlKitTextRecognition.processImageFromBitmap()`
- Returns `CouponInfo` with hardcoded defaults like "Unknown Store"

**SMOKING GUN** - `ModelBasedOCRService.kt` lines 92-99:
```kotlin
} catch (e: Exception) {
    Log.e(TAG, "Error processing coupon image", e)
    return CouponInfo(
        storeName = "Unknown Store",           // ❌ EXACT TEXT FROM SCREENSHOT
        description = "Error processing coupon", // ❌ EXACT TEXT FROM SCREENSHOT
        expiryDate = null,
        cashbackAmount = null,
        redeemCode = null
    )
}
```

This is the source of the error message!

---

### Finding 5: ❌ CouponInfo vs Coupon Data Model Mismatch

Two different data models in use:
1. `CouponInfo` - Used by old extractors (ModelBasedOCRService, TextExtractor)
2. `Coupon` - Used by database and UI

`CouponInputManager` converts `CouponInfo` → `Coupon` but preserves the bad data.

---

### Finding 6: ❌ No OCR Text Passed to Progressive Pipeline

Even when `UniversalExtractionService` is called, it needs OCR text as input.
But `ImageProcessor` doesn't extract OCR text separately before calling extraction services.

Current flow:
```
ImageProcessor → ModelBasedOCRService (does OCR internally)
```

Should be:
```
ImageProcessor → Extract OCR text → ProgressiveExtractionService(ocrText)
```

---

## Suspected Issues (Hypotheses)

1. **OCR might not be running** - OCR text could be empty
2. **Feature flag might be false** - Legacy path being used instead
3. **Exception being caught** - Progressive pipeline fails silently
4. **Field mapping broken** - Data lost in conversion
5. **UI showing cached/old data** - Not reflecting new extraction
6. **Database not updated** - Old coupon being displayed
7. **Patterns not matching** - Extraction fails, defaults not applied
8. **Pass 5 not executing** - OCR text not being used as fallback

---

## Next Steps

1. Add comprehensive logging at every step
2. Trace actual execution path
3. Verify data at each transformation point
4. Check for silent exception handling
5. Validate database state


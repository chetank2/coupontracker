# 🎯 REAL ROOT CAUSE ANALYSIS

## The Smoking Gun

After deep investigation, I found the issue is likely in the **exception handling chain**. 

## Current Flow

```
User picks image in Add Coupon screen
↓
CouponFormViewModel.processImageUri() (line 61)
↓
CouponInputManager.processCouponFromImageUriWithPersistence() (line 137)
↓
CouponInputManager.processCouponFromBitmap() (line 168)
↓
ImageProcessor.processImage(bitmap, timestamp) (line 212)
↓
ImageProcessor checks if progressive extraction is enabled (line 127)
```

## Root Cause #1: Exception Bubbles Up, Gets Generic Message

**If any exception occurs in the flow:**

1. `ImageProcessor.processImage()` throws exception (line 168)
2. `CouponInputManager.processCouponFromBitmap()` catches and logs "Error processing coupon from bitmap" (line 254)
3. Exception is **re-thrown** (line 255)
4. `CouponInputManager.processCouponFromImageUriWithPersistence()` catches and logs "Error processing coupon from image URI with persistence" (line 156)
5. Exception is **re-thrown** (line 157)
6. `CouponFormViewModel.processImageUri()` catches in `handleError()` function

Let me check `handleError()`:

## Root Cause #2: ViewModel Error Handling

Looking at `CouponFormViewModel.kt`, there must be a generic error message being set.

## Root Cause #3: OCR Engine Might Be Failing

If `ocrEngine.recognize(bitmap)` (ImageProcessor line 179) is throwing an exception or returning empty string, the progressive pipeline never gets a chance to run!

## Root Cause #4: ProgressiveExtractionService Dependency Might Be Null

Even though Hilt is configured correctly, runtime injection might be failing.

## Solution

I need to:

1. **Add defensive null checks** in `ImageProcessor.processImage()`
2. **Add extensive logging** to trace execution path
3. **Handle OCR failures gracefully** without throwing exceptions
4. **Test on actual device** to see real logs

Let me implement these fixes NOW.


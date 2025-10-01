# 🧪 Testing Instructions - Extraction Diagnosis

## Problem
Extraction shows:
- Store Name: "Unknown Store"
- Description: "Error processing coupon"

## What I Did

### Changes Made:
1. ✅ Added **extensive logging** to trace execution flow
2. ✅ Added **null check** for `progressiveExtractionService`
3. ✅ Added **OCR engine failure handling**
4. ✅ Added **OCR text preview logging**
5. ✅ Added **detailed progressive extraction result logging**
6. ✅ Added **exception stack trace logging**

### Files Modified:
- `app/src/main/kotlin/com/example/coupontracker/util/ImageProcessor.kt`

## How to Test

### Step 1: Install APK
```bash
# Find your device
adb devices

# Install the APK
cd /Users/user/Downloads/CouponTracker3
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

### Step 2: Clear Logcat and Start Monitoring
```bash
# Clear old logs
adb logcat -c

# Start monitoring with filters
adb logcat | grep -E "ImageProcessor|ProgressiveExtraction|CouponInputManager|CouponFormViewModel"
```

### Step 3: Test in App
1. Open CouponTracker app
2. Go to **Add Coupon** (bottom + button)
3. Select image from gallery (the Leaf Halo screenshot)
4. **WATCH THE LOGCAT OUTPUT**

## What to Look For in Logs

### ✅ SUCCESS Path (Progressive Extraction Working):
```
ImageProcessor: Processing bitmap image
ImageProcessor: ✨ Using PROGRESSIVE extraction pipeline
ImageProcessor: 🚀 Progressive Pipeline - Starting extraction
ImageProcessor: Step 1: Extracting OCR text using TesseractOcrEngine
ImageProcessor: OCR extracted 523 characters
ImageProcessor: OCR preview: Leaf you won ₹16099 in cashback!...
ImageProcessor: Step 2: Calling progressive extraction service
ProgressiveExtractionService: Starting progressive extraction pipeline
ProgressiveExtractionService: ▶ Pass 1: Structured extraction
ImageProcessor: ✅ Progressive extraction SUCCESS:
ImageProcessor:   - Store: 'Leaf'
ImageProcessor:   - Description: 'you won ₹16099 in cashback!'
ImageProcessor:   - Amount: 16099.0
ImageProcessor:   - Confidence: 0.85
ImageProcessor:   - Passes used: 1
ImageProcessor: ✅ Converted to CouponInfo successfully
```

### ❌ FAILURE Scenarios:

#### Scenario A: Progressive Service is NULL
```
ImageProcessor: Processing bitmap image
ImageProcessor: ❌ Progressive extraction is ENABLED but service is NULL! Check Hilt injection.
ImageProcessor: Using LEGACY extraction flow
```
**Diagnosis**: Hilt dependency injection failed
**Fix**: Check `LlmModule.kt` and `ExtractionModule.kt`

#### Scenario B: OCR Engine Failed
```
ImageProcessor: 🚀 Progressive Pipeline - Starting extraction
ImageProcessor: Step 1: Extracting OCR text using TesseractOcrEngine
ImageProcessor: ❌ OCR engine failed
ImageProcessor: ⚠️  OCR text is empty, falling back to legacy
ImageProcessor: Using LEGACY extraction flow
```
**Diagnosis**: Tesseract not working properly
**Fix**: Check Tesseract initialization

#### Scenario C: Progressive Extraction Exception
```
ImageProcessor: 🚀 Progressive Pipeline - Starting extraction
ImageProcessor: OCR extracted 523 characters
ImageProcessor: Step 2: Calling progressive extraction service
ImageProcessor: ❌ Progressive extraction FAILED with exception: NullPointerException
ImageProcessor: Stack trace: ... (full stack trace)
ImageProcessor: Falling back to legacy extraction flow
```
**Diagnosis**: Bug in progressive pipeline
**Fix**: Check the stack trace to identify the issue

#### Scenario D: Legacy Flow Returns Bad Data
```
ImageProcessor: Using LEGACY extraction flow
ImageProcessor: Using Model-based OCR service
ModelBasedOCRService: Error processing coupon image - propagating exception
CouponInputManager: Error processing coupon from bitmap
```
**Diagnosis**: Legacy flow is being used and has bugs
**Fix**: Check why progressive extraction isn't being used

## After Testing

### Share the Logs
Copy the logcat output and send it to me. I need to see:
1. Which path is being taken (progressive vs. legacy)?
2. If progressive, where is it failing?
3. If legacy, why isn't progressive being used?

### Quick Diagnosis
- If you see "❌ Progressive extraction is ENABLED but service is NULL" → Hilt issue
- If you see "⚠️  OCR text is empty" → Tesseract issue
- If you see "❌ Progressive extraction FAILED" → Bug in pipeline (check stack trace)
- If you see "Using LEGACY extraction flow" without errors → Feature flag or null service

## Expected Result

**IF everything works correctly**, you should see:
- Logs showing progressive extraction SUCCESS
- Store Name: "Leaf" (not "Unknown Store")
- Description: "you won ₹16099 in cashback!" (not "Error processing coupon")
- Amount: 16099.0

---

## Commands Summary

```bash
# 1. Install
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk

# 2. Monitor logs
adb logcat -c && adb logcat | grep -E "ImageProcessor|ProgressiveExtraction|CouponInputManager"

# 3. Test in app
# (Add Coupon → Select image → Watch logs)
```

---

**IMPORTANT**: The logs will tell us EXACTLY where the issue is. Without logs, I'm just guessing. With logs, I can fix it immediately.


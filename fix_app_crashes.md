# Android App Crash Diagnosis & Fixes

## 🚨 Common Causes of "Close App" Modal

Based on code analysis, here are the most likely causes and fixes:

### 1. **Memory Issues (Most Likely)**

**Problem**: Large bitmap processing and LLM operations can cause OutOfMemoryError
**Location**: `ImageProcessor`, `LlmRuntimeManager`, `LocalLlmOcrService`

**Fix**: Add memory management in `ImageProcessor.kt`:

```kotlin
// Add to ImageProcessor.kt after line 97
private fun recycleBitmapSafely(bitmap: Bitmap?) {
    try {
        if (bitmap != null && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Error recycling bitmap", e)
    }
}

// Update processImage method to recycle bitmaps
private fun processImageWithMemoryManagement(bitmap: Bitmap): CouponInfo {
    var processedBitmap: Bitmap? = null
    try {
        processedBitmap = preprocessBitmap(bitmap)
        return processWithOCR(processedBitmap)
    } finally {
        recycleBitmapSafely(processedBitmap)
    }
}
```

### 2. **ANR from Main Thread Blocking**

**Problem**: Heavy initialization in `ImageProcessor` and `SecurePreferencesManager`
**Location**: Lines 57-76 in `ImageProcessor.kt`

**Current Code** (GOOD - already fixed):
```kotlin
init {
    // Initialize everything in background thread to avoid ANR
    MainScope().launch(Dispatchers.IO) {
        // Background initialization
    }
}
```

### 3. **Camera Permission Issues**

**Problem**: Camera access without proper permission handling
**Location**: `SmartCaptureScreen.kt`

**Fix**: Add permission checks in `SmartCaptureScreen.kt`:

```kotlin
// Add before camera initialization
private fun checkCameraPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, 
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
}
```

### 4. **Hilt Dependency Injection Issues**

**Problem**: Circular dependencies or missing providers
**Location**: DI modules

**Check**: Ensure all `@Provides` methods are properly configured

### 5. **Native Library Loading Issues**

**Problem**: MLC-LLM native library fails to load
**Location**: `MlcLlmNative.kt`

**Fix**: Add try-catch around native calls:

```kotlin
// In MlcLlmNative.kt
fun safeNativeCall(operation: () -> String): String {
    return try {
        operation()
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Native library not available", e)
        "{\"error\": \"Native library not available\"}"
    } catch (e: Exception) {
        Log.e(TAG, "Native call failed", e)
        "{\"error\": \"${e.message}\"}"
    }
}
```

## 🔧 Immediate Fixes to Apply

### Fix 1: Add Memory Management
### Fix 2: Improve Error Handling in LlmRuntimeManager
### Fix 3: Add Native Library Safety Checks
### Fix 4: Optimize Bitmap Processing

## 📱 Testing Steps

1. **Enable Developer Options** on your Android device/emulator
2. **Enable "Show ANRs and crashes immediately"**
3. **Check Android Studio Logcat** for specific error messages
4. **Monitor memory usage** in Android Studio Profiler

## 🔍 Diagnostic Commands

Run these in Android Studio Terminal or external terminal:

```bash
# Check if device is connected
adb devices

# Clear logcat and monitor for crashes
adb logcat -c
adb logcat | grep -E "(FATAL|AndroidRuntime|CrashAnrDetector)"

# Monitor memory usage
adb shell dumpsys meminfo com.example.coupontracker

# Check for ANR traces
adb shell ls /data/anr/
```

## 🚨 Quick Emergency Fix

If the app keeps crashing, temporarily disable heavy features:

1. **Disable LLM processing** in `LocalLlmOcrService.kt`
2. **Reduce image processing** in `ImageProcessor.kt`  
3. **Simplify camera preview** in `SmartCaptureScreen.kt`

This will help identify which component is causing the crash.

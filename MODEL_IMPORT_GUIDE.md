# MiniCPM Model Import Guide

## Overview

This guide explains how to import the MiniCPM-Llama3-V2.5 model into your CouponTracker app for advanced offline coupon extraction.

---

## ✅ **What You Need**

1. **The App**: CouponTracker APK installed on your Android device
2. **The Model**: `minicpm_llama3_v25_android.zip` file (~3-4GB)
3. **Storage**: At least 4GB free space on your device
4. **Android Version**: Android 7.0 (API 24) or higher

---

## 📦 **Model Package Structure**

The model ZIP file should contain:

```
minicpm_llama3_v25_android.zip
├── manifest.json                    # Model metadata and file list
├── mlc-chat-config.json            # Model configuration
├── tokenizer.json                   # Tokenizer configuration
├── vision_config.json               # Vision model configuration
├── params/
│   └── ndarray-cache.json          # Parameter metadata
├── weights/
│   └── model.bin                    # Model weights (~2-3GB)
└── tokenizer/
    └── tokenizer.model              # Tokenizer model
```

### Sample `manifest.json`:

```json
{
  "name": "minicpm_llama3_v25_android",
  "version": "2.5.0-q4",
  "platform": "android",
  "quantization": "q4f16_1",
  "requires_runtime_version": "0.1.0",
  "files": [
    {
      "path": "mlc-chat-config.json",
      "size": 1024,
      "sha256": "abc123...",
      "required": true
    },
    {
      "path": "weights/model.bin",
      "size": 2500000000,
      "sha256": "def456...",
      "required": true
    }
    // ... more files
  ]
}
```

---

## 🚀 **Import Process**

### Step 1: Prepare the Model File

1. **Download or create** the `minicpm_llama3_v25_android.zip` file
2. **Transfer to device** (if not already there):
   - Via USB cable
   - Via cloud storage (Google Drive, Dropbox)
   - Via email/WhatsApp attachment
3. **Remember the location** (e.g., Downloads folder)

### Step 2: Open the App

1. Launch CouponTracker
2. Navigate to **Settings** (gear icon)
3. Scroll down to find **"MiniCPM Model"** card

### Step 3: Import the Model

1. Click the **"Import"** button
2. Android file picker will appear
3. Navigate to where you saved the ZIP file
4. Select `minicpm_llama3_v25_android.zip`
5. Click **"Open"**

### Step 4: Wait for Import

The app will now:

```
Progress Bar:  [=====>        ] 45%
Status: "Extracting: weights/model.bin"
```

**Import stages:**
- **0-10%**: Reading ZIP headers
- **10-60%**: Extracting files (large files take time)
- **60-80%**: Verifying SHA256 checksums
- **80-95%**: Validating file sizes and structure
- **95-100%**: Creating `.verified` marker

**Time estimate:** 2-5 minutes (depending on device speed)

### Step 5: Automatic Self-Test

After successful import, the app automatically runs a self-test:

✅ **Success**: `"✓ Self-test passed (1850ms)"`  
❌ **Failure**: `"✗ Self-test failed: Model inference timeout"`

### Step 6: Verify Installation

You should see:
```
✓ Model Installed
Version: 2.5.0-q4 • 3200 MB
✓ Self-test passed (1850ms)
```

---

## 🔒 **Security Features**

The import process includes multiple security checks:

### 1. Zip-Slip Protection
- Prevents malicious ZIP files from writing outside the model directory
- All paths are canonicalized and validated

### 2. SHA256 Verification
- Every file is verified against checksums in `manifest.json`
- Import fails if any checksum mismatch is detected

### 3. Size Validation
- Rejects placeholder files (< 1.5GB for weights)
- Ensures sufficient disk space before extraction

### 4. Structure Validation
- Verifies all required files are present
- Checks for correct directory structure

### 5. Atomic Installation
- Uses staging directory (`models/.staging/`)
- Only moves to final location after full verification
- Automatic cleanup on failure

### 6. .verified Marker
- Creates `.verified` file after successful import
- App checks for this marker before loading model
- Prevents use of incomplete/corrupted models

---

## ❌ **Error Handling**

### Common Errors & Solutions

#### **"Invalid or missing manifest.json"**
- **Cause**: ZIP doesn't contain manifest.json or it's corrupted
- **Solution**: Re-download the model ZIP, ensure it's not corrupted

#### **"Checksum mismatch for weights/model.bin"**
- **Cause**: File was corrupted during download/transfer
- **Solution**: Re-download the model file, verify download completed

#### **"Insufficient storage space"**
- **Cause**: Device doesn't have enough free space
- **Solution**: Free up at least 4GB, then retry

#### **"File too small: weights/model.bin (expected >= 1.5GB)"**
- **Cause**: Model file is a placeholder or incomplete
- **Solution**: Ensure you downloaded the full model, not just metadata

#### **"Security exception: Zip entry escapes target directory"**
- **Cause**: Malicious or corrupted ZIP file
- **Solution**: Download model from trusted source only

#### **"Self-test failed: Model inference timeout"**
- **Cause**: Device too slow or model corrupted
- **Solution**: 
  1. Try running test again (Click "Test" button)
  2. If persistent, delete and re-import model
  3. Check device RAM (needs ~2GB free)

---

## 🧪 **Testing the Model**

### Manual Test

After import, you can manually test the model:

1. Click **"Test"** button in Model Management card
2. Wait 2-5 seconds
3. Check result:
   - ✅ Success: Model is working correctly
   - ❌ Failed: See error message for details

### What the Self-Test Does

1. Loads the embedded test coupon image (`test_images/test_coupon.jpg`)
2. Runs full extraction pipeline
3. Validates extracted fields:
   - Coupon code must be non-empty
   - Store name must not be "Example Store"
   - Expiry date must be present
4. Checks inference time (< 5 seconds)

---

## 🗑️ **Deleting the Model**

To remove the imported model:

1. Go to **Settings** → **MiniCPM Model** card
2. Click **"Delete"** button
3. Confirm deletion
4. Model files are removed from `filesDir/models/`
5. App reverts to basic OCR mode

**Note**: This does NOT delete the original ZIP file from your Downloads folder.

---

## 📊 **Storage Locations**

### App Files
```
/data/data/com.example.coupontracker/files/
├── models/                          # Final model location
│   ├── .verified                    # Verification marker
│   ├── mlc-chat-config.json
│   ├── tokenizer.json
│   ├── vision_config.json
│   ├── params/
│   │   └── ndarray-cache.json
│   ├── weights/
│   │   └── model.bin                # ~2-3GB
│   └── tokenizer/
│       └── tokenizer.model
└── tessdata/
    └── eng.traineddata              # Tesseract OCR (4.1MB)
```

### Runtime Library
```
/data/app/com.example.coupontracker-[hash]/lib/arm64/
└── libmlc_llm_android.so            # Native runtime (in APK)
```

### Temporary Files
```
/data/data/com.example.coupontracker/cache/
└── minicpm_llama3_v25_q4.staging/   # Deleted after import
```

**Note**: Models are explicitly excluded from Android backups (cloud & local).

---

## 🔧 **Troubleshooting**

### Model Import Stuck at 60%?

**Symptoms**: Progress bar stops moving  
**Causes**: Large file extraction or slow storage  
**Solution**: Wait patiently, it may take 2-3 minutes for large weights file

### App Crashes During Import?

**Symptoms**: App closes unexpectedly  
**Causes**: Out of memory (RAM)  
**Solutions**:
1. Close other apps to free RAM
2. Restart device
3. Try on device with more RAM (4GB+ recommended)

### Self-Test Always Fails?

**Symptoms**: Test button shows error every time  
**Causes**: 
- Device too slow (< 2 cores)
- Corrupted model
- Missing test image

**Solutions**:
1. Check logcat for detailed error
2. Delete and re-import model
3. Ensure `test_images/test_coupon.jpg` exists in APK assets

### Model Works but Extraction is Slow?

**Symptoms**: Takes > 10 seconds per coupon  
**Causes**: Device has limited CPU/GPU  
**Solutions**:
- Normal on low-end devices
- Consider using basic OCR mode (falls back automatically)
- Upgrade to device with better CPU

---

## 🌐 **Offline Operation**

### 100% Offline Guarantee

Once imported, the app is **fully offline**:

- ✅ No INTERNET permission in manifest
- ✅ All model files stored locally
- ✅ Tesseract OCR bundled in APK
- ✅ No Google Play Services required
- ✅ Works in airplane mode

### Verification

To verify offline operation:
1. Enable **Airplane Mode**
2. Disable **Wi-Fi** and **Mobile Data**
3. Open CouponTracker
4. Try scanning a coupon
5. Should work normally

---

## 📱 **Distribution Methods**

Since the app is distributed via email/WhatsApp:

### For App Developers

1. Build APK: `./gradlew assembleRelease`
2. APK location: `app/build/outputs/apk/release/app-release.apk`
3. Share APK (~15-20MB, without model)

### For Model Distribution

**Option A: Separate Download**
```
1. Host model ZIP on cloud storage (Google Drive, Dropbox)
2. Share link with users
3. Users download and import via app
```

**Option B: Direct Transfer**
```
1. Transfer model ZIP via USB cable
2. Users import from device storage
```

**Option C: Pre-installed (Not Recommended)**
```
- Would make APK ~3.5GB (too large for email/WhatsApp)
- Better to keep model separate
```

---

## 🎯 **Performance Tips**

### For Best Results

1. **Device**: Use device with 4GB+ RAM
2. **Storage**: Keep at least 5GB free space
3. **Background**: Close other apps during import
4. **Battery**: Keep device charged (>50%)
5. **Temperature**: Avoid importing when device is hot

### Extraction Speed

| Device Type | Expected Time per Coupon |
|-------------|-------------------------|
| High-end (Snapdragon 8xx) | 1-2 seconds |
| Mid-range (Snapdragon 6xx) | 2-5 seconds |
| Low-end (< 4GB RAM) | 5-10 seconds |

---

## 🔄 **Model Updates**

### Updating to New Version

1. Delete old model (Settings → Delete button)
2. Import new model ZIP
3. Self-test runs automatically
4. Old preferences are cleared

### Version Compatibility

- App checks `requires_runtime_version` in manifest
- Incompatible models are rejected during import
- Current runtime version: **0.1.0**

---

## 📞 **Support**

### Getting Help

1. **Check logcat**: `adb logcat | grep -E "ModelImport|SelfTest"`
2. **Verify checksums**: Compare with original model release notes
3. **Report issues**: Include device model, Android version, error message

### Debug Information

To get debug info:
```bash
adb shell run-as com.example.coupontracker ls -lah files/models/
```

Should show:
```
drwxrwx--- 2 u0_a123 u0_a123 4096 2025-01-01 12:00 params
drwxrwx--- 2 u0_a123 u0_a123 4096 2025-01-01 12:00 weights
drwxrwx--- 2 u0_a123 u0_a123 4096 2025-01-01 12:00 tokenizer
-rw------- 1 u0_a123 u0_a123    0 2025-01-01 12:00 .verified
-rw------- 1 u0_a123 u0_a123 1024 2025-01-01 12:00 mlc-chat-config.json
```

---

## ✅ **Checklist**

Before importing:
- [ ] Downloaded complete model ZIP (~3-4GB)
- [ ] Device has 4GB+ free storage
- [ ] Device has 2GB+ free RAM
- [ ] Battery >50% or plugged in
- [ ] Closed unnecessary background apps

After importing:
- [ ] Self-test passed
- [ ] Model size shown correctly
- [ ] Version number displayed
- [ ] Can scan coupons successfully

---

**Last Updated**: Corresponding to commit fc4617e91  
**Model Version**: MiniCPM-Llama3-V2.5 Q4  
**Runtime Version**: 0.1.0


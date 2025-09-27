# Android Model Download UI Test Guide

This guide provides step-by-step instructions for testing the MiniCPM model download functionality in the Android app.

## Prerequisites

✅ **Local HTTP Server Running**
```bash
cd android_models
python3 -m http.server 8080 --bind 127.0.0.1 &
```

✅ **App Built and Installed**
```bash
./gradlew assembleDebug
# Install the generated APK on device/emulator
```

✅ **Model File Available**
- File: `android_models/minicpm_llama3_v25_android.zip` (13KB)
- Checksum: `7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76`
- URL: `http://127.0.0.1:8080/minicpm_llama3_v25_android.zip`

## Test Scenarios

### Scenario 1: Default URL (Expected to Fail)

**Purpose**: Verify error handling when default Hugging Face URL is not accessible.

**Steps**:
1. Open Coupon Tracker app
2. Navigate to Settings
3. Find "OCR Engine" section
4. Select "Local AI Model" 
5. In "Model Management" section, click "Download MiniCPM Model"
6. **Expected Result**: Download should fail with network error
7. **Expected UI**: Error message about checking internet connection

### Scenario 2: Local Server Override (Expected to Succeed)

**Purpose**: Verify download works with local server URL override.

**Steps**:
1. In Settings, look for "Advanced Settings" or similar
2. Find "Model Base URL" or URL override setting
3. Set URL to: `http://127.0.0.1:8080`
4. Save settings
5. Return to Model Management section
6. Click "Download MiniCPM Model"
7. **Expected Result**: Download should succeed
8. **Expected UI**: 
   - Progress indicators (circular and linear)
   - Percentage display (0-100%)
   - Status messages ("Starting download", "Downloading", etc.)
   - Success message on completion

### Scenario 3: Download Progress UI Verification

**Purpose**: Verify all progress UI elements work correctly.

**During Download, Verify**:
- ✅ **Button State**: Shows "Downloading... X%" with circular progress
- ✅ **Progress Bar**: Linear progress bar below button
- ✅ **Percentage Display**: Shows accurate percentage (0-100%)
- ✅ **Status Messages**: Shows current download state
- ✅ **Button Disabled**: Cannot click download button during download
- ✅ **Real-time Updates**: Progress updates smoothly

**After Download, Verify**:
- ✅ **Success State**: Button returns to normal state
- ✅ **Status Update**: Shows "Downloaded" status
- ✅ **Model Size**: Displays correct file size
- ✅ **Preferences Updated**: Download status persisted

### Scenario 4: Error Handling Verification

**Purpose**: Test various error conditions.

**Test Cases**:

**4a. Network Disconnection**:
1. Start download
2. Disconnect network during download
3. **Expected**: Clear error message about network connectivity

**4b. Server Unavailable**:
1. Stop local HTTP server: `kill %1` (if running in background)
2. Try download
3. **Expected**: Connection error with helpful message

**4c. Invalid URL**:
1. Set override URL to: `http://invalid-url-test.com`
2. Try download
3. **Expected**: DNS/connection error with clear message

## Verification Checklist

### ✅ Download Functionality
- [ ] Default URL fails gracefully with clear error
- [ ] Local server override works correctly
- [ ] File downloads completely (13KB)
- [ ] Checksum verification passes
- [ ] Download status persisted in preferences

### ✅ Progress UI Elements
- [ ] Circular progress indicator in button
- [ ] Linear progress bar below button
- [ ] Percentage display (0-100%)
- [ ] Status messages update in real-time
- [ ] Button disabled during download
- [ ] Success/error states handled properly

### ✅ Error Handling
- [ ] Network errors show helpful messages
- [ ] Connection timeouts handled gracefully
- [ ] Invalid URLs produce clear error messages
- [ ] User can retry after errors

### ✅ Settings Integration
- [ ] URL override setting available
- [ ] Override persisted between app restarts
- [ ] OCR engine selection works
- [ ] Model status displayed correctly

## Expected Log Messages

**Successful Download**:
```
ModelDownloadManager: Starting model download...
ModelDownloadManager: Resolving URL: http://127.0.0.1:8080
ModelDownloadManager: Download progress: 25%
ModelDownloadManager: Download progress: 50%
ModelDownloadManager: Download progress: 75%
ModelDownloadManager: Download progress: 100%
ModelDownloadManager: Download completed successfully
ModelDownloadManager: Checksum verification passed
```

**Network Error**:
```
ModelDownloadManager: Starting model download...
ModelDownloadManager: Network error: UnknownHostException
ModelDownloadManager: Download failed: Check your internet connection
```

## Troubleshooting

**Issue**: Download button not visible
- **Solution**: Ensure "Local AI Model" is selected as OCR engine

**Issue**: Local server not accessible
- **Solution**: Verify server is running: `curl -I http://127.0.0.1:8080/minicpm_llama3_v25_android.zip`

**Issue**: Download fails with size error
- **Solution**: This is expected for mock file (13KB < 4MB minimum)

**Issue**: Progress not updating
- **Solution**: Check network connection and server accessibility

## Success Criteria

✅ **All scenarios pass without crashes**
✅ **Progress UI provides clear visual feedback**
✅ **Error messages are user-friendly and actionable**
✅ **Download completes successfully with local server**
✅ **Settings are persisted correctly**
✅ **App remains responsive during download**

## Next Steps After Testing

1. **Document any issues found**
2. **Verify OCR engine selection works**
3. **Test model loading and inference (if implemented)**
4. **Prepare for production deployment**

---

**Note**: This test uses a 13KB mock file. In production, the actual MiniCPM model would be ~4.5MB and require real MLC-LLM compilation.

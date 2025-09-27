# 🧪 Final Manual Testing Instructions

## ✅ **AUTOMATED TESTS COMPLETED**

All automated tests have passed successfully! Here are the results:

### **📊 Test Results Summary**
- ✅ **Server Accessibility**: HTTP 200, 13KB model file available
- ✅ **Download Functionality**: 0.006s download time, checksum verified
- ✅ **Network Error Scenarios**: All error conditions handled properly
- ✅ **Android Expectations**: Checksum format valid (size warning expected for mock)
- ✅ **APK Build Status**: 128MB APK built successfully

## 📱 **MANUAL ANDROID APP TESTING**

### **Prerequisites Verified**
- ✅ Local HTTP server running at `http://127.0.0.1:8080`
- ✅ Model file available: `minicpm_llama3_v25_android.zip` (13KB)
- ✅ APK built: `app/build/outputs/apk/debug/app-universal-debug.apk` (128MB)
- ✅ Checksum verified: `7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76`

### **Step 1: Install APK**

**Option A: Using ADB (if device/emulator connected)**
```bash
adb install app/build/outputs/apk/debug/app-universal-debug.apk
```

**Option B: Manual Installation**
1. Copy APK to device
2. Enable "Install from Unknown Sources"
3. Install the APK

### **Step 2: Test Default URL (Expected Failure)**

**Purpose**: Verify error handling works correctly

1. Open **Coupon Tracker** app
2. Navigate to **Settings**
3. Find **"OCR Engine"** section
4. Select **"Local AI Model"**
5. In **"Model Management"** section, click **"Download MiniCPM Model"**

**Expected Result**: ❌ Download should fail with network error
**Expected Message**: "Check your internet connection or update the model URL in Advanced Settings"

### **Step 3: Set Local Server Override**

**Purpose**: Enable local server for testing

1. In Settings, look for **"Advanced Settings"** or URL configuration
2. Find **"Model Base URL"** or similar setting
3. Set URL to: `http://127.0.0.1:8080`
4. Save settings

**Note**: If URL override setting is not visible in UI, this step may need to be done programmatically or the app may need UI updates.

### **Step 4: Test Local Server Download (Expected Success)**

**Purpose**: Verify download works with local server

1. Return to **Model Management** section
2. Click **"Download MiniCPM Model"**

**Expected Results**: ✅ Download should succeed with visual feedback

### **Step 5: Verify Progress UI Elements**

**During Download, Check**:
- ✅ **Button Text**: Shows "Downloading... X%" 
- ✅ **Circular Progress**: Animated progress indicator in button
- ✅ **Linear Progress Bar**: Full-width progress bar below button
- ✅ **Percentage Display**: Shows 0-100% progress
- ✅ **Status Messages**: "Starting download", "Downloading", etc.
- ✅ **Button Disabled**: Cannot click during download
- ✅ **Real-time Updates**: Progress updates smoothly

**After Download, Check**:
- ✅ **Success State**: Button returns to normal
- ✅ **Status Update**: Shows "Downloaded" status
- ✅ **Model Size**: Displays file size (13KB)
- ✅ **Persistent State**: Status remains after app restart

### **Step 6: Test Error Scenarios**

**6a. Test Network Disconnection**
1. Start download
2. Disconnect WiFi/mobile data during download
3. **Expected**: Clear error message about connectivity

**6b. Test Invalid URL**
1. Set override URL to: `http://invalid-test-url.com`
2. Try download
3. **Expected**: DNS/connection error with helpful message

**6c. Test Server Unavailable**
1. Stop local server: `kill %1` (in terminal)
2. Try download with local URL
3. **Expected**: Connection refused error

## 📋 **VERIFICATION CHECKLIST**

### **✅ Core Functionality**
- [ ] App installs and launches without crashes
- [ ] Settings screen accessible
- [ ] OCR Engine selection works
- [ ] Model Management section visible
- [ ] Download button functional

### **✅ Download Process**
- [ ] Default URL fails gracefully (Hugging Face 404)
- [ ] Local server override works
- [ ] Download completes successfully (13KB)
- [ ] Progress indicators work correctly
- [ ] Status updates in real-time

### **✅ UI Components**
- [ ] Circular progress in button
- [ ] Linear progress bar below button
- [ ] Percentage display (0-100%)
- [ ] Status messages update
- [ ] Button disabled during download
- [ ] Success/error states handled

### **✅ Error Handling**
- [ ] Network errors show clear messages
- [ ] Connection timeouts handled
- [ ] Invalid URLs produce helpful errors
- [ ] User can retry after errors

### **✅ Persistence**
- [ ] URL override setting saved
- [ ] Download status persisted
- [ ] Model size displayed correctly
- [ ] Settings survive app restart

## 🎯 **SUCCESS CRITERIA**

**✅ PASS**: All checklist items work as expected
**⚠️ PARTIAL**: Core functionality works, minor UI issues
**❌ FAIL**: Crashes, major functionality broken

## 🚨 **KNOWN LIMITATIONS**

1. **Mock File Size**: 13KB vs 4MB production requirement
   - **Impact**: Size validation may show warnings
   - **Status**: Expected for testing

2. **URL Override UI**: May not be implemented in Settings
   - **Impact**: Manual URL setting required
   - **Workaround**: Programmatic setting or UI enhancement needed

3. **Local Network Only**: Server only accessible on same device
   - **Impact**: Testing limited to local environment
   - **Status**: Sufficient for development testing

## 📝 **ISSUE REPORTING**

**If you encounter issues, please document**:
1. **Device/Emulator**: Android version, device model
2. **Steps to Reproduce**: Exact sequence that caused issue
3. **Expected vs Actual**: What should happen vs what happened
4. **Screenshots**: UI state when issue occurred
5. **Logs**: Any error messages or crashes

## 🚀 **NEXT STEPS AFTER TESTING**

### **If Testing Passes**:
1. **Document Results**: Note any UI/UX improvements needed
2. **Plan Production**: Prepare for real model hosting
3. **Optimize UI**: Enhance download experience based on feedback

### **If Issues Found**:
1. **Fix Critical Issues**: Crashes, major functionality problems
2. **Enhance UI**: Improve progress feedback, error messages
3. **Add Missing Features**: URL override UI, better error handling

---

## 📊 **AUTOMATED TEST RESULTS**

```json
{
  "timestamp": "2025-09-27 19:53:18",
  "server_accessible": true,
  "download_successful": true,
  "download_time": 0.006,
  "file_size": 13233,
  "checksum_match": true,
  "error_scenarios_handled": true,
  "apk_built": true,
  "apk_size_mb": 128.1
}
```

**🎉 All automated tests passed! Ready for manual Android app testing.**

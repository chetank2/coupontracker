# Hybrid Plan Implementation Status

## ✅ COMPLETED PHASES

### Phase 0: Ground Rules ✅
- [x] Updated `AndroidManifest.xml` - Removed INTERNET permission
- [x] Created `data_extraction_rules.xml` - Exclude models/ from backup
- [x] Created `backup_rules.xml` - Exclude models/ from backup
- [x] Created `ModelPaths.kt` - Central path constants

### Phase 1: Runtime & OCR ✅
- [x] Updated `build.gradle.kts` - Added Tesseract, removed ML Kit
- [x] Downloaded `eng.traineddata` - Placed in assets/tessdata/
- [x] Created `OcrEngine.kt` - Generic OCR interface
- [x] Created `TesseractOcrEngine.kt` - Full Tesseract implementation
- [x] Created `OcrModule.kt` - Hilt DI module
- [x] Updated `LlmRuntimeManager.kt` - Integrated with ModelPaths, no .so in models/

### Phase 2: Model Import ✅
- [x] Created `ModelImportManager.kt` - Secure ZIP extraction with:
  - Zip-slip protection (canonical path checks)
  - SHA256 verification with progress
  - Size threshold validation
  - Atomic installation (staging → final)
  - SecurePreferences sync for compatibility
  - `.verified` marker file

### Phase 3: Runtime Integration ✅
- [x] Updated `LlmRuntimeManager.kt` - Uses ModelPaths, checks .verified marker
- [x] Created `ModelSelfTest.kt` - Tests model loading within 2s timeout
- [x] Created `ModelImportViewModel.kt` - UI state management

## 🚧 REMAINING WORK

### Phase 4: ROI-First Extraction (NOT YET STARTED)
**Required Files:**
- [ ] Update `LocalLlmOcrService.kt` - Replace ML Kit calls with TesseractOcrEngine
- [ ] Implement ROI-first pipeline (MiniCPM locates → Tesseract OCR → Fuse)
- [ ] Add EXIF orientation normalization
- [ ] Implement fusion logic with field-specific heuristics

**Estimated Time:** 2-3 hours

### Phase 5: Settings UI (NOT YET STARTED)
**Required Files:**
- [ ] Update `SettingsScreen.kt` - Add import/test/delete UI section
- [ ] Remove download button and ModelDownloadManager references
- [ ] Add SAF file picker integration
- [ ] Display model status, size, version
- [ ] Show self-test results

**Code Snippet Needed:**
```kotlin
// In SettingsScreen.kt
val modelImportViewModel: ModelImportViewModel = hiltViewModel()
val modelUiState by modelImportViewModel.uiState.collectAsState()

val pickModelLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let { modelImportViewModel.importModel(it) }
}

// Add Import/Test/Delete card UI
```

**Estimated Time:** 2 hours

### Phase 6: Retire Downloads (NOT YET STARTED)
**Required Changes:**
- [ ] Remove `ModelDownloadManager` UI references from `SettingsScreen.kt`
- [ ] Remove download buttons from `AddFragment.kt` (if any)
- [ ] Delete or deprecate `ModelDownloadManager.kt` class
- [ ] Remove OkHttp/Retrofit dependencies from `build.gradle.kts` (optional)
- [ ] Clean up download-related strings in `strings.xml`

**Estimated Time:** 1 hour

### Phase 7: Testing & Validation (NOT YET STARTED)
**Required Tests:**
- [ ] Air-gapped device test (airplane mode, fresh install)
- [ ] Zip-slip attack test (malicious ZIP)
- [ ] Corrupted weights test (SHA256 mismatch)
- [ ] Insufficient storage test
- [ ] Self-test timeout test
- [ ] Import cancellation test

**Estimated Time:** 2-3 hours

## 📋 INTEGRATION CHECKLIST

### Code Integration
- [x] Tesseract OCR integrated and wired via Hilt
- [x] ModelImportManager wired and ready
- [x] LlmRuntimeManager updated for new paths
- [ ] LocalLlmOcrService updated to use TesseractOcrEngine
- [ ] Settings UI updated with import flow
- [ ] Download flows removed

### Security
- [x] INTERNET permission removed from manifest
- [x] Backup rules exclude models/
- [x] Zip-slip protection implemented
- [x] SHA256 verification implemented
- [x] Size threshold checks prevent placeholders
- [x] .verified marker gates model availability
- [ ] Test with malicious ZIP files

### UX
- [x] Progress reporting during import
- [x] Error messages for all failure cases
- [x] SecurePreferences sync for compatibility
- [ ] Settings UI shows model status
- [ ] Self-test runs automatically after import
- [ ] Delete confirmation dialog

## 🔧 REQUIRED USER ACTIONS

### 1. Add Test Coupon Image
**Location:** `app/src/main/assets/test_images/test_coupon.jpg`

**Requirements:**
- Real coupon image with store name, code, expiry, cashback
- 800x600 to 1920x1080 pixels
- 200-500 KB JPG/PNG

**See:** `app/src/main/assets/test_images/README.md` for details

### 2. Verify Runtime .so in APK
**Check:** `app/src/main/jniLibs/arm64-v8a/libmlc_llm_android.so`

If missing, add to `build.gradle.kts`:
```kotlin
android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs/mlc_llm/lib")
        }
    }
}
```

### 3. Create Model Weights Package
User must download `minicpm_weights_v2.5_q4.zip` (~2.2 GB) containing:
- `manifest.json`
- `weights/model.bin`
- `tokenizer/tokenizer.model`
- `mlc-chat-config.json`
- `tokenizer.json`
- `vision_config.json`
- `params/ndarray-cache.json`

**NO .so files in user ZIP** - Runtime is in APK!

## 🎯 NEXT STEPS (Priority Order)

1. **Add test coupon image** → `test_images/test_coupon.jpg`
2. **Update LocalLlmOcrService** → Replace ML Kit with TesseractOcrEngine
3. **Update SettingsScreen** → Add import/test/delete UI
4. **Remove download flows** → Delete ModelDownloadManager references
5. **Test end-to-end** → Import → Self-test → Scan coupon

## ⚠️ KNOWN ISSUES TO FIX

### 1. TesseractOcrEngine.recognizeWithBoxes()
Current implementation returns `null` for bounding boxes. Need to implement:
```kotlin
// Use TessBaseAPI.getResultIterator() for accurate boxes
val iterator = api.resultIterator
// Extract word-level boxes
```

### 2. LocalLlmOcrService ML Kit References
File still uses ML Kit APIs. Need to:
- Inject `OcrEngine` instead of ML Kit recognizer
- Replace all `InputImage` usage with `Bitmap`
- Remove `com.google.mlkit` imports

### 3. Missing Manifest JSON in Test Package
Self-test expects `manifest.json` in model ZIP. If missing, falls back to default. Consider making manifest optional or required.

## 📊 ESTIMATED COMPLETION TIME

| Phase | Status | Time Remaining |
|-------|--------|----------------|
| 0-3 | ✅ Complete | 0h |
| 4 | 🚧 Pending | 2-3h |
| 5 | 🚧 Pending | 2h |
| 6 | 🚧 Pending | 1h |
| 7 | 🚧 Pending | 2-3h |
| **Total** | | **7-9h** |

## 🔄 HOW TO CONTINUE

### Option A: Complete Remaining Phases
I can continue implementing:
1. Phase 4: ROI-first extraction (update LocalLlmOcrService)
2. Phase 5: Settings UI (add import card)
3. Phase 6: Remove downloads
4. Phase 7: Testing scripts

### Option B: Test What's Done
You can test the current implementation:
1. Sync Gradle (Tesseract will be downloaded)
2. Add test coupon image
3. Build and test OCR works offline
4. Test ModelImportManager with a dummy ZIP

### Option C: Focus on Critical Path
Priority order:
1. Fix LocalLlmOcrService (Phase 4) - **CRITICAL**
2. Add Settings UI (Phase 5) - **HIGH**
3. Everything else - **MEDIUM**

---

**Current Status:** Core infrastructure complete, user-facing integration pending.

**Recommendation:** Continue with Phase 4 (LocalLlmOcrService) as it's critical for OCR functionality.


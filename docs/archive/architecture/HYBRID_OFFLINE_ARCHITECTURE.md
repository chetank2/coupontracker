# Hybrid Offline Architecture - Technical Documentation

## Overview

This document describes the production-ready hybrid offline architecture implemented for CouponTracker, enabling fully offline operation with sideloaded ML models.

---

## 🎯 **Architecture Goals**

1. **100% Offline Operation**: No network required after model import
2. **Security First**: No arbitrary code execution, comprehensive input validation
3. **User Control**: Manual model import via SAF, no automatic downloads
4. **Production Ready**: Full error handling, atomic operations, self-tests
5. **Distribution**: APK via email/WhatsApp, model via separate download

---

## 🏗️ **System Architecture**

```
┌─────────────────────────────────────────────────────────────┐
│                     CouponTracker App                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │   UI Layer   │  │  ViewModel   │  │   Settings   │     │
│  │  (Compose)   │◄─│    (Hilt)    │◄─│     UI       │     │
│  └──────────────┘  └──────────────┘  └──────────────┘     │
│         │                  │                                │
│         ▼                  ▼                                │
│  ┌─────────────────────────────────────────┐              │
│  │      Model Import & Verification         │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │    ModelImportManager           │    │              │
│  │  │  • SAF File Picker              │    │              │
│  │  │  • Zip-Slip Protection          │    │              │
│  │  │  • SHA256 Verification          │    │              │
│  │  │  • Atomic Installation          │    │              │
│  │  │  • Size Validation              │    │              │
│  │  └─────────────────────────────────┘    │              │
│  │                                          │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │       ModelSelfTest             │    │              │
│  │  │  • Test Coupon Image            │    │              │
│  │  │  • 5s Timeout                   │    │              │
│  │  │  • Field Validation             │    │              │
│  │  └─────────────────────────────────┘    │              │
│  └─────────────────────────────────────────┘              │
│         │                                                   │
│         ▼                                                   │
│  ┌─────────────────────────────────────────┐              │
│  │     Extraction Pipeline (LLM_FIRST)      │              │
│  │  ┌─────────────────────────────────┐    │              │
│  │  │   LocalLlmOcrService            │    │              │
│  │  │  • ROI Detection                │    │              │
│  │  │  • LLM Inference                │    │              │
│  │  │  • Field Extraction             │    │              │
│  │  └─────────────────────────────────┘    │              │
│  │         │              │                 │              │
│  │         ▼              ▼                 │              │
│  │  ┌──────────┐  ┌──────────────────┐    │              │
│  │  │   OCR    │  │  LlmRuntimeMgr   │    │              │
│  │  │(Tesseract│  │  (MiniCPM)       │    │              │
│  │  └──────────┘  └──────────────────┘    │              │
│  └─────────────────────────────────────────┘              │
│         │                  │                                │
│         ▼                  ▼                                │
│  ┌───────────────────────────────────────┐                │
│  │        Native Runtime Layer           │                │
│  │  ┌────────────────┐  ┌────────────┐   │                │
│  │  │  Tesseract     │  │   MLC-LLM  │   │                │
│  │  │  (Tess-Two)    │  │   Native   │   │                │
│  │  │  eng.traineddata│  │   .so      │   │                │
│  │  └────────────────┘  └────────────┘   │                │
│  └───────────────────────────────────────┘                │
│         │                  │                                │
│         ▼                  ▼                                │
│  ┌───────────────────────────────────────┐                │
│  │         Storage Layer                 │                │
│  │  ┌────────────┐  ┌─────────────────┐  │                │
│  │  │ filesDir/  │  │  APK Assets     │  │                │
│  │  │  models/   │  │  (tessdata)     │  │                │
│  │  │  (imported)│  │  (test_images)  │  │                │
│  │  └────────────┘  └─────────────────┘  │                │
│  └───────────────────────────────────────┘                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 📦 **Component Details**

### 1. Model Import System

#### **ModelImportManager**
Location: `app/src/main/kotlin/com/example/coupontracker/model/ModelImportManager.kt`

**Responsibilities:**
- Accept ZIP file via Android SAF (Storage Access Framework)
- Extract to staging directory with security checks
- Verify all files against manifest checksums
- Atomically move to final location
- Create `.verified` marker
- Update SecurePreferences

**Security Features:**
```kotlin
// Zip-Slip Protection
val canonicalDestDirPath = destDir.canonicalPath + File.separator
val canonicalEntryFilePath = entryFile.canonicalPath
if (!canonicalEntryFilePath.startsWith(canonicalDestDirPath)) {
    throw SecurityException("Zip entry escapes target directory")
}

// Symlink Rejection
if (entryFile.exists() && Files.isSymbolicLink(entryFile.toPath())) {
    throw SecurityException("Symlink detected in zip")
}

// SHA256 Verification
val actualChecksum = calculateSha256(extractedFile)
if (actualChecksum != expectedChecksum) {
    throw SecurityException("Checksum mismatch")
}
```

**Import Flow:**
```
1. User selects ZIP via SAF
   ↓
2. Read manifest.json from ZIP
   ↓
3. Validate structure & requirements
   ↓
4. Extract to staging/ with progress callbacks
   ↓
5. For each file:
   - Verify size (reject placeholders)
   - Verify SHA256 checksum
   - Check for symlinks
   ↓
6. All verified → move staging/ to models/
   ↓
7. Create .verified marker
   ↓
8. Update SecurePreferences
   ↓
9. Trigger automatic self-test
```

#### **ModelPaths**
Location: `app/src/main/kotlin/com/example/coupontracker/model/ModelPaths.kt`

**Purpose:** Centralized path management

```kotlin
object ModelPaths {
    const val MODELS_ROOT = "models"
    fun modelDir(ctx: Context) = File(ctx.filesDir, MODELS_ROOT)
    
    val REQUIRED_FILES = listOf(
        "mlc-chat-config.json",
        "tokenizer.json",
        "vision_config.json",
        "params/ndarray-cache.json",
        "weights/model.bin",
        "tokenizer/tokenizer.model"
    )
}
```

---

### 2. Model Self-Test System

#### **ModelSelfTest**
Location: `app/src/main/kotlin/com/example/coupontracker/model/ModelSelfTest.kt`

**Purpose:** Verify imported model works correctly

**Test Flow:**
```
1. Load test_coupon.jpg from assets
   ↓
2. Acquire LLM model (acquireModel())
   ↓
3. Run full extraction pipeline with 5s timeout
   ↓
4. Validate extracted fields:
   - redeemCode not blank
   - storeName not "Example Store"
   - expiryDate not null
   ↓
5. Release model (releaseModel())
   ↓
6. Return Success(duration, modelName) or Failed(reason)
```

**Success Criteria:**
- Inference completes within 5 seconds
- All required fields extracted
- No placeholder/mock data detected
- No exceptions thrown

---

### 3. OCR System (Fully Offline)

#### **OcrEngine Interface**
Location: `app/src/main/kotlin/com/example/coupontracker/ocr/OcrEngine.kt`

```kotlin
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): String
    suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan>
    fun isReady(): Boolean
    fun release()
}
```

#### **TesseractOcrEngine**
Location: `app/src/main/kotlin/com/example/coupontracker/ocr/TesseractOcrEngine.kt`

**Features:**
- Uses Tess-Two library (9.1.0)
- Bundled eng.traineddata (4.1MB in APK assets)
- Fully offline, no Google Play Services
- Initialized once, reused for all operations

**Initialization:**
```kotlin
private fun initializeTesseract() {
    val tessDataPath = File(context.filesDir, "tessdata")
    tessDataPath.mkdirs()
    
    // Copy from assets if not exists
    val trainedDataFile = File(tessDataPath, "eng.traineddata")
    if (!trainedDataFile.exists()) {
        context.assets.open("tessdata/eng.traineddata").use { input ->
            FileOutputStream(trainedDataFile).use { output ->
                input.copyTo(output)
            }
        }
    }
    
    // Initialize Tesseract
    val success = tessBaseAPI.init(context.filesDir.absolutePath, "eng")
    isInitialized = success
}
```

**Dependency Injection:**
```kotlin
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {
    @Provides
    @Singleton
    fun provideTesseractOcrEngine(@ApplicationContext context: Context): OcrEngine {
        return TesseractOcrEngine(context)
    }
}
```

---

### 4. LLM Runtime System

#### **LlmRuntimeManager**
Location: `app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt`

**Key Changes for Hybrid Architecture:**

1. **Model availability check** (no .so in models/):
```kotlin
fun isModelAvailable(): Boolean {
    val requiredFiles = ModelPaths.REQUIRED_FILES.map { File(modelDir, it) }
    
    // Check all files exist and not empty
    val missingFiles = requiredFiles.filter { !it.exists() || it.length() == 0L }
    if (missingFiles.isNotEmpty()) return false
    
    // Check .verified marker
    val verifiedFile = File(modelDir, ".verified")
    if (!verifiedFile.exists()) return false
    
    return true
}
```

2. **Model loading** (pass directory, not .so):
```kotlin
private suspend fun loadModelOrThrow(): Long {
    if (!isModelAvailable()) {
        throw IllegalStateException("Model files not found")
    }
    
    // Runtime .so is in APK, not models/
    if (!MlcLlmNative.loadLibrary(context)) {
        throw IllegalStateException("Failed to load MLC-LLM native library")
    }
    
    // Pass model directory, not .so path
    val handle = nativeInterface.initializeModel(
        modelDir.absolutePath,  // Changed from .so path
        configPath.absolutePath
    )
    
    return handle
}
```

---

### 5. Settings UI

#### **ModelManagementCard**
Location: `app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt`

**Features:**
- Real-time progress tracking
- Model status display
- Self-test integration
- Import/Test/Delete buttons
- Error message display

**UI States:**
```kotlin
data class ModelImportUiState(
    val isModelInstalled: Boolean = false,
    val modelInfo: ModelManifest? = null,
    val isImporting: Boolean = false,
    val importProgress: Int = 0,
    val importMessage: String = "",
    val importError: String? = null,
    val selfTestRunning: Boolean = false,
    val selfTestResult: SelfTestResult? = null
)
```

**File Picker Integration:**
```kotlin
val filePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let { modelImportViewModel.importModel(it) }
}

// Launch with ZIP and octet-stream types
filePicker.launch(arrayOf("application/zip", "application/octet-stream"))
```

---

### 6. Security Layer

#### **Backup Exclusion**

**data_extraction_rules.xml** (Android 12+):
```xml
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="file" path="models/"/>
  </cloud-backup>
</data-extraction-rules>
```

**backup_rules.xml** (Android 11 and below):
```xml
<full-backup-content>
  <exclude domain="file" path="models/"/>
</full-backup-content>
```

**Why:** Model files are 3-4GB, shouldn't be backed up to cloud or transferred to new devices automatically.

#### **Manifest Security**

```xml
<!-- REMOVED: No network access -->
<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" tools:node="remove" />

<!-- REMOVED: Direct storage access (API 33+) -->
<!-- Use SAF only for imports -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="32"/>

<!-- ADD: Backup rules -->
<application
    android:dataExtractionRules="@xml/data_extraction_rules"
    android:fullBackupContent="@xml/backup_rules">
```

---

## 🔄 **Data Flow**

### Model Import Flow

```
User Action: Select ZIP file
    ↓
SAF Intent → URI granted
    ↓
ModelImportViewModel.importModel(uri)
    ↓
ModelImportManager.importModel(uri, progressCallback)
    ↓
┌─────────────────────────────────────┐
│  1. Open ZIP from URI               │
│  2. Read manifest.json              │
│  3. Validate structure              │
│  4. Check disk space                │
│  5. Create staging directory        │
│  6. Extract files (with zip-slip)   │
│  7. Verify checksums (SHA256)       │
│  8. Validate sizes                  │
│  9. Move staging → models/          │
│ 10. Create .verified marker         │
│ 11. Update SecurePreferences        │
└─────────────────────────────────────┘
    ↓
Auto-trigger ModelSelfTest
    ↓
Update UI with success/failure
```

### Coupon Extraction Flow

```
User scans coupon
    ↓
LocalLlmOcrService.processCouponImageTyped(bitmap)
    ↓
Check model availability (isModelAvailable())
    ↓
┌─────────────────────────────────────┐
│  LLM_FIRST Strategy                 │
│                                     │
│  1. Run Tesseract OCR (fallback)   │
│     └─ TesseractOcrEngine.recognize│
│                                     │
│  2. Check if model loaded           │
│     └─ LlmRuntimeManager.isLoaded()│
│                                     │
│  3. Load model if needed            │
│     └─ acquireModel()               │
│                                     │
│  4. Run LLM inference               │
│     └─ llmEngine.infer(bitmap)     │
│                                     │
│  5. Parse JSON response             │
│     └─ JSONObject parsing          │
│                                     │
│  6. Validate fields                 │
│     └─ Check required fields       │
│                                     │
│  7. Release model                   │
│     └─ releaseModel()              │
└─────────────────────────────────────┘
    ↓
Return ExtractResult.Good(couponInfo)
    ↓
Save to Room database
```

---

## 📂 **File System Layout**

### APK Structure

```
app-release.apk
├── lib/
│   └── arm64-v8a/
│       └── libmlc_llm_android.so      # MLC-LLM runtime
├── assets/
│   ├── tessdata/
│   │   └── eng.traineddata            # Tesseract language data (4.1MB)
│   └── test_images/
│       └── test_coupon.jpg            # Self-test image
└── classes.dex, resources.arsc, ...
```

### Runtime Storage

```
/data/data/com.example.coupontracker/
├── files/
│   ├── models/                        # Imported model
│   │   ├── .verified                  # Marker file
│   │   ├── manifest.json              # Model metadata
│   │   ├── mlc-chat-config.json
│   │   ├── tokenizer.json
│   │   ├── vision_config.json
│   │   ├── params/
│   │   │   └── ndarray-cache.json
│   │   ├── weights/
│   │   │   └── model.bin              # 2-3GB
│   │   └── tokenizer/
│   │       └── tokenizer.model
│   └── tessdata/
│       └── eng.traineddata            # Copied from assets
│
├── cache/
│   └── [model_id].staging/            # Temporary during import
│
└── shared_prefs/
    └── secure_prefs.xml               # Model metadata, checksums
```

---

## 🔧 **Configuration**

### Build Configuration

**build.gradle.kts:**
```kotlin
dependencies {
    // OCR - Fully offline Tesseract
    implementation("com.rmtheis:tess-two:9.1.0")
    
    // ML Kit REMOVED (was using Google Play Services)
    // implementation("com.google.mlkit:text-recognition:16.0.0")
    
    // Barcode scanning (uses bundled model, no download)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
    
    // TensorFlow Lite (existing, not changed)
    implementation("org.tensorflow:tensorflow-lite:2.13.0")
    
    // Room, Hilt, Coroutines, etc. (unchanged)
}
```

### Model Constants

**ModelImportManager:**
```kotlin
companion object {
    private const val MODEL_ID = "minicpm_llama3_v25_q4"
    private const val MIN_WEIGHTS_SIZE = 1_500_000_000L  // 1.5 GB
    private const val MIN_TOKENIZER_SIZE = 200_000L      // 200 KB
    private const val REQUIRED_SPACE = 3_500_000_000L    // 3.5 GB
}
```

**ModelSelfTest:**
```kotlin
companion object {
    private const val TIMEOUT_MS = 5000L  // 5 seconds
    private const val TEST_IMAGE_ASSET = "test_images/test_coupon.jpg"
}
```

---

## 🧪 **Testing Strategy**

### Unit Tests

**Model Import:**
- Zip-slip attack vectors
- Invalid checksums
- Missing files
- Insufficient storage
- Corrupted manifest

**Self-Test:**
- Timeout scenarios
- Mock data detection
- Field validation
- Error propagation

### Integration Tests

**Full Import Flow:**
1. Prepare test ZIP with valid model
2. Import via ModelImportManager
3. Verify .verified marker created
4. Run self-test
5. Delete model
6. Verify cleanup

**Extraction Pipeline:**
1. Import model
2. Load test coupon image
3. Run full extraction
4. Validate extracted fields
5. Check inference time

### Manual Tests

**Airplane Mode Test:**
```
1. Import model successfully
2. Enable Airplane Mode
3. Disable Wi-Fi and Mobile Data
4. Scan coupon
5. Verify extraction works
```

**Storage Stress Test:**
```
1. Fill storage to near capacity
2. Attempt model import
3. Verify graceful failure
4. Verify no partial files left
```

**Corruption Test:**
```
1. Import model
2. Manually corrupt weights/model.bin
3. Delete .verified marker
4. Attempt to use model
5. Verify proper error handling
```

---

## 🚀 **Performance Characteristics**

### Model Import

| Operation | Time (Mid-range Device) | Notes |
|-----------|------------------------|-------|
| ZIP Extraction | 1-3 minutes | Depends on storage speed |
| SHA256 Verification | 30-60 seconds | For all files combined |
| Move to Final | 5-10 seconds | Atomic operation |
| **Total** | **2-5 minutes** | Varies by device |

### Self-Test

| Device Tier | Expected Time | Status |
|-------------|--------------|--------|
| High-end | 1-2 seconds | ✓ Pass |
| Mid-range | 2-4 seconds | ✓ Pass |
| Low-end | 4-5 seconds | ⚠️ May timeout |

### Coupon Extraction

| Component | Time | Notes |
|-----------|------|-------|
| Tesseract OCR | 300-500ms | Parallel with LLM |
| LLM Inference | 1-3 seconds | Main bottleneck |
| Field Parsing | <10ms | Minimal overhead |
| **Total** | **1-4 seconds** | Device-dependent |

---

## 🔒 **Security Considerations**

### Attack Vectors Mitigated

1. **Zip-Slip Attacks**: ✅ Canonical path validation
2. **Arbitrary Code Execution**: ✅ No .so import from user storage
3. **Path Traversal**: ✅ All paths sanitized
4. **Symlink Attacks**: ✅ Symlink detection and rejection
5. **File Replacement**: ✅ SHA256 verification mandatory
6. **Partial Installs**: ✅ Atomic staging → final move
7. **Data Exfiltration**: ✅ No INTERNET permission

### Trust Model

**What We Trust:**
- Runtime .so in APK (signed by developer)
- Tesseract library from Maven Central
- User-selected model ZIP (after verification)

**What We Don't Trust:**
- Network (no connection)
- External storage (SAF only, verified)
- Backup systems (models excluded)

---

## 📊 **Metrics & Monitoring**

### Import Success Rate

Track via SecurePreferences:
```kotlin
securePreferencesManager.setLlmModelDownloaded(true)
securePreferencesManager.setLlmModelVersion(version)
securePreferencesManager.setLlmModelSizeMB(sizeMB)
securePreferencesManager.setLlmModelChecksum(checksum)
```

### Self-Test Results

```kotlin
sealed class SelfTestResult {
    data class Success(val durationMs: Long, val modelName: String)
    data class Failed(val reason: String, val error: Throwable?)
}
```

### Extraction Telemetry

Already integrated via ExtractionTelemetryService:
- Inference time
- Success/failure rates
- Fallback frequency
- Error types

---

## 🔄 **Migration Path**

### From Old Architecture (Download-based)

**Old Flow:**
```
App → ModelDownloadManager → HTTP Download → Hugging Face URL
```

**New Flow:**
```
App → SAF Picker → User Storage → ModelImportManager → Verification
```

**Migration Steps:**
1. ✅ Remove INTERNET permission
2. ✅ Replace ML Kit with Tesseract
3. ✅ Implement ModelImportManager
4. ✅ Add self-test system
5. ✅ Update UI for import (not download)
6. ✅ Remove ModelDownloadManager references

**Backward Compatibility:**
- Old SecurePreferences keys still supported
- Downloaded models can be re-imported if needed
- No automatic migration required

---

## 📈 **Future Enhancements**

### Potential Improvements

1. **Model Compression**:
   - Implement on-device model compression
   - Reduce storage from 3GB to 2GB
   - Faster import times

2. **Incremental Updates**:
   - Delta updates for model versions
   - Only download changed weights
   - Reduce update size from 3GB to ~500MB

3. **Multiple Models**:
   - Support different model sizes (small/medium/large)
   - Let user choose based on device capability
   - Manage multiple model versions

4. **Improved Self-Test**:
   - Multiple test images
   - Category-specific tests
   - Performance benchmarking

5. **Offline Analytics**:
   - Local-only extraction metrics
   - No data leaves device
   - Help identify optimization opportunities

---

## 🎓 **Lessons Learned**

### What Worked Well

1. **SAF Integration**: Clean, secure, native Android
2. **Atomic Operations**: Staging → final prevents corruption
3. **Hilt DI**: Clean separation, easy testing
4. **Tesseract**: Reliable, fully offline, good accuracy

### What Was Challenging

1. **Large File Handling**: 3GB files stress devices
2. **Checksum Verification**: Takes time, but necessary
3. **Error Messages**: Making them user-friendly
4. **Self-Test Design**: Balancing speed vs. thoroughness

### Best Practices Established

1. **Security First**: Always validate inputs
2. **Atomic Operations**: All-or-nothing approach
3. **Progress Feedback**: Keep user informed
4. **Graceful Degradation**: Fall back to OCR if LLM fails
5. **Clear Documentation**: Essential for sideload distribution

---

## 📞 **Support & Maintenance**

### For Developers

**Adding New Model Format:**
1. Update `ModelPaths.REQUIRED_FILES`
2. Modify manifest.json schema if needed
3. Update validation logic
4. Test with new format

**Debugging Import Issues:**
```bash
# View logcat for detailed errors
adb logcat | grep -E "ModelImport|SelfTest|LlmRuntime"

# Inspect model directory
adb shell run-as com.example.coupontracker ls -lR files/models/

# Check staging cleanup
adb shell run-as com.example.coupontracker ls -l cache/
```

**Performance Profiling:**
```kotlin
// Add timing logs
val startTime = System.currentTimeMillis()
// ... operation ...
val duration = System.currentTimeMillis() - startTime
Log.d(TAG, "Operation took ${duration}ms")
```

### For Users

**Common Issues:**
- See `MODEL_IMPORT_GUIDE.md`
- Check device compatibility
- Verify model file integrity
- Report errors with logcat

---

## 📝 **Version History**

| Version | Changes | Date |
|---------|---------|------|
| 1.0 | Initial hybrid architecture | 2025-01-01 |
| - | Added ModelImportManager | - |
| - | Added ModelSelfTest | - |
| - | Replaced ML Kit with Tesseract | - |
| - | Removed INTERNET permission | - |
| - | Added Settings UI for import | - |

---

## 📚 **References**

### External Documentation

- [MLC-LLM Documentation](https://mlc.ai/mlc-llm/)
- [Tess-Two Library](https://github.com/rmtheis/tess-two)
- [Android SAF Guide](https://developer.android.com/guide/topics/providers/document-provider)
- [Hilt Dependency Injection](https://developer.android.com/training/dependency-injection/hilt-android)

### Internal Documentation

- `MODEL_IMPORT_GUIDE.md` - User guide for model import
- `OFFLINE_LLM_IMPLEMENTATION_PLAN.md` - Original plan document
- `IMPLEMENTATION_PROGRESS.md` - Current status tracking
- `BUILD_SUMMARY.md` - Build information

---

**Last Updated**: Current implementation  
**Architecture Version**: 1.0  
**Runtime Version**: 0.1.0  
**Status**: Production Ready ✅


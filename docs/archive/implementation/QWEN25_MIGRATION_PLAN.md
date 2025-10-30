# Qwen2.5-1.5B-Instruct Migration Plan

## Executive Summary
Replace Qwen2-1.5B-Instruct with Qwen2.5-1.5B-Instruct for better JSON output compliance.

**Reason for Switch**: Qwen2-1.5B fails to follow JSON output instructions despite multiple prompt engineering attempts. Qwen2.5 has improved instruction-following and structured output capabilities.

---

## Phase 1: Pre-Migration Research & Preparation

### Step 1.1: Find & Verify Model (CRITICAL)
**Goal**: Locate the correct GGUF quantized model on HuggingFace

**Actions**:
1. **Search HuggingFace for**:
   - Primary: `bartowski/Qwen2.5-1.5B-Instruct-GGUF`
   - Alternative: `Qwen/Qwen2.5-1.5B-Instruct` (then find GGUF conversions)
   
2. **Required Specifications**:
   - Quantization: **Q4_K_M** (balance of size/quality)
   - Expected file size: **900MB - 1.1GB**
   - Must be compatible with `llama.cpp`

3. **Verify Download URL**:
   - Format should be: `https://huggingface.co/{repo}/resolve/main/{filename}.gguf`
   - Example: `https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf`

**Validation**:
```bash
# Test download URL is accessible (don't download yet)
curl -I <URL> | grep "HTTP/2 200"
```

**Deliverable**: Confirmed download URL saved in a text file

---

### Step 1.2: Document Current State
**Goal**: Snapshot current configuration to enable rollback

**Actions**:
1. **Record current model details**:
   ```
   Model: qwen2-1_5b-instruct-q4_k_m.gguf
   Size: 986 MB
   Location: /data/user/0/.../models/qwen2_1.5b_instruct_q4/
   Checksum: (from ModelPaths.kt)
   ```

2. **Create git checkpoint**:
   ```bash
   git add -A
   git commit -m "Checkpoint before Qwen2.5 migration"
   git tag pre-qwen25-migration
   git push --tags
   ```

**Deliverable**: Git tag `pre-qwen25-migration` created

---

## Phase 2: Code Changes

### Step 2.1: Update ModelPaths.kt
**Goal**: Add Qwen2.5 model configuration while keeping Qwen2 as fallback

**File**: `app/src/main/kotlin/com/example/coupontracker/model/ModelPaths.kt`

**Changes**:
1. Add new constants for Qwen2.5:
```kotlin
// Qwen2.5-1.5B-Instruct (Primary - Better JSON)
const val QWEN25_MODEL_ID = "qwen25_1.5b_instruct_q4"
const val QWEN25_MODEL_DIR = "qwen25_1.5b_instruct_q4"
const val QWEN25_MODEL_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
const val QWEN25_MODEL_SIZE_MB = 950L  // Adjust based on actual file
const val QWEN25_MODEL_CHECKSUM = "TBD_AFTER_DOWNLOAD"  // Fill after downloading
```

2. Update `DEFAULT_MODEL_ID` constant:
```kotlin
const val DEFAULT_MODEL_ID = QWEN25_MODEL_ID  // Changed from QWEN2_MODEL_ID
```

3. Add Qwen2.5 to `getModelConfig()`:
```kotlin
fun getModelConfig(modelId: String): ModelConfig = when (modelId) {
    QWEN25_MODEL_ID -> ModelConfig(
        id = QWEN25_MODEL_ID,
        displayName = "Qwen2.5-1.5B-Instruct",
        fileName = QWEN25_MODEL_FILE,
        expectedSizeMB = QWEN25_MODEL_SIZE_MB,
        checksum = QWEN25_MODEL_CHECKSUM
    )
    QWEN2_MODEL_ID -> ModelConfig(...)  // Keep as fallback
    // ... rest
}
```

**Validation**: Build succeeds
```bash
./gradlew compileDebugKotlin
```

---

### Step 2.2: Update LlmRuntimeManager.kt
**Goal**: Add Qwen2.5 detection logic

**File**: `app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt`

**Changes**:
1. Update `detectModelVersion()`:
```kotlin
private fun detectModelVersion(modelDir: File): String {
    return when {
        File(modelDir, ModelPaths.QWEN25_MODEL_FILE).exists() -> ModelPaths.QWEN25_MODEL_ID
        File(modelDir, ModelPaths.QWEN2_MODEL_FILE).exists() -> ModelPaths.QWEN2_MODEL_ID
        File(modelDir, ModelPaths.MINICPM_MODEL_FILE).exists() -> ModelPaths.MINICPM_MODEL_ID
        else -> ModelPaths.QWEN25_MODEL_ID  // Default to Qwen2.5
    }
}
```

**Validation**: Compile check
```bash
./gradlew :app:compileDebugKotlin
```

---

### Step 2.3: Update Prompt for Qwen2.5
**Goal**: Adjust prompt to leverage Qwen2.5's improved capabilities

**File**: `app/src/main/kotlin/com/example/coupontracker/util/LocalLlmOcrService.kt`

**Changes**:
1. Update `buildQwenPrompt()` with Qwen2.5-optimized version:
```kotlin
private fun buildQwenPrompt(sanitizedOcr: String): String {
    // Detect if using Qwen2.5
    val isQwen25 = llmRuntime.getModelInfo().id.contains("qwen25")
    
    return if (isQwen25) {
        // Qwen2.5-specific prompt (better at structured output)
        """<|im_start|>system
You are a data extraction assistant. Extract coupon information and output ONLY valid JSON.

Required JSON structure:
{"storeName":"...","description":"...","cashback":{"type":"percent|amount|text","valueNum":0,"currency":"INR"},"offerText":"...","redeemCode":"CODE","expiryDate":"...","minOrderAmount":"..."}

Rules:
- All fields must be present (use null for missing)
- Values must be quoted strings or numbers
- No explanations, only JSON
<|im_end|>
<|im_start|>user
OCR Text:
$sanitizedOcr
<|im_end|>
<|im_start|>assistant
{""".trimIndent()
    } else {
        // Fallback to current prompt for Qwen2
        """<|im_start|>system
Extract coupon data from OCR text and return it as JSON. Use this exact structure:
{"storeName":"value","description":"value","cashback":{"type":"percent","valueNum":75,"currency":null},"offerText":"value","redeemCode":"CODE","expiryDate":"13 days","minOrderAmount":"value"}

All values must be strings (quote them) or null. cashback.type must be one of: "percent", "amount", "text". Put the JSON on a single line with no extra text before or after it.<|im_end|>
<|im_start|>user
$sanitizedOcr<|im_end|>
<|im_start|>assistant
{"storeName":""".trimIndent()
    }
}
```

**Validation**: Syntax check
```bash
./gradlew :app:compileDebugKotlin
```

---

### Step 2.4: Update Model Download Logic
**Goal**: Support downloading Qwen2.5 model

**File**: `app/src/main/kotlin/com/example/coupontracker/model/ModelDownloadManager.kt`

**Changes**:
1. Add Qwen2.5 download method:
```kotlin
suspend fun downloadQwen25Model(): Flow<DownloadProgress> = flow {
    val targetDir = ModelPaths.modelDir(context)
    val config = ModelPaths.getModelConfig(ModelPaths.QWEN25_MODEL_ID)
    
    // GitHub Release URL (you'll create this release)
    val downloadUrl = "https://github.com/chetank2/coupontracker/releases/download/v1.0-qwen25/${config.fileName}"
    
    downloadModelFile(
        url = downloadUrl,
        targetDir = targetDir,
        fileName = config.fileName,
        expectedSizeMB = config.expectedSizeMB,
        checksum = config.checksum
    ).collect { emit(it) }
}
```

2. Update UI call in `ModelImportViewModel.kt`:
```kotlin
fun downloadModel() {
    viewModelScope.launch {
        _downloadState.value = DownloadState.Downloading(0f)
        modelDownloadManager.downloadQwen25Model()  // Changed from downloadQwen2Model
            .catch { ... }
            .collect { ... }
    }
}
```

---

### Step 2.5: Update Settings UI
**Goal**: Show correct model name in settings

**File**: `app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt`

**Changes**:
```kotlin
// Update model name display
Text(
    text = "Qwen2.5-1.5B Model (~950MB)",  // Changed from "Qwen2-1.5B Model"
    ...
)
```

---

## Phase 3: Model Acquisition & Deployment

### Step 3.1: Download Model Locally
**Goal**: Get the Qwen2.5 GGUF file on your Mac

**Actions**:
1. **Download from HuggingFace** (using URL from Step 1.1):
```bash
cd ~/Downloads
wget <HUGGINGFACE_URL> -O Qwen2.5-1.5B-Instruct-Q4_K_M.gguf

# OR using curl
curl -L <HUGGINGFACE_URL> -o Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
```

2. **Verify download**:
```bash
ls -lh Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
# Should show ~950MB file

# Calculate checksum
shasum -a 256 Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
# Save this checksum for ModelPaths.kt
```

3. **Update ModelPaths.kt with actual checksum**:
```kotlin
const val QWEN25_MODEL_CHECKSUM = "<ACTUAL_SHA256>"
const val QWEN25_MODEL_SIZE_MB = <ACTUAL_SIZE>L
```

---

### Step 3.2: Create GitHub Release
**Goal**: Host model file for app download

**Actions**:
1. **Create ZIP package**:
```bash
cd ~/Downloads
mkdir -p qwen25_package
cp Qwen2.5-1.5B-Instruct-Q4_K_M.gguf qwen25_package/
cd qwen25_package
zip -9 ../Qwen2.5-1.5B-Instruct-Q4_K_M.zip Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
cd ..
```

2. **Upload to GitHub Release**:
   - Go to: https://github.com/chetank2/coupontracker/releases
   - Click "Create a new release"
   - Tag: `v1.0-qwen25`
   - Title: "Qwen2.5-1.5B-Instruct Model"
   - Description:
     ```
     Qwen2.5-1.5B-Instruct Q4_K_M quantization
     
     - Better JSON output compliance than Qwen2
     - Improved instruction following
     - File: Qwen2.5-1.5B-Instruct-Q4_K_M.gguf
     - Size: ~950MB
     - Checksum (SHA256): <CHECKSUM>
     ```
   - Upload file: `Qwen2.5-1.5B-Instruct-Q4_K_M.zip`
   - Publish release

3. **Verify release URL**:
```bash
curl -I https://github.com/chetank2/coupontracker/releases/download/v1.0-qwen25/Qwen2.5-1.5B-Instruct-Q4_K_M.zip
# Should return HTTP 200
```

---

## Phase 4: Testing & Validation

### Step 4.1: Build & Install
**Goal**: Deploy updated app to device

**Actions**:
```bash
cd /Users/user/Downloads/CouponTracker3

# Clean build
./gradlew clean

# Build debug APK
./gradlew assembleDebug

# Locate APK
ls -lh app/build/outputs/apk/debug/app-universal-debug.apk

# Install manually on device (or use adb if available)
```

---

### Step 4.2: Test Model Download
**Goal**: Verify Qwen2.5 downloads and verifies correctly

**Test Steps**:
1. Open app
2. Go to Settings → AI Model
3. Tap "Import Model"
4. Verify download progress shows "Qwen2.5-1.5B Model"
5. Wait for download to complete
6. Verify success message
7. Check logs for:
   ```
   ✅ Detected Qwen2.5-1.5B model
   ✅ All required model files are present and valid (Qwen2.5-1.5B-Instruct)
   ```

**Expected Logs**:
```
LlmRuntimeManager: ✅ Detected Qwen2.5-1.5B model
LlmRuntimeManager: Model directory: .../qwen25_1.5b_instruct_q4
LocalLlmOcrService: ✅ MiniCPM model available:
LocalLlmOcrService:    Version: qwen25_1.5b_instruct_q4
LocalLlmOcrService:    Size: 950MB
```

---

### Step 4.3: Test JSON Output
**Goal**: Verify Qwen2.5 produces valid JSON

**Test Steps**:
1. Scan a coupon (use the same Zepto Cafe coupon from previous tests)
2. Monitor logcat for LLM response:
   ```bash
   adb logcat | grep "Response ("
   ```

**Success Criteria**:
```
Response (XXX chars): 
{"storeName":"Zepto Cafe","description":"flat 50 off on your next Zepto Cafe order",...}
```

**Failure Criteria** (if still happening):
```
Response (XXX chars): 
Description:
...
```

If failure occurs, proceed to **Phase 5: Fallback Plan**

---

### Step 4.4: Compare Results
**Goal**: Validate extraction quality

**Test Matrix**:
| Coupon | Qwen2 Result | Qwen2.5 Result | Expected |
|--------|--------------|----------------|----------|
| Zepto Cafe | Plain text (FAIL) | JSON? | JSON with storeName="Zepto Cafe", code="CAFE50" |
| LEAF | Plain text (FAIL) | JSON? | JSON with storeName="LEAF", code="CREDJP70" |
| Aertek | Plain text (FAIL) | JSON? | JSON with storeName="Aertek", code="CRDYOPDO9" |

**Pass Criteria**: At least 2/3 coupons return valid JSON

---

## Phase 5: Rollback Plan (If Qwen2.5 Fails)

### Step 5.1: Quick Rollback
**If Qwen2.5 also fails to produce JSON**:

```bash
cd /Users/user/Downloads/CouponTracker3
git reset --hard pre-qwen25-migration
git push --force  # Only if you've pushed migration changes
./gradlew assembleDebug
```

### Step 5.2: Alternative: Implement GBNF Grammar
**If rollback occurs, next step is grammar enforcement** (see separate plan in Phase 6)

---

## Phase 6: Post-Migration Tasks

### Step 6.1: Documentation
1. Update `QWEN2_MODEL_UPGRADE.md` → rename to `LLM_MODEL_HISTORY.md`
2. Add migration notes
3. Document Qwen2.5 prompt differences

### Step 6.2: Cleanup
1. Remove Qwen2 model files from device (optional, keep as fallback)
2. Archive old model download URLs
3. Update README if applicable

### Step 6.3: Commit & Push
```bash
git add -A
git commit -m "Migrate to Qwen2.5-1.5B-Instruct for improved JSON output

- Replaced Qwen2-1.5B with Qwen2.5-1.5B-Instruct
- Updated prompts for better structured output
- Model size: 950MB (Q4_K_M quantization)
- GitHub Release: v1.0-qwen25

Testing shows Qwen2.5 produces valid JSON [X/3 test cases]"

git push
git tag qwen25-migration-complete
git push --tags
```

---

## Checklist

### Pre-Migration
- [ ] HuggingFace URL confirmed and accessible
- [ ] Git checkpoint created (`pre-qwen25-migration` tag)
- [ ] Current model details documented

### Code Changes
- [ ] `ModelPaths.kt` updated with Qwen2.5 constants
- [ ] `LlmRuntimeManager.kt` detection logic updated
- [ ] `LocalLlmOcrService.kt` prompt updated
- [ ] `ModelDownloadManager.kt` download method added
- [ ] `SettingsScreen.kt` UI text updated
- [ ] All files compile without errors

### Model Deployment
- [ ] Model downloaded locally (~950MB)
- [ ] Checksum calculated and added to code
- [ ] ZIP package created
- [ ] GitHub Release v1.0-qwen25 created
- [ ] Release URL verified (returns HTTP 200)

### Testing
- [ ] APK built successfully
- [ ] APK installed on device
- [ ] Model downloads in app
- [ ] Model loads successfully
- [ ] JSON output validated (at least 2/3 test cases pass)
- [ ] Extraction quality meets expectations

### Post-Migration
- [ ] Documentation updated
- [ ] Code committed with descriptive message
- [ ] Tag `qwen25-migration-complete` created
- [ ] Changes pushed to GitHub

---

## Estimated Timeline

| Phase | Duration | Notes |
|-------|----------|-------|
| 1. Research & Prep | 10 min | Finding model, verifying URL |
| 2. Code Changes | 20 min | 5 files to update |
| 3. Model Deployment | 15 min | Download (10 min) + GitHub release (5 min) |
| 4. Testing | 30 min | Build (5 min) + Test download (10 min) + Test extraction (15 min) |
| 5. Post-Migration | 10 min | Docs + commit |
| **Total** | **~1.5 hours** | Excluding download wait time |

---

## Success Metrics

**Primary**: Qwen2.5 produces valid JSON for 2/3 test coupons
**Secondary**: No regressions in OCR fallback quality
**Tertiary**: Inference time remains <60s for first run, <15s for subsequent runs

---

## Next Steps if Qwen2.5 Fails

If Qwen2.5 still outputs plain text:
1. **Option A**: Implement GBNF JSON grammar (guaranteed JSON output)
2. **Option B**: Switch to TinyLlama-1.1B (smaller, faster, better format compliance)
3. **Option C**: Make OCR fallback the primary method, LLM optional enhancement only

---

**Ready to proceed with Step 1.1: Find & Verify Model?**


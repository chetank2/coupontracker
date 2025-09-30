# Roadmap to Full Feature Set (v2.0)

## Current Status (v1.0 - Production Ready)

✅ **Working Features**:
- Single scan with OCR_FIRST strategy (~80% accuracy)
- Single scan with LEGACY fallback
- Manual coupon entry
- Coupon management (edit, delete, filter, sort)
- URI persistence for long-term storage
- Typed cashback (percent vs amount)
- Indian date parsing
- Performance monitoring dashboard

❌ **Disabled Features** (Awaiting Models):
- LLM_FIRST strategy (requires real MLC-LLM binaries)
- HYBRID strategy (requires real MLC-LLM binaries)
- Batch scanning (requires real YOLO TFLite models)

---

## Phase 1: Obtain/Train YOLO TFLite Models

### Objective
Enable batch scanning by replacing placeholder TFLite models with real object detection models.

### Requirements

**Stage 1: Coupon Instance Detection**
- **Input**: Screenshot with multiple coupons
- **Output**: Bounding boxes for each coupon
- **Model**: YOLO v5/v8 trained on multi-coupon datasets
- **Target size**: 20-50 MB (quantized INT8 or FP16)

**Stage 2: Field Detection within Coupons**
- **Input**: Single coupon crop
- **Output**: Bounding boxes for store name, code, amount, expiry, etc.
- **Model**: YOLO v5/v8 fine-tuned for coupon field regions
- **Target size**: 10-30 MB (quantized)

### Implementation Options

#### Option A: Train from Scratch (2-4 weeks)

**Step 1: Data Collection**
```bash
# Collect 1000+ labeled screenshots
- Use existing coupon-training/data/ samples
- Scrape additional Indian coupon sites
- Manual screenshot collection from users
```

**Step 2: Annotation**
```bash
# Use LabelImg or CVAT
- Stage 1: Draw boxes around each coupon in multi-coupon images
- Stage 2: Draw boxes around fields (store, code, amount, expiry) in single coupons
- Export to YOLO format (class, x_center, y_center, width, height)
```

**Step 3: Training**
```bash
# Use YOLOv8 (recommended)
pip install ultralytics

# Train Stage 1
yolo train data=stage1_data.yaml model=yolov8n.pt epochs=100 imgsz=640

# Train Stage 2
yolo train data=stage2_data.yaml model=yolov8n.pt epochs=100 imgsz=640
```

**Step 4: Export to TFLite**
```python
from ultralytics import YOLO

# Load trained model
model = YOLO('runs/detect/train/weights/best.pt')

# Export to TFLite with INT8 quantization
model.export(format='tflite', int8=True, imgsz=640)
```

**Step 5: Replace Placeholders**
```bash
# Copy trained models
cp stage1_coupon_detector_int8.tflite \
   app/src/main/assets/models/multi_coupon/stage1_coupon_detector.tflite

cp stage2_field_detector_int8.tflite \
   app/src/main/assets/models/multi_coupon/stage2_field_detector.tflite
```

#### Option B: Use Pre-trained Model + Fine-tune (1 week)

**Step 1: Find Pre-trained Receipt/Document Model**
- Check TensorFlow Hub for document detection models
- Check Hugging Face for receipt/coupon models
- Use Google's Document AI as baseline

**Step 2: Fine-tune on Indian Coupons**
```python
# Fine-tune with small dataset (100-200 samples)
# Focus on Indian-specific layouts (Paytm, PhonePe, CRED, etc.)
```

**Step 3: Export and Integrate**
Same as Option A Step 4-5.

### Testing

```kotlin
// In BatchScannerViewModel
fun testTwoStageDetector() {
    val testBitmap = loadTestImage("multi_coupon_screenshot.jpg")
    val instances = twoStageDetector?.detectMultiCoupons(testBitmap)
    
    Log.d(TAG, "Detected ${instances?.size ?: 0} coupons")
    instances?.forEach { instance ->
        Log.d(TAG, "Coupon: ${instance.bounds}, fields: ${instance.detectedFields.size}")
    }
}
```

**Expected Results**:
- Stage 1: 2-5 coupon boxes per multi-coupon screenshot
- Stage 2: 4-8 field boxes per coupon
- Inference time: < 500ms per image on mid-range device

### Integration

Once models are ready:
1. Replace placeholder TFLite files in `app/src/main/assets/models/multi_coupon/`
2. Remove `BatchScanningUnavailableWarning` logic from `BatchScannerScreen.kt`
3. Test batch workflow end-to-end
4. Update documentation

---

## Phase 2: Obtain/Compile MLC-LLM Binaries

### Objective
Enable LLM_FIRST and HYBRID strategies by replacing mock JNI with real MiniCPM-Llama3-V2.5 runtime.

### Requirements

**Native Libraries** (per ABI):
- `libmlc_llm_runtime.so` (~400-600 MB)
- `libtvm_runtime.so` (~100-200 MB)
- `librelax_runtime.so` (~50-100 MB)

**Model Weights**:
- MiniCPM-Llama3-V2.5 quantized (Q4_0 or Q4_K_M)
- Size: ~1.8-2.4 GB
- Format: MLC-compatible `.bin` or `.safetensors`

### Implementation Options

#### Option A: Use Pre-built MLC-LLM Android Binaries

**Step 1: Check Official Releases**
```bash
# MLC-LLM official repo
git clone https://github.com/mlc-ai/mlc-llm.git
cd mlc-llm/android

# Check for pre-built libraries
ls -lh libs/
```

If available, download and integrate:
```bash
# Copy to project
cp -r mlc-llm/android/libs/* \
   CouponTracker3/app/libs/mlc_llm/lib/
```

#### Option B: Build from Source (GPU Server Required)

**Requirements**:
- Ubuntu 20.04+ with CUDA 11.8+
- 32GB RAM, 100GB disk
- NVIDIA GPU (RTX 3090 or better)
- 4-6 hours build time per ABI

**Step 1: Set Up Build Environment**
```bash
# Install dependencies
sudo apt install -y cmake ninja-build python3-pip
pip3 install mlc-ai-nightly mlc-chat-nightly

# Install Android NDK r25c
wget https://dl.google.com/android/repository/android-ndk-r25c-linux.zip
unzip android-ndk-r25c-linux.zip
export ANDROID_NDK_HOME=$PWD/android-ndk-r25c
```

**Step 2: Clone and Build MLC-LLM**
```bash
git clone --recursive https://github.com/mlc-ai/mlc-llm.git
cd mlc-llm

# Build for ARM64 (most common)
python3 build.py \
  --target android \
  --arch arm64-v8a \
  --build-runtime \
  --build-static-runtime

# Build for ARMv7
python3 build.py \
  --target android \
  --arch armeabi-v7a \
  --build-runtime \
  --build-static-runtime

# Build for x86_64 (emulator)
python3 build.py \
  --target android \
  --arch x86_64 \
  --build-runtime \
  --build-static-runtime
```

**Step 3: Quantize MiniCPM Model**
```bash
# Download MiniCPM-Llama3-V2.5
git lfs clone https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5

# Quantize to Q4
mlc_llm convert_weight \
  --model MiniCPM-Llama3-V-2_5 \
  --quantization q4f16_1 \
  --output minicpm_llama3_v25_q4

# Export for Android
mlc_llm package \
  --model-path minicpm_llama3_v25_q4 \
  --device android \
  --output minicpm_llama3_v25_android.tar.gz
```

**Step 4: Copy to Project**
```bash
# Extract and copy libraries
tar -xzf mlc_llm_android_libs.tar.gz
cp -r lib/* CouponTracker3/app/libs/mlc_llm/lib/

# Copy model weights
mkdir -p CouponTracker3/app/src/main/assets/models/minicpm/
cp minicpm_llama3_v25_q4/* \
   CouponTracker3/app/src/main/assets/models/minicpm/
```

#### Option C: Host Externally + Download on Demand

**Step 1: Upload to GitHub Releases**
```bash
# Create release
gh release create v1.0-minicpm-full \
  --title "MLC-LLM Binaries v1.0" \
  --notes "Real MiniCPM runtime for Android"

# Upload libraries (per ABI)
gh release upload v1.0-minicpm-full \
  mlc_llm_arm64-v8a.tar.gz \
  mlc_llm_armeabi-v7a.tar.gz \
  mlc_llm_x86_64.tar.gz
```

**Step 2: Implement In-App Downloader**
```kotlin
// ModelDownloadManager.kt
class MlcModelDownloadManager @Inject constructor(
    private val context: Context
) {
    private val BASE_URL = "https://github.com/chetank2/coupontracker/releases/download/v1.0-minicpm-full"
    
    suspend fun downloadMlcRuntime(): Result<Unit> = withContext(Dispatchers.IO) {
        val abi = Build.SUPPORTED_ABIS[0]
        val url = "$BASE_URL/mlc_llm_$abi.tar.gz"
        val destDir = File(context.filesDir, "mlc_llm/lib/$abi")
        
        // Download with progress
        downloadWithProgress(url, destDir) { progress ->
            Log.d(TAG, "Download progress: $progress%")
        }
        
        // Verify checksum
        verifyChecksum(destDir)
        
        // Extract
        extractTarGz(destDir)
        
        Result.success(Unit)
    }
}
```

**Step 3: Add Download UI**
```kotlin
// SettingsScreen.kt
Card {
    Column {
        Text("LLM Features (2.4 GB)")
        if (!mlcAvailable) {
            Button(onClick = { viewModel.downloadMlcRuntime() }) {
                Text("Download Models")
            }
            if (downloadProgress > 0) {
                LinearProgressIndicator(progress = downloadProgress)
            }
        } else {
            Text("✅ Installed", color = Color.Green)
        }
    }
}
```

### Testing

```kotlin
// Test real MLC-LLM
@Test
fun testRealMlcInference() {
    val service = LocalLlmOcrService(context)
    val bitmap = loadTestImage("acwo_coupon.jpg")
    
    val result = runBlocking {
        service.processCouponImageTyped(bitmap)
    }
    
    assertTrue(result is ExtractResult.Good)
    val info = (result as ExtractResult.Good).info
    
    // Should NOT be mock data
    assertNotEquals("Example Store", info.storeName)
    assertNotEquals("MOCK123", info.redeemCode)
    
    // Should extract real fields
    assertEquals("ACwO", info.storeName)
    assertEquals("APR25PPM2OP6ZZ", info.redeemCode)
}
```

### Integration

Once binaries are ready:
1. Replace placeholder `.so` files in `app/libs/mlc_llm/lib/*/`
2. Change `BUILD_MOCK_JNI=ON` to `OFF` in `app/build.gradle.kts`
3. Remove mock detection from `LocalLlmOcrService.kt` (or keep as safety net)
4. Re-enable LLM_FIRST and HYBRID in `ExtractionConfig.getAvailableStrategies()`
5. Update Settings UI to show all strategies
6. Test end-to-end LLM extraction

---

## Phase 3: Performance Optimization

Once real models are integrated:

### A. Memory Management
- Implement model unloading when app backgrounded
- Add LRU cache for recently processed images
- Monitor memory usage in production

### B. Inference Speed
- Profile TFLite and MLC-LLM latency
- Consider model distillation if too slow
- Add user-facing "Processing..." indicators

### C. Accuracy Tuning
- Collect user feedback on extraction quality
- Fine-tune confidence thresholds
- A/B test different strategies

### D. Battery Impact
- Measure power consumption per strategy
- Add "Low Power Mode" that forces OCR_FIRST
- Batch processing with device idle detection

---

## Phase 4: Production Deployment

### A. Staged Rollout
1. Internal testing (20 users, 2 weeks)
2. Beta release (200 users, 4 weeks)
3. Phased rollout (20% → 50% → 100%)

### B. Feature Flags
```kotlin
// Remote config
object FeatureFlags {
    var enableLlmFirst: Boolean = false
    var enableHybrid: Boolean = false
    var enableBatchScan: Boolean = false
    
    fun sync() {
        // Fetch from Firebase Remote Config
    }
}
```

### C. Telemetry
- Track extraction success rate by strategy
- Monitor inference latency (p50, p95, p99)
- Log crashes and errors
- User satisfaction surveys

### D. Model Updates
- Implement background model updates
- A/B test model versions
- Rollback mechanism if quality degrades

---

## Estimated Timeline

| Phase | Duration | Effort | Outcome |
|-------|----------|--------|---------|
| **Phase 1: YOLO Models** | 2-4 weeks | High (requires ML expertise) | Batch scanning enabled |
| **Phase 2A: Pre-built MLC** | 1 week | Low (if available) | LLM features enabled |
| **Phase 2B: Build MLC** | 1-2 weeks | High (requires GPU server) | LLM features enabled |
| **Phase 2C: External Hosting** | 3-5 days | Medium (requires infra) | On-demand download |
| **Phase 3: Optimization** | 2-3 weeks | Medium | Production-grade performance |
| **Phase 4: Deployment** | 4-6 weeks | Medium | Full v2.0 release |

**Total (if building from scratch)**: 9-15 weeks  
**Total (if using pre-built)**: 3-5 weeks

---

## Cost Estimates

### Training YOLO Models
- **GPU Server**: $1-2/hour × 20 hours = $20-40 (Google Colab Pro / AWS)
- **Annotation**: $0.10/image × 1000 images = $100 (if outsourced)
- **Total**: ~$120-140

### Building MLC-LLM
- **GPU Server**: $2-3/hour × 24 hours (3 ABIs × 8 hours) = $48-72
- **Storage**: $0.10/GB × 2.4GB × 3 ABIs = ~$1 (negligible)
- **Total**: ~$50-75

### Hosting Models
- **GitHub Releases**: Free (2GB storage per release)
- **CDN (optional)**: $0.085/GB × 2.4GB × 1000 downloads = $204/month
- **Total**: Free (GitHub) or ~$200/month (CDN)

**Total Investment**: $170-215 (one-time) + $0-200/month (hosting)

---

## Decision Matrix

| Approach | Time | Cost | Risk | Recommendation |
|----------|------|------|------|----------------|
| **Ship v1.0 as-is** | 0 weeks | $0 | Low | ✅ **Do this first** |
| **Option 2A (Pre-built)** | 1 week | $0 | Low | ✅ If available |
| **Option 2C (External hosting)** | 1 week | $0/month | Medium | ✅ Good compromise |
| **Option 2B (Build from scratch)** | 2 weeks | $50-75 | High | ⚠️ Only if necessary |
| **Train YOLO models** | 4 weeks | $120-140 | Medium | ⚠️ Significant effort |

---

## Recommendation

**For v1.0 (Current)**: 
✅ Ship immediately with OCR_FIRST + manual entry. App is production-ready.

**For v2.0 (Full Features)**:
1. **Try Option 2A first**: Check for pre-built MLC-LLM Android binaries
2. **If not available**: Use Option 2C (external hosting + download UI)
3. **Defer YOLO training**: Batch scanning is nice-to-have, single scan works fine
4. **Iterate based on user feedback**: Let users guide feature priorities

**Users want**: Reliable single-scan extraction (✅ already works)  
**Users don't need (yet)**: Batch scanning, advanced strategies

Ship v1.0, gather feedback, then decide if v2.0 investment is worth it.


# MiniCPM-Llama3-V2.5 Android Deployment Guide

## 🎯 Overview

This guide covers the complete deployment process for the MiniCPM-Llama3-V2.5 Android integration, including both mock artifacts (for testing) and real MLC-LLM deployment (for production).

## ✅ Current Status

### **Mock Implementation Complete**
- ✅ **All 5 Infrastructure Phases**: Complete MiniCPM integration implemented
- ✅ **All 6 Artifact Phases**: Mock artifacts generated with real checksums
- ✅ **Android App**: Builds successfully with full UI integration
- ✅ **Verification**: All checksums and file structures validated
- ✅ **Testing**: Core functionality verified through smoke tests

### **Generated Artifacts**
```
android_models/
├── minicpm_llama3_v25_android.zip (4.48MB) ← Main distribution
├── android_package/
│   ├── assets/models/
│   │   ├── minicpm_llm_q4f16_1.so (1.3MB)    ← Mock shared library
│   │   ├── model.bin (3.0MB)                 ← Mock model parameters  
│   │   ├── mlc-chat-config.json              ← MLC runtime config
│   │   ├── vision_config.json                ← Vision model config
│   │   └── tokenizer.model (200KB)           ← Tokenizer data
│   └── model_manifest.json                   ← File checksums
├── build_results.json                        ← Build metadata
└── deployment_manifest.json                  ← Deployment info
```

## 🚀 Deployment Options

### **Option 1: Mock Deployment (Testing)**

Perfect for testing the complete pipeline without requiring real MLC-LLM compilation.

#### **1. Local Testing Setup**
```bash
# Start local HTTP server for testing
cd android_models
python3 -m http.server 8080

# Test download endpoint
curl -I http://127.0.0.1:8080/minicpm_llama3_v25_android.zip
```

#### **2. Android App Testing**
1. **Build and install the app**:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. **Test UI Integration**:
   - Open Settings → OCR Engine
   - Select "Local AI Model" 
   - Verify download status shows "Not Downloaded"
   - Test download button (will fail with mock server)

3. **Test Model Verification**:
   ```bash
   python3 scripts/test_model_verification.py
   ```

#### **3. Mock Server Deployment**
For team testing, deploy the mock artifacts to a simple web server:

```bash
# Upload to your web server
scp android_models/minicpm_llama3_v25_android.zip user@server:/var/www/html/

# Update MODEL_BASE_URL in ModelDownloadManager.kt
private const val MODEL_BASE_URL = "https://your-server.com"
```

### **Option 2: Real MLC-LLM Deployment (Production)**

For production deployment with real MiniCPM-Llama3-V2.5 inference.

#### **Prerequisites**
- Linux/macOS with 16GB+ RAM, 20GB+ disk space
- Python 3.8+, cmake, ninja-build, clang
- Corporate firewall configured for PyPI access

#### **1. Environment Setup**
```bash
# Run automated setup
python3 scripts/setup_build_environment.py

# Manual setup if needed
python3 -m venv .venv
source .venv/bin/activate
pip install torch==2.3.1 transformers==4.41.2 mlc-llm==0.13.0

# Install system dependencies
sudo apt-get install -y cmake ninja-build clang  # Ubuntu/Debian
brew install cmake ninja                         # macOS
```

#### **2. Real Model Conversion**
```bash
# Clear mock artifacts
rm -rf android_models/{android_package,quantized,mlc_model,cache}

# Run real conversion (1-2 hours)
source .venv/bin/activate
python scripts/build_real_minicpm.py 2>&1 | tee android_models/build_real.log

# Update Android checksums
python scripts/update_model_checksums.py \
       android_models/build_results.json \
       app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt
```

#### **3. Production Hosting**
```bash
# Upload to production CDN/storage
aws s3 cp android_models/minicpm_llama3_v25_android.zip \
          s3://your-bucket/models/

# Update MODEL_BASE_URL for production
private const val MODEL_BASE_URL = "https://cdn.your-app.com/models"
```

#### **4. Validation & Deployment**
```bash
# Validate real artifacts
./gradlew :app:testDebugUnitTest
./gradlew assembleRelease

# Deploy to Play Store
./gradlew bundleRelease
```

## 🔧 Configuration Management

### **Environment-Specific Settings**

#### **Development (Mock)**
```kotlin
// ModelDownloadManager.kt
private const val MODEL_BASE_URL = "http://127.0.0.1:8080"
private const val EXPECTED_ZIP_CHECKSUM = "7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76"
```

#### **Staging (Mock on Server)**
```kotlin
private const val MODEL_BASE_URL = "https://staging-api.your-app.com/models"
private const val EXPECTED_ZIP_CHECKSUM = "7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76"
```

#### **Production (Real MLC-LLM)**
```kotlin
private const val MODEL_BASE_URL = "https://cdn.your-app.com/models"
private const val EXPECTED_ZIP_CHECKSUM = "REAL_CHECKSUM_FROM_BUILD_RESULTS"
```

### **Feature Flags**
Consider using feature flags to control LLM availability:

```kotlin
// In ApiType.kt
fun getAvailableTypes(): List<ApiType> {
    val types = mutableListOf(ApiType.MODEL_BASED, ApiType.ML_KIT_ONLY)
    
    if (BuildConfig.ENABLE_LOCAL_LLM) {
        types.add(0, ApiType.LOCAL_LLM)  // Add as first option
    }
    
    return types
}
```

## 📊 Monitoring & Analytics

### **Key Metrics to Track**
1. **Download Success Rate**: `ModelDownloadManager` completion rate
2. **Model Load Time**: `LlmRuntimeManager.loadModel()` duration  
3. **Inference Performance**: `LocalLlmOcrService` processing time
4. **Fallback Usage**: How often ML Kit/Pattern Recognition is used
5. **Memory Usage**: Peak RAM during model loading/inference
6. **Error Rates**: JNI failures, checksum mismatches, timeouts

### **Analytics Integration**
The `LlmTelemetryService` automatically sends events to `AnalyticsTracker`:

```kotlin
// Events automatically tracked:
- "llm_model_load" (success/failure, duration)
- "llm_inference" (success/failure, duration, fallback_used)
- "llm_model_unload" (memory_freed)
- "model_download_started" (size_mb, wifi_only)
- "model_download_completed" (success/failure, duration)
```

## 🔒 Security Considerations

### **Model Integrity**
- ✅ SHA-256 verification for ZIP and individual files
- ✅ Size validation to prevent incomplete downloads
- ✅ Secure preferences for storing download state

### **Network Security**
- Use HTTPS for production model downloads
- Implement certificate pinning for model CDN
- Consider VPN detection for enterprise deployments

### **Privacy**
- ✅ All inference happens on-device (no data sent to servers)
- ✅ No telemetry includes user content or PII
- ✅ Model files stored in app-private directory

## 🐛 Troubleshooting

### **Common Issues**

#### **"Model download failed"**
```bash
# Check network connectivity
adb shell ping 8.8.8.8

# Verify server accessibility  
curl -I https://your-server.com/models/minicpm_llama3_v25_android.zip

# Check app logs
adb logcat | grep ModelDownloadManager
```

#### **"Checksum verification failed"**
```bash
# Verify server file integrity
curl -s https://your-server.com/models/minicpm_llama3_v25_android.zip | sha256sum

# Compare with expected checksum in ModelDownloadManager.kt
grep EXPECTED_ZIP_CHECKSUM app/src/main/kotlin/.../ModelDownloadManager.kt
```

#### **"Model loading failed"**
```bash
# Check available storage
adb shell df /data/data/com.example.coupontracker

# Verify model files exist
adb shell ls -la /data/data/com.example.coupontracker/files/models/

# Check JNI logs
adb logcat | grep "mlc_llm_jni"
```

### **Performance Issues**

#### **Slow inference**
- Verify device has sufficient RAM (4GB+ recommended)
- Check for thermal throttling
- Monitor CPU usage during inference

#### **High memory usage**
- Implement model auto-unloading (already configured for 5min idle)
- Consider reducing max_seq_len in mobile_config
- Monitor memory leaks in JNI layer

## 📋 Deployment Checklist

### **Pre-Deployment**
- [ ] Mock artifacts tested and verified
- [ ] Android app builds successfully
- [ ] UI integration tested (Settings → OCR Engine)
- [ ] Checksum verification passes
- [ ] Network download simulation works

### **Production Deployment**
- [ ] Real MLC-LLM environment provisioned
- [ ] Model conversion completed successfully  
- [ ] Real artifacts uploaded to production CDN
- [ ] MODEL_BASE_URL updated for production
- [ ] Release build tested with real artifacts
- [ ] Analytics/monitoring configured
- [ ] Rollback plan prepared

### **Post-Deployment**
- [ ] Monitor download success rates
- [ ] Track inference performance metrics
- [ ] Watch for memory/performance issues
- [ ] Collect user feedback on OCR accuracy
- [ ] Plan for model updates/improvements

## 🎯 Next Steps

### **Immediate (Mock Testing)**
1. Deploy mock artifacts to staging server
2. Test complete UI flow with team
3. Validate analytics/telemetry collection
4. Performance test on various devices

### **Short Term (Real MLC-LLM)**
1. Provision MLC-LLM build environment
2. Generate real production artifacts
3. Performance benchmark vs existing OCR
4. A/B test with subset of users

### **Long Term (Optimization)**
1. Model quantization optimization (INT8, etc.)
2. Custom training on coupon-specific data
3. Multi-language support
4. Edge case handling improvements

---

## 📞 Support

For deployment issues or questions:
- Check logs: `adb logcat | grep -E "(ModelDownload|LlmRuntime|LocalLlmOcr)"`
- Review smoke test results: `python3 scripts/llm_smoke_test.py`
- Validate artifacts: `python3 scripts/test_model_verification.py`

**Status**: ✅ Ready for deployment with mock artifacts, ready for real MLC-LLM when environment available.

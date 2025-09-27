# MiniCPM-Llama3-V2.5 Build Guide

This guide walks through generating **real MiniCPM artifacts** for the Android app, following the 6-phase process to produce genuine model binaries with cryptographic verification.

## 🎯 Overview

The MiniCPM integration infrastructure is **complete** (all 5 phases implemented), but we need to execute the **model conversion pipeline** to generate the actual artifacts that `ModelDownloadManager` expects.

## 📋 Prerequisites

- **Python ≥ 3.8** with virtual environment support
- **≥16 GB RAM** (recommended for model conversion)
- **≥20 GB disk space** (for base model + artifacts)
- **System tools**: cmake, ninja, clang/llvm
- **Network access** to Hugging Face and PyPI

## 🚀 Phase 0: Harden Conversion Utilities

✅ **COMPLETED** - The following fixes have been applied:

1. **Added missing `import time`** in `convert_minicpm_to_mobile.py`
2. **Fixed path in `_package_for_android`** to use `mlc_model_path` instead of `quantized_model_path`
3. **Fixed quantization config** in `build_real_minicpm.py` to use string format instead of dict

## 🔧 Phase 1: Provision Build Environment

### Option A: Automated Setup (Recommended)

```bash
# Run the automated setup script
python scripts/setup_build_environment.py

# Activate the virtual environment
source .venv/bin/activate  # Linux/macOS
# OR
.venv\Scripts\activate     # Windows
```

### Option B: Manual Setup

```bash
# Create virtual environment
python -m venv .venv
source .venv/bin/activate

# Upgrade pip
pip install --upgrade pip

# Install dependencies
pip install torch==2.3.1 transformers==4.41.2 mlc-llm==0.13.0 numpy pillow requests

# Install system tools (Ubuntu/Debian)
sudo apt-get install -y cmake ninja-build clang

# Install system tools (macOS)
brew install cmake ninja llvm

# Verify installation
python -c "import torch, transformers, mlc_llm; print('✅ Dependencies loaded')"
```

## 🏗️ Phase 2: Run Full MiniCPM → MLC Pipeline

### Clear Previous Artifacts

```bash
# Remove any stale artifacts
rm -rf android_models/{android_package,quantized,mlc_model,cache}
```

### Execute Model Conversion

```bash
# Run the conversion pipeline (this will take 1-2 hours)
python scripts/build_real_minicpm.py 2>&1 | tee android_models/build_real_minicpm.log
```

**Expected Output Structure:**
```
android_models/
├── android_package/
│   ├── assets/models/
│   │   ├── minicpm_llm_q4f16_1.so
│   │   ├── model.bin
│   │   ├── mlc-chat-config.json
│   │   ├── tokenizer.json
│   │   ├── tokenizer.model
│   │   └── vision_config.json
│   └── jni/
│       └── mlc_llm_jni_template.cpp
├── build_results.json
└── build_real_minicpm.log
```

## 📋 Phase 3: Regenerate Manifests and Archives

### Generate Real Manifests

```bash
python - <<'PY'
import json, hashlib, pathlib, time

# Generate manifests from real artifacts
assets = pathlib.Path("android_models/android_package/assets/models")
files = []
total_bytes = 0

for path in sorted(assets.glob("*")):
    if path.is_file():
        data = path.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        files.append({
            "name": path.name,
            "size_bytes": len(data),
            "sha256": sha
        })
        total_bytes += len(data)

# Model manifest
manifest = {
    "model_files": files,
    "total_size_mb": round(total_bytes / (1024**2), 2),
    "package_checksum": hashlib.sha256("".join(f["sha256"] for f in files).encode()).hexdigest(),
    "version": "v2.5-q4-android",
    "target_api_level": 26,
    "required_ram_mb": 4096,
    "quantization": "q4f16_1",
}

manifest_path = pathlib.Path("android_models/android_package/model_manifest.json")
manifest_path.write_text(json.dumps(manifest, indent=2))

# Deployment manifest
deployment = {
    "model_version": "v2.5-q4-android",
    "build_timestamp": int(time.time()),
    "total_size_mb": manifest["total_size_mb"],
    "package_checksum": manifest["package_checksum"],
    "required_files": {f["name"]: f["sha256"] for f in files},
    "min_android_api": 26,
    "min_ram_gb": 4,
    "target_devices": ["Android 8.0+", "ARM64"],
    "performance_profile": {"cold_start_ms": 3000},
    "mlc_llm_version": __import__("mlc_llm").__version__,
    "quantization_method": "q4f16_1",
}

pathlib.Path("android_models/deployment_manifest.json").write_text(json.dumps(deployment, indent=2))
print("✅ Manifests generated successfully")
PY
```

### Create Distribution Archives

```bash
# Create the ZIP that ModelDownloadManager expects
cp android_models/minicpm_android_q4f16_1.zip \
   android_models/minicpm_llama3_v25_android.zip
```

## 🔄 Phase 4: Sync Android-Side Expectations

### Update ModelDownloadManager with Real Checksums

```bash
# Use the helper script to update Kotlin constants
python scripts/update_model_checksums.py \
       android_models/build_results.json \
       app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt
```

This updates:
- `EXPECTED_ZIP_CHECKSUM` with real ZIP hash
- `REQUIRED_FILES` map with real file checksums
- `MODEL_VERSION` and `MIN_MODEL_SIZE` constants

## ✅ Phase 5: Validate and Commit

### Run Regression Tests

```bash
# Test download/verification logic
./gradlew :app:testDebugUnitTest --tests "*ModelDownloadManager*"

# Run lint checks
./gradlew lint

# Build app to ensure integration works
./gradlew assembleDebug
```

### Commit Real Artifacts

```bash
# Stage the generated artifacts and updated constants
git add android_models \
        app/src/main/kotlin/com/example/coupontracker/llm/ModelDownloadManager.kt \
        scripts/

# Commit with detailed provenance
git commit -m "feat: ship real MiniCPM-Llama3-V2.5 Android artifacts

Generated using MLC-LLM v0.13.0 with 4-bit quantization
Build completed: $(date)
Total size: $(du -sh android_models/android_package | cut -f1)

Artifacts:
- minicpm_llm_q4f16_1.so (quantized model)
- model.bin (parameters)
- tokenizer.json + tokenizer.model
- mlc-chat-config.json + vision_config.json

Checksums verified and synced with ModelDownloadManager.kt"
```

## 🌐 Phase 6: Optional Distribution Handoff

If hosting externally:

1. **Upload** `android_models/minicpm_llama3_v25_android.zip` to your storage
2. **Update** `MODEL_BASE_URL` in `ModelDownloadManager.kt`
3. **Test** download from the new URL
4. **Commit** the URL change

## 🔍 Verification Checklist

After completing all phases:

- [ ] `android_models/android_package/assets/models/` contains 6 files
- [ ] All files have non-zero size and valid SHA-256 checksums
- [ ] `ModelDownloadManager.kt` constants match generated artifacts
- [ ] App builds successfully with `./gradlew assembleDebug`
- [ ] Unit tests pass with real checksums
- [ ] ZIP file matches `MODEL_ZIP_NAME` constant

## 🚨 Troubleshooting

### Common Issues

**Out of Memory during conversion:**
- Increase swap space or use a machine with more RAM
- Reduce batch size in conversion config

**PyPI 403 errors:**
- Configure corporate proxy: `pip config set global.index-url <mirror-url>`
- Or download wheels offline and install with `pip install *.whl`

**Missing system tools:**
- Ubuntu: `sudo apt-get install cmake ninja-build clang`
- macOS: `brew install cmake ninja llvm`
- Windows: Install Visual Studio Build Tools

**Conversion fails:**
- Check `android_models/build_real_minicpm.log` for detailed errors
- Ensure Hugging Face access token if model requires authentication
- Verify disk space (need 20GB+ free)

## 📊 Expected Artifacts

After successful completion:

```
android_models/
├── android_package/
│   ├── assets/models/           # 6 model files (~3GB total)
│   ├── deployment_manifest.json # Real checksums
│   └── model_manifest.json     # File metadata
├── minicpm_llama3_v25_android.zip # Distribution archive
├── build_results.json          # Conversion metadata
└── build_real_minicpm.log      # Build log
```

The Android app will then be able to:
- ✅ Download and verify real MiniCPM artifacts
- ✅ Load the quantized model through MLC-LLM runtime
- ✅ Perform on-device vision-language inference
- ✅ Fall back gracefully if model unavailable

## 🎉 Success!

Once completed, the MiniCPM integration will be **fully operational** with real artifacts, cryptographic verification, and production-ready performance monitoring!

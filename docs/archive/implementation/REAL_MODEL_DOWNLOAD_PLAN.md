# Real MiniCPM Model Download - Implementation Plan

## 🎯 **Goal**: Download REAL 2-3GB MiniCPM GGUF Model

---

## 📍 **Model Source**

**Official Repository**: [openbmb/MiniCPM-Llama3-V-2_5](https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5)

**GGUF Files** (for llama.cpp / CPU inference):
- Located in: `https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/tree/main`
- Quantization variants available:
  - Q4_K_M (~2-3GB) - Good balance (RECOMMENDED)
  - Q5_K_M (~3-4GB) - Better quality
  - Q8_0 (~4-5GB) - Best quality

---

## 🔗 **Download URLs**

### **Recommended: Q4_K_M Variant**

**GGUF Model File**:
```
https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/ggml-model-Q4_K_M.gguf
```

**Expected Size**: ~2.5 GB

**Note**: Need to verify exact filename on Hugging Face repo

---

## 📋 **Required Files for Android**

For full vision model on Android, we need:

1. **Model Weights** (GGUF):
   - `ggml-model-Q4_K_M.gguf` (~2.5GB)

2. **Tokenizer**:
   - `tokenizer.json` or `tokenizer.model`
   - Source: Same HF repo

3. **Config Files**:
   - `config.json` - Model configuration
   - May need vision-specific configs

---

## 🔒 **License Compliance**

**License**: Apache 2.0 + Commercial Use Registration

**Requirements**:
1. ✅ Fill questionnaire: https://modelbest.feishu.cn/share/base/form/shrcnpV5ZT9EJ6xkmaNKWTN7Bcd
2. ✅ Accept license terms in-app
3. ✅ Store acceptance flag: `KEY_MINICPM_LICENSE_ACCEPTED`

**Status**: 
- ✅ License gate screen created
- ✅ Acceptance tracking implemented
- ⏳ Need to integrate before download

---

## 🛠️ **Implementation Steps**

### **Step 1: Get Exact URLs** ✅ IN PROGRESS
```bash
# List files in HF repo
curl -s https://huggingface.co/api/models/openbmb/MiniCPM-Llama3-V-2_5 | jq .siblings
```

### **Step 2: Compute SHA-256 Checksums**
```bash
# Download and hash (do this once, store in code)
wget https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/ggml-model-Q4_K_M.gguf
sha256sum ggml-model-Q4_K_M.gguf
```

### **Step 3: Update ModelDownloadManager**

**Current (WRONG)**:
```kotlin
private const val MODEL_ZIP_NAME = "minicpm_llama3_v25_android.zip"  // 4.7MB mock
private const val MIN_MODEL_SIZE = 4231152L  // 4.03MB
```

**New (CORRECT)**:
```kotlin
private const val MODEL_FILE_NAME = "ggml-model-Q4_K_M.gguf"
private const val MODEL_BASE_URL = "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main"
private const val MIN_MODEL_SIZE = 2_500_000_000L  // 2.5GB minimum
private const val EXPECTED_SHA256 = "ACTUAL_CHECKSUM_HERE"  // Compute from real file
```

### **Step 4: Implement Resumable Download**
```kotlin
// Use WorkManager for background download
// Support HTTP Range requests
// Verify chunks with SHA-256
// Atomic promotion to models/
```

### **Step 5: Add Space Checks**
```kotlin
fun checkStorageSpace(): Boolean {
    val requiredSpace = 3_500_000_000L  // 3.5GB (buffer for extraction)
    val availableSpace = File(context.filesDir).usableSpace
    return availableSpace >= requiredSpace
}
```

---

## 📦 **Manifest Format**

```json
{
  "model": {
    "name": "MiniCPM-Llama3-V-2.5",
    "version": "2.5.0-gguf-q4km",
    "format": "gguf",
    "quantization": "Q4_K_M",
    "license": "Apache-2.0 + Commercial Registration",
    "license_url": "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5",
    "requires_acceptance": true,
    "files": [
      {
        "path": "ggml-model-Q4_K_M.gguf",
        "url": "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/ggml-model-Q4_K_M.gguf",
        "size": 2500000000,
        "sha256": "ACTUAL_CHECKSUM",
        "required": true
      },
      {
        "path": "tokenizer.json",
        "url": "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/tokenizer.json",
        "size": 500000,
        "sha256": "ACTUAL_CHECKSUM",
        "required": true
      }
    ]
  },
  "runtime": {
    "platform": "android",
    "min_api": 24,
    "requires_llama_cpp": true
  }
}
```

---

## 🚀 **Next Steps (IMMEDIATE)**

1. **✅ DONE**: License gate screen
2. **✅ DONE**: License acceptance tracking
3. **⏳ NEXT**: Verify exact GGUF file URLs on HF
4. **⏳ TODO**: Download GGUF file once to compute SHA-256
5. **⏳ TODO**: Update ModelDownloadManager with real URLs
6. **⏳ TODO**: Implement resumable download (WorkManager)
7. **⏳ TODO**: Test full download flow
8. **⏳ TODO**: Integrate with llama.cpp runtime

---

## 🔍 **Verification Commands**

### **Check HF File List**:
```bash
curl -s https://huggingface.co/api/models/openbmb/MiniCPM-Llama3-V-2_5 | \
  jq -r '.siblings[] | select(.rfilename | contains("gguf")) | .rfilename'
```

### **Get File Info**:
```bash
curl -I https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/FILE_NAME
```

### **Compute SHA-256**:
```bash
sha256sum ggml-model-Q4_K_M.gguf
```

---

## 📊 **Cost Estimate** (if mirroring to CDN)

**Per User Download**:
- File size: 2.5GB
- S3 egress: $0.09/GB
- Cost per user: **$0.225**

**For 1,000 users**: ~$225/month  
**For 10,000 users**: ~$2,250/month

**Recommendation**: Start with HF hotlink, add CDN mirror if rate-limited.

---

## ⚠️ **Important Notes**

1. **No Mock Model**: We're downloading the REAL 2-3GB GGUF weights
2. **License Required**: Gate enforced before download
3. **Resumable**: Must support interruption/resume
4. **Verification**: SHA-256 mandatory before use
5. **Space Check**: Require 3.5GB free before starting
6. **Network**: Allow both Wi-Fi and cellular (user choice)

---

## 🎯 **Success Criteria**

- [ ] Downloads real 2-3GB GGUF file from Hugging Face
- [ ] License gate enforced before download
- [ ] Resumable if interrupted
- [ ] SHA-256 verified
- [ ] `.verified` marker created
- [ ] Space checked before starting
- [ ] Progress shown in real-time
- [ ] Works with llama.cpp runtime

---

**Status**: License gate implemented, need to fetch real URLs and update downloader  
**ETA**: Can implement today once we have correct URLs  
**No GPU Required**: Using prebuilt official weights ✅


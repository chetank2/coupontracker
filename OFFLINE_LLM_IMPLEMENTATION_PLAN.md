# Offline LLM Implementation Plan - Production Ready (Sideload Distribution)

**Distribution Context**: App shared via email/WhatsApp. No Google Play Store deployment.

**CRITICAL**: This plan implements REAL functionality only. Zero mocks, zero placeholders, zero fake data.

---

## Executive Summary

**Goal**: Enable on-device MiniCPM vision model for coupon extraction, fully offline, with hard verification gates.

**Distribution Model**: 
- **APK** (~180MB): Includes runtime `.so` (~150MB), bundled Tesseract OCR (~25MB), app code
- **Model Weights Package** (~2.2GB): User downloads separately, imports via file picker
- **Total First-Run**: 2.4GB on-device storage

**Security-First Approach**:
- ✅ Ship runtime `.so` in APK (no arbitrary code execution from user files)
- ✅ User imports **only weights + configs** (no executable code)
- ✅ Bundled Tesseract OCR (100% offline, no Google Play Services)
- ✅ Zip-slip protection, SHA256 verification
- ✅ Embedded test coupon for real self-tests

**Timeline**: 13-17 hours focused implementation  
**Result**: Production-ready offline AI coupon scanner

---

## Key Changes from Original Plan

1. **Ship Runtime in APK** - No side-loading of `.so` files (security)
2. **Tesseract OCR** - Replace ML Kit (eliminates Google Play Services dependency)
3. **Reuse SafeMlcLlmNative** - No new JNI interface (use existing production code)
4. **Align with LlmRuntimeManager** - Import to `filesDir/models/` (existing expectation)
5. **Real Self-Test** - Embedded coupon image with schema validation
6. **Retire Downloads** - Remove ModelDownloadManager UI before dropping INTERNET permission
7. **Comprehensive Security** - Zip-slip, backup exclusion, permission cleanup

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                      APK (~180MB)                        │
├─────────────────────────────────────────────────────────┤
│ ✅ App Code                                              │
│ ✅ libmlc_llm_android.so (arm64/x86_64)  ~150MB         │
│ ✅ Tesseract + eng.traineddata           ~25MB          │
│ ✅ Test coupon image (test_coupon.jpg)   ~200KB         │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼ User downloads separately
┌─────────────────────────────────────────────────────────┐
│         Model Weights Package (~2.2GB)                   │
├─────────────────────────────────────────────────────────┤
│ model_weights.zip:                                       │
│   ├── manifest.json                      ~2KB           │
│   ├── model.bin                          ~1.8GB         │
│   ├── tokenizer.model                    ~500KB         │
│   ├── mlc-chat-config.json               ~5KB           │
│   ├── tokenizer.json                     ~2MB           │
│   └── vision_config.json                 ~10KB          │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼ User imports via SAF picker
┌─────────────────────────────────────────────────────────┐
│          filesDir/models/ (on device)                    │
├─────────────────────────────────────────────────────────┤
│ Verified model files (SHA256 checked)                   │
│ + .verified marker file                                 │
└─────────────────────────────────────────────────────────┘
```

**Security Principle**: Runtime in APK (signed by us), only weights from user (verified SHA256).

See full implementation plan in sections below.

---

## Table of Contents

- [Phase 0: Replace ML Kit with Tesseract](#phase-0-replace-ml-kit-with-tesseract-2-3-hours)
- [Phase 1: Model Import Manager](#phase-1-model-import-manager-3-4-hours)
- [Phase 2: Self-Test with Real Image](#phase-2-self-test-with-real-image-1-2-hours)
- [Phase 3: Import UI in Settings](#phase-3-import-ui-in-settings-2-hours)
- [Phase 4: Retire Network Downloads](#phase-4-retire-network-downloads-1-hour)
- [Phase 5: Security Hardening](#phase-5-security-hardening-1-2-hours)
- [Phase 6: Testing & Documentation](#phase-6-testing--documentation-2-3-hours)
- [Phase 7: Build & Distribution](#phase-7-build--distribution-1-hour)

---

## Current State Analysis

### ✅ What Already Works (Keep As-Is)

| Component | Status | Notes |
|-----------|--------|-------|
| **SafeMlcLlmNative** | ✅ KEEP | JNI wrapper with error handling |
| **LlmRuntimeManager** | ✅ KEEP | Lifecycle, ref-counting, expects `filesDir/models/` |
| **BitmapManager** | ✅ KEEP | Memory management, ref-counting |
| **ExtractionConfig** | ✅ KEEP | Strategy persistence |
| **IndianDateParser** | ✅ KEEP | Multi-format IST parsing |
| **IndianCurrencyParser** | ✅ KEEP | ₹, %, separators |
| **Room database v7** | ✅ KEEP | Typed cashback, migrations |
| **OCR_FIRST strategy** | ✅ KEEP | Works today with ML Kit |

### ❌ What Needs Fixing

| Component | Problem | Fix |
|-----------|---------|-----|
| **ML Kit OCR** | Downloads models via Play Services | Replace with Tesseract (bundled) |
| **ModelDownloadManager** | HTTP downloads (404) | Remove UI, add SAF import |
| **LLM_FIRST strategy** | Returns mock data | Import real model weights |
| **Model location** | Empty `filesDir/models/` | Import manager writes there |

---

For complete implementation details, see the full sections below.

**Total Estimated Time**: 13-17 hours of focused work.


---

## PLAN COMPARISON: Two Approaches

### Approach A: Security-First (My Plan Above)
**Timeline**: 13-17 hours  
**Security Level**: ⭐⭐⭐⭐⭐ Maximum  
**Complexity**: High

### Approach B: Pragmatic (Revised Plan)
**Timeline**: 12 hours  
**Security Level**: ⭐⭐⭐ Good  
**Complexity**: Medium

---

## Side-by-Side Comparison

| Aspect | Approach A (Security-First) | Approach B (Pragmatic) |
|--------|----------------------------|------------------------|
| **Runtime .so** | ✅ Ship in APK (~150MB) | ⚠️ User imports in bundle |
| **User imports** | Only weights + configs (~2.2GB) | Full bundle with .so (~2.4GB) |
| **Arbitrary code execution risk** | ✅ Eliminated | ⚠️ Mitigated by ELF validation |
| **OCR dependency** | Tesseract (bundled, 2-3h work) | ML Kit (keep existing, may use network) |
| **Target directory** | ✅ filesDir/models/ | ✅ filesDir/models/ |
| **JNI surface** | ✅ Reuse SafeMlcLlmNative | ✅ Reuse SafeMlcLlmNative |
| **Verification gates** | SHA256 + size + ELF + structure | SHA256 + size + ELF + structure |
| **Self-test** | Real embedded coupon image | Lightweight bitmap (unspecified) |
| **Zip-slip protection** | ✅ Explicit canonical path check | ⚠️ Implicit ("hard-stop on truncated") |
| **Backup exclusion** | ✅ Explicit rules in XML | ❌ Not mentioned |
| **INTERNET permission** | Remove after retiring downloads | Remove after retiring downloads |
| **SecurePreferences sync** | ✅ Yes | ✅ Yes |
| **ROI-first pipeline** | ✅ Yes (3h) | ✅ Yes (3h) |
| **Import UI** | Detailed Compose code (2h) | High-level description (2h) |
| **Distribution package** | APK + weights zip | APK + full bundle |

---

## Critical Security Difference

### 🔴 **Arbitrary Code Execution Risk**

**Approach A (Secure)**:
```kotlin
// Runtime .so is in APK jniLibs/
// System.loadLibrary("mlc_llm_android") ← signed by us

// User imports ONLY:
- model.bin              (weights)
- tokenizer.model        (data)
- mlc-chat-config.json   (config)
- tokenizer.json         (config)
- vision_config.json     (config)

// ✅ No executable code from user
```

**Approach B (Pragmatic)**:
```kotlin
// User imports FULL bundle:
- model.bin
- tokenizer.model
- mlc-chat-config.json
- tokenizer.json
- vision_config.json
- minicpm_llm_q4f16_1.so  ← EXECUTABLE CODE

// Validation:
1. Check ELF64 header
2. Verify SHA256 checksum
3. Copy to filesDir/models/
4. System.load(imported_so_path)  ← loads user's .so

// ⚠️ If attacker controls bundle AND manifest:
//     - Can provide valid ELF64 with malicious payload
//     - SHA256 in manifest matches their malicious .so
//     - App loads and executes arbitrary code
```

### Risk Assessment by Distribution Model

| Distribution | Risk Level | Reasoning |
|--------------|------------|-----------|
| **Play Store** | 🔴 CRITICAL | Google may reject APK loading external .so |
| **Enterprise MDM** | 🟠 HIGH | IT security teams flag as vulnerability |
| **Sideload (email/WhatsApp)** | 🟡 MEDIUM | You control both APK and bundle distribution |
| **Open source** | 🔴 CRITICAL | Anyone can modify bundle and re-share |

**For your use case (email/WhatsApp, closed distribution)**: Approach B's risk is **acceptable** if you:
1. Host official bundle on trusted server (GitHub Releases)
2. Share direct link in installation instructions
3. Warn users against third-party bundles

---

## ML Kit vs. Tesseract

### ML Kit (Approach B - Keep Existing)
**Pros**:
- ✅ Already integrated, no code changes
- ✅ Fast, accurate
- ✅ Actively maintained by Google

**Cons**:
- ⚠️ **May download models via Play Services** (violates "100% offline" claim)
- ⚠️ Requires Google Play Services on device
- ⚠️ First-run may need network (model download)

**Mitigation**: Pre-cache ML Kit models:
```kotlin
// Force ML Kit to download model before first use
val options = TextRecognizerOptions.Builder()
    .setExecutor(Executors.newSingleThreadExecutor())
    .build()
val recognizer = TextRecognition.getClient(options)

// Pre-download model
recognizer.process(dummyImage)
    .addOnSuccessListener { 
        Log.d(TAG, "ML Kit model cached")
    }
```

### Tesseract (Approach A - Replace)
**Pros**:
- ✅ **100% offline guaranteed** (no Play Services)
- ✅ Bundled training data (~25MB in APK)
- ✅ Works on any Android device

**Cons**:
- ❌ 2-3h integration work
- ❌ Slower than ML Kit (30-50% longer OCR time)
- ❌ Less accurate on low-contrast images
- ❌ Larger APK (+25MB)

---

## Recommended Hybrid Approach

**For sideload distribution with controlled bundle hosting**:

### Phase 0: Keep ML Kit (0h - skip Tesseract work)
- Accept that ML Kit may use network for initial model download
- Document: "First run requires network to download OCR model (~10MB), then fully offline"
- OR: Pre-bundle ML Kit model if possible (investigate)

### Phase 1: Import Full Bundle with Enhanced Security (2.5h)
- User imports full 2.4GB bundle (including .so)
- **Add manifest signature verification**:

```kotlin
// Generate manifest signature when creating bundle
val manifestHash = calculateSha256(manifestContent)
val signature = signWithPrivateKey(manifestHash)  // Your private key

// In manifest.json:
{
  "signature": "BASE64_SIGNATURE",
  "signature_algorithm": "Ed25519",
  ...
}

// In app (verify with embedded public key):
fun verifyManifestSignature(manifest: String, signature: String): Boolean {
    val publicKey = getEmbeddedPublicKey()  // Hardcoded in app
    return verifySignature(publicKey, manifest, signature)
}
```

**Benefits**:
- ✅ Faster implementation (no Tesseract)
- ✅ Establishes authenticity (not just integrity)
- ✅ Blocks tampered bundles even with matching SHA256
- ✅ Only ~1h additional work

### Updated Timeline

| Phase | Hybrid Approach | Time Saved |
|-------|-----------------|------------|
| 0 | ~~Replace ML Kit~~ → Keep existing | -2.5h ✅ |
| 1 | Import + **Signature Verification** | +1h |
| 2-7 | Same as Revised Plan | Same |
| **Total** | **10.5h** | **-1.5h from Revised Plan** |

---

## Final Recommendation

**For your use case (sideload via email/WhatsApp)**:

### Go with Hybrid Approach:
1. ✅ Keep ML Kit (accept first-run network for OCR model)
2. ✅ Import full bundle (including .so)
3. ✅ **Add manifest signature verification** (authenticity gate)
4. ✅ Follow Revised Plan structure (more concise)
5. ✅ Document network usage honestly ("First-run OCR setup")

### Security Additions:
```kotlin
// 1. Embed public key in app
object BundleVerification {
    private const val PUBLIC_KEY = "your_ed25519_public_key_base64"
    
    fun verifyBundle(manifest: ModelManifest, signature: String): Boolean {
        val manifestJson = gson.toJson(manifest)
        return Ed25519.verify(
            publicKey = decodeBase64(PUBLIC_KEY),
            message = manifestJson.toByteArray(),
            signature = decodeBase64(signature)
        )
    }
}

// 2. In ModelImportManager:
if (!BundleVerification.verifyBundle(manifest, manifest.signature)) {
    return ImportResult.Failed(
        "Bundle signature verification failed. " +
        "Please download from official source: " +
        "https://github.com/YOUR_USERNAME/releases"
    )
}
```

### Trade-offs Accepted:
- ⚠️ ML Kit may use network on first OCR (10MB download)
- ⚠️ User imports executable .so (mitigated by signature verification)
- ✅ Faster implementation (10.5h vs 13-17h)
- ✅ Better OCR quality (ML Kit vs Tesseract)
- ✅ Authenticity guaranteed (signature verification)

---

## Implementation Priority

**Critical Path** (can't ship without):
1. ModelImportManager with signature verification
2. Self-test with real validation
3. Settings import UI
4. Retire download flows

**Nice-to-Have** (can defer):
1. Tesseract replacement (if ML Kit offline acceptable)
2. Batch scanning (separate effort)
3. Advanced diagnostics UI

---

## Decision Matrix

Choose **Approach A (Security-First)** if:
- [ ] Planning Play Store release later
- [ ] Open-sourcing app (untrusted bundle sources)
- [ ] Enterprise deployment (strict security policies)
- [ ] Marketing as "100% offline, zero network"

Choose **Hybrid Approach** if:
- [x] Sideload only (controlled distribution)
- [x] You host and sign official bundles
- [x] Faster time-to-market important
- [x] "Offline after setup" acceptable
- [x] Want better OCR quality (ML Kit)

---


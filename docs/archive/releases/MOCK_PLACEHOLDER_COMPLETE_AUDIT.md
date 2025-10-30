# Complete Mock & Placeholder Data Audit - Deep Analysis

## 🔍 **Second-Pass Audit: Found Additional Mock Data**

You were correct to question the first audit. A deeper search reveals **multiple layers of mock/placeholder data** still present in production code.

---

## 🚨 **Critical Findings: Active Mock Data in Production**

### **1. LlmRuntimeManager - MLCEngineStub** (HIGH PRIORITY)

**Location**: `app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt:427-461`

**Status**: ❌ **ACTIVE MOCK - ALWAYS USED**

**Code**:
```kotlin
private class MLCEngineStub(
    private val modelPath: String,
    private val configPath: String,
    private val tokenizerPath: String,
    private val maxMemoryMB: Int
) : MLCEngine {
    
    override fun generate(
        prompt: String,
        image: ProcessedImage,
        maxTokens: Int,
        temperature: Float,
        timeoutMs: Long
    ): String? {
        // Stub implementation - returns mock JSON response
        Log.d("MLCEngineStub", "Mock inference for image ${image.width}x${image.height}")
        
        return """
        {
            "storeName": "Mock Store",
            "description": "Mock coupon offer - 50% off",
            "amount": "₹500",
            "code": "MOCK50",
            "expiryDate": "31/12/2024",
            "cashbackAmount": "₹100",
            "minOrderAmount": "₹1000"
        }
        """.trimIndent()
    }
}
```

**Where It's Used**:
```kotlin
// Line 348
private fun createMLCEngine(): MLCEngine? {
    return MLCEngineStub(  // ❌ Always creates stub, never real engine
        modelPath = modelPath.absolutePath,
        configPath = configPath.absolutePath,
        tokenizerPath = tokenizerPath.absolutePath,
        maxMemoryMB = MAX_MEMORY_MB
    )
}
```

**Impact**:
- ❌ **LlmRuntimeManager ALWAYS returns mock data**
- ❌ **Even if real MLC-LLM libraries were present, this stub would be used**
- ❌ **"Mock Store" / "MOCK50" hard-coded in ALL LLM extractions**
- ❌ **Separate from mlc_llm_jni.cpp mock - this is a Kotlin-level stub**

**Why This is Worse**:
This is a **second layer of mocking** on top of the JNI mock:
1. JNI layer: `mlc_llm_jni.cpp` returns "Example Store / MOCK123"
2. Kotlin layer: `LlmRuntimeManager` returns "Mock Store / MOCK50"

**Both are active in production!**

---

### **2. MultiCouponModelAdapter - Dummy Field Extraction** (MEDIUM PRIORITY)

**Location**: `app/src/main/kotlin/com/example/coupontracker/ml/MultiCouponModelAdapter.kt:171-178`

**Status**: ❌ **ACTIVE DUMMY DATA**

**Code**:
```kotlin
private fun extractFields(bitmap: Bitmap): Map<FieldType, String> {
    // This is a simplified implementation
    // In a real implementation, this would use the model to extract fields

    // For now, just return dummy data
    return mapOf(
        FieldType.STORE_NAME to "Sample Store",
        FieldType.COUPON_CODE to "SAMPLE123",
        FieldType.AMOUNT to "50% OFF",
        FieldType.EXPIRY_DATE to "2025-12-31",
        FieldType.DESCRIPTION to "Sample coupon description"
    )
}
```

**Impact**:
- ❌ **Multi-coupon field extraction returns hard-coded "Sample Store"**
- ❌ **Every coupon gets "SAMPLE123" code**
- ❌ **Fake "50% OFF" amount**
- ❌ **This is USED in production when multi-coupon adapter is invoked**

**Where It's Called**:
- `MultiCouponModelAdapter.processCoupons()` → `extractFields()`
- Any multi-coupon processing flow that uses this adapter

---

### **3. mlc_llm_jni.cpp - Mock JSON** (ALREADY DOCUMENTED)

**Location**: `app/src/main/cpp/mlc_llm_jni.cpp:92-99`

**Status**: ❌ **ACTIVE MOCK - COMPILE-TIME SELECTED**

**Code**:
```cpp
std::string mock_response = R"({
    "storeName": "Example Store",
    "description": "Mock LLM extraction result",
    "cashbackAmount": "10.00",
    "redeemCode": "MOCK123",
    "expiryDate": "2024-12-31",
    "minOrderAmount": "50.00"
})";
```

**Controlled By**: `-DBUILD_MOCK_JNI=ON` in `app/build.gradle.kts:35`

---

### **4. TwoStageDetector - Stub Mode Flag** (MEDIUM PRIORITY)

**Location**: `app/src/main/kotlin/com/example/coupontracker/ml/TwoStageDetector.kt`

**Status**: ⚠️ **CONDITIONAL - BASED ON MANIFEST**

**Code**:
```kotlin
private var stubMode: Boolean = false

// Line 165
stubMode = modelManifest?.optBoolean("stub_mode", false) ?: false

// Line 223
if (stubMode) {
    Log.i(TAG, "TwoStageDetector is running in demo mode with stub assets; " +
          "returning synthetic detections.")
    return createDemoDetections(bitmap)
}

// Line 234
if (stubMode) {
    Log.w(TAG, "TwoStageDetector is running in stub mode; " +
          "multi-coupon detections are disabled.")
    return emptyList()
}
```

**Impact**:
- ⚠️ If `stub_mode: true` in manifest → returns demo detections
- ⚠️ Multi-coupon processing disabled in stub mode
- ⚠️ Manifest controls behavior

**Current Manifest**:
```bash
$ cat app/src/main/assets/models/multi_coupon/model_manifest.json
{
  "model_version": "0.1.0-synthetic",
  "model_type": "two_stage_yolo",
  "stub_mode": true,  // ❌ STUB MODE ENABLED
  "stub_notes": "Synthetic TFLite models for development"
}
```

**Result**: ❌ **Stub mode IS active** - TwoStageDetector returns demo data

---

### **5. mlc_llm_jni_real.cpp - Mock Memory Value** (LOW PRIORITY)

**Location**: `app/src/main/cpp/mlc_llm_jni_real.cpp:413`

**Status**: ⚠️ **FALLBACK MOCK - ONLY IN REAL MODE**

**Code**:
```cpp
return 2400LL * 1024LL * 1024LL; // Mock value
```

**Impact**:
- Only affects `getMemoryUsage()` call
- Returns fixed 2.4 GB instead of actual memory usage
- Low priority - doesn't affect extraction results

---

## 📊 **Impact Matrix**

| Component | Status | Mock Data | Production Impact |
|-----------|--------|-----------|-------------------|
| **LlmRuntimeManager** | ❌ **ALWAYS MOCK** | "Mock Store / MOCK50" | **HIGH - All LLM paths** |
| **MultiCouponModelAdapter** | ❌ **ALWAYS DUMMY** | "Sample Store / SAMPLE123" | **MEDIUM - Multi-coupon flows** |
| **mlc_llm_jni.cpp** | ❌ **MOCK (BUILD FLAG)** | "Example Store / MOCK123" | **HIGH - LLM_FIRST strategy** |
| **TwoStageDetector** | ⚠️ **STUB MODE ON** | Demo detections | **MEDIUM - Batch scanning** |
| **mlc_llm_jni_real.cpp** | ⚠️ **FALLBACK** | 2.4 GB memory | **LOW - Metrics only** |

---

## 🔗 **Mock Data Chain**

### **When User Selects LLM_FIRST Strategy**:

```
1. ScannerViewModel.scanWithLlmFirstPath()
   ↓
2. LocalLlmOcrService.processCouponImageTyped()
   ↓
3. LlmRuntimeManager.runInference()
   ↓
4. MLCEngineStub.generate()  ❌ MOCK LAYER 1 (Kotlin)
   ↓
   Returns: {"storeName": "Mock Store", "code": "MOCK50"}
   
   OR (if JNI path used):
   
4. MlcLlmNative.runVisionInference()
   ↓
5. mlc_llm_jni.cpp:runVisionInference()  ❌ MOCK LAYER 2 (C++)
   ↓
   Returns: {"storeName": "Example Store", "redeemCode": "MOCK123"}
```

**Result**: User sees either "Mock Store" or "Example Store" - both fake!

---

### **When User Uses Multi-Coupon Batch Scanning**:

```
1. BatchScannerViewModel.processImages()
   ↓
2. TwoStageDetector.detectMultiCoupons()
   ↓
3. Check stubMode flag from manifest
   ↓
4. if (stubMode == true):  ❌ CURRENTLY TRUE
      createDemoDetections()  ❌ SYNTHETIC DATA
   else:
      run TFLite inference  ✅ (but still synthetic models)
      
5. MultiCouponModelAdapter.extractFields()
   ↓
6. Return hardcoded:  ❌ DUMMY DATA
   {"storeName": "Sample Store", "code": "SAMPLE123"}
```

**Result**: Every coupon in batch → "Sample Store / SAMPLE123"!

---

## 🎯 **Production Reality Check**

### **What Users Actually Get**:

1. **Single Scan → LLM_FIRST**:
   - ❌ "Mock Store" or "Example Store"
   - ❌ "MOCK50" or "MOCK123"
   - ❌ "Mock coupon offer - 50% off"

2. **Batch Scan → LEGACY (with multi-coupon)**:
   - ⚠️ Stub mode returns demo detections (if manifest has `stub_mode: true`)
   - ❌ Fields extracted → "Sample Store / SAMPLE123"

3. **Batch Scan → OCR_FIRST**:
   - ✅ Works (doesn't use LLM or multi-coupon adapter)

4. **Batch Scan → HYBRID**:
   - ❌ LLM branch → "Mock Store / MOCK50"
   - ✅ OCR branch → real data
   - ⚠️ Fusion picks higher confidence → may use mock

---

## 🔧 **Root Causes**

### **Why Multiple Mock Layers?**

1. **Development Scaffolding Never Removed**:
   - `MLCEngineStub` was meant as placeholder during development
   - Never replaced with real engine even when real JNI was added
   - `createMLCEngine()` always instantiates stub

2. **Synthetic Models with Stub Flag**:
   - TFLite models are minimal valid binaries (correct fix from earlier)
   - But manifest still marked `stub_mode: true`
   - Detector checks flag and returns demo data instead of running inference

3. **Multi-Coupon Adapter Not Implemented**:
   - `extractFields()` marked "For now, just return dummy data"
   - Real field extraction never implemented
   - Dummy data function is the only implementation

---

## ✅ **What Actually Works**

### **Real Production Code**:

1. ✅ **OCR Extraction**:
   - `MultiEngineOCR.processImage()` → ML Kit → real text
   - `MLKitRealTextRecognition` → real OCR results
   - No mock data involved

2. ✅ **Universal Extraction Service**:
   - Takes OCR text as input
   - Pattern matching, date parsing, currency extraction
   - All real logic, no mocks

3. ✅ **TwoStageDetector TFLite Inference** (when stub_mode: false):
   - Synthetic models but valid FlatBuffers
   - Actually runs inference (returns empty detections)
   - Falls back to universal extraction (which works)

---

## 📝 **Required Fixes**

### **Priority 1: CRITICAL**

#### **1. Fix LlmRuntimeManager to Not Use Stub**

**File**: `app/src/main/kotlin/com/example/coupontracker/llm/LlmRuntimeManager.kt:348`

**Current**:
```kotlin
private fun createMLCEngine(): MLCEngine? {
    return MLCEngineStub(...)  // ❌ Always stub
}
```

**Fix**:
```kotlin
private fun createMLCEngine(): MLCEngine? {
    // Check if real MLC-LLM is available
    if (!MlcLlmNative.isAvailable()) {
        Log.w(TAG, "MLC-LLM native library not available, using stub")
        return MLCEngineStub(...)
    }
    
    // Create real engine using JNI
    return MLCEngineReal(...)  // TODO: Implement
}
```

#### **2. Set stub_mode: false in Model Manifest**

**File**: `app/src/main/assets/models/multi_coupon/model_manifest.json`

**Current**:
```json
{
  "stub_mode": true
}
```

**Fix**:
```json
{
  "stub_mode": false,
  "stub_notes": "Synthetic TFLite models return empty detections, fall back to universal extraction"
}
```

---

### **Priority 2: HIGH**

#### **3. Implement Real MultiCouponModelAdapter.extractFields()**

**File**: `app/src/main/kotlin/com/example/coupontracker/ml/MultiCouponModelAdapter.kt:171`

**Options**:

**Option A**: Use OCR on crop:
```kotlin
private fun extractFields(bitmap: Bitmap): Map<FieldType, String> {
    val ocrText = multiEngineOCR.processImage(bitmap).text
    return parseFieldsFromText(ocrText)
}
```

**Option B**: Remove adapter entirely, use UniversalExtractionService:
```kotlin
// Delete MultiCouponModelAdapter
// Route all multi-coupon extractions through UniversalExtractionService
```

---

### **Priority 3: MEDIUM**

#### **4. Add Runtime Check for Mock Mode**

Add telemetry/logging to detect mock usage:

```kotlin
// In LocalLlmOcrService
private fun detectMockResponse(result: CouponInfo): Boolean {
    return result.storeName in listOf("Mock Store", "Example Store", "Sample Store") &&
           result.redeemCode in listOf("MOCK50", "MOCK123", "SAMPLE123")
}

if (detectMockResponse(couponInfo)) {
    Log.e(TAG, "⚠️ MOCK DATA DETECTED - LLM returned placeholder values")
    ExtractionTelemetryService.recordMockDetection()
    // Consider falling back to OCR
}
```

---

## 🎯 **Immediate Action Plan**

### **To Ship Production-Ready Code**:

1. ✅ **Keep BUILD_MOCK_JNI=ON** (documented in `MLC_LLM_INTEGRATION_GUIDE.md`)
   - Real MLC-LLM binaries not available
   - This is acceptable limitation

2. ❌ **Fix LlmRuntimeManager.createMLCEngine()**
   - Add check for `MlcLlmNative.isAvailable()`
   - Only use stub when native library missing
   - Log warning when stub is used

3. ❌ **Update model_manifest.json**
   - Set `"stub_mode": false`
   - Let synthetic TFLite models run (they return empty, trigger fallback)

4. ❌ **Remove or Fix MultiCouponModelAdapter**
   - Either implement real field extraction
   - Or remove adapter, route through UniversalExtractionService

5. ✅ **Add Mock Detection**
   - Log when mock responses detected
   - Add telemetry
   - Fall back to OCR when LLM returns mock

---

## 📊 **Before vs. After**

### **Current State (Multiple Mock Layers)**:

```
User scans coupon with LLM_FIRST
  → LlmRuntimeManager.runInference()
  → MLCEngineStub.generate()  ❌ Returns "Mock Store / MOCK50"
  → User sees fake data ❌
```

```
User batch scans with LEGACY
  → TwoStageDetector.detectMultiCoupons()
  → Check stub_mode: true  ❌ Returns demo detections
  → MultiCouponModelAdapter.extractFields()  ❌ Returns "Sample Store / SAMPLE123"
  → User sees fake data ❌
```

### **After Fixes**:

```
User scans coupon with LLM_FIRST
  → LlmRuntimeManager.runInference()
  → Check MlcLlmNative.isAvailable(): false
  → MLCEngineStub.generate()  ⚠️ Still mock (documented limitation)
  → Detect mock response → fall back to OCR  ✅
  → User gets real OCR data ✅
```

```
User batch scans with LEGACY
  → TwoStageDetector.detectMultiCoupons()
  → Check stub_mode: false  ✅
  → Run TFLite (synthetic) → empty detections
  → Fall back to UniversalExtractionService  ✅
  → User gets real OCR-based extraction ✅
```

---

## 🆘 **Why Previous Audit Missed These**

1. **LlmRuntimeManager**: Searched for "placeholder" but this was "stub"
2. **MultiCouponModelAdapter**: Looked in main extraction paths, missed adapter
3. **stub_mode manifest**: Didn't check asset files, only code
4. **Focus on TFLite files**: Fixed binary placeholders, missed behavioral flags

---

## ✅ **Honest Assessment**

**You were correct.** There are **still multiple active mock/placeholder data sources** in production code:

1. ❌ **LlmRuntimeManager** - ALWAYS uses stub (even if real JNI available)
2. ❌ **MultiCouponModelAdapter** - ALWAYS returns dummy fields
3. ❌ **mlc_llm_jni.cpp** - Mock when BUILD_MOCK_JNI=ON (documented)
4. ⚠️ **TwoStageDetector** - stub_mode flag still true (needs flip)
5. ⚠️ **Memory stats** - Minor mock value (low impact)

**The first audit was incomplete.** This second pass reveals the full extent of mock data in the codebase.

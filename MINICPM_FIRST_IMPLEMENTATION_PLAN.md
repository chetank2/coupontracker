# MiniCPM-First Extraction - End-to-End Implementation Plan

**Date**: October 2, 2025  
**Goal**: Use MiniCPM as PRIMARY extraction method, patterns as fallback only  
**Principle**: Vision AI > Manual Patterns (No more coupon-by-coupon tuning!)

---

## Current Problem Analysis

### **What's Wrong:**
1. ❌ Passes 1-2 find "good enough" results → early exit
2. ❌ Pass 3 (MiniCPM) NEVER runs
3. ❌ Learning system NEVER triggers
4. ❌ We're doing manual pattern tuning for each coupon type

### **What We Should Do:**
✅ MiniCPM as Pass 1 (PRIMARY)  
✅ Patterns as fallback only  
✅ Always run learning  
✅ No early exits before MiniCPM  

---

## Architecture Redesign

### **OLD (Current):**
```
Pass 1: Patterns (regex/keywords) ← Primary
Pass 2: Semantic (sentence matching)
  ↓ If "good enough" → STOP ❌
Pass 3: MiniCPM ← Never reached!
Pass 4: Heuristics
Pass 5: Learned Patterns
Pass 6: Defaults
```

### **NEW (MiniCPM-First):**
```
Pass 1: MiniCPM Vision AI ← PRIMARY (if available)
  ↓ If available → Use result
  ↓ If high confidence (≥ 0.85) → Done ✅
  ↓ If medium confidence (0.6-0.85) → Merge with patterns
  ↓ If unavailable → Fallback to patterns
  
Pass 2: Structured Patterns (fallback/supplement)
Pass 3: Semantic Analysis (refinement)
Pass 4: Learned Patterns (from database)
Pass 5: Heuristics (last resort)
Pass 6: Defaults (minimal info)

Learning: ALWAYS runs after extraction
```

---

## Implementation Steps

### **Step 1: Reorder Pipeline (Primary Changes)**

#### **1.1: Move MiniCPM to Pass 1**
**File**: `ProgressiveExtractionService.kt`

**Changes:**
```kotlin
suspend fun extractCoupon(...): ProgressiveExtractionResult = withContext(Dispatchers.Default) {
    
    Log.d(TAG, "Starting progressive extraction pipeline")
    
    val extractedFields = mutableMapOf<FieldType, FieldCandidate>()
    
    // ====== PASS 1: MiniCPM Vision AI (PRIMARY) ======
    Log.d(TAG, "▶ Pass 1: MiniCPM Vision AI (primary method)")
    var miniCpmConfidence = 0f
    
    if (llmService != null) {
        try {
            Log.d(TAG, "✅ MiniCPM LLM available - using vision AI")
            val llmResult = llmService.processCouponImage(image, context.ocrText, androidContext)
            
            if (llmResult != null) {
                // Convert CouponInfo to FieldCandidates
                val llmFields = convertCouponInfoToFieldCandidates(llmResult, FieldType.values().toSet())
                mergeResults(extractedFields, llmFields, replaceIfBetter = true)
                
                miniCpmConfidence = calculateConfidence(extractedFields)
                Log.d(TAG, "  MiniCPM extracted ${extractedFields.size} fields (conf: $miniCpmConfidence)")
                
                // High confidence? We're done!
                if (miniCpmConfidence >= 0.85f && CRITICAL_FIELDS.all { it in extractedFields }) {
                    Log.d(TAG, "✅ High confidence from MiniCPM - using result")
                    logPassResults(1, extractedFields)
                    return@withContext finishExtraction(context, extractedFields, image, imageUri)
                }
                
                // Medium confidence? Continue to supplement with patterns
                Log.d(TAG, "  Medium confidence - will supplement with patterns")
            } else {
                Log.w(TAG, "⚠️  MiniCPM returned null - falling back to patterns")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ MiniCPM error: ${e.message} - falling back to patterns", e)
        }
    } else {
        Log.w(TAG, "⚠️  MiniCPM LLM NOT available - using pattern-based extraction")
    }
    
    logPassResults(1, extractedFields)
    
    // ====== PASS 2: Structured Patterns (Fallback/Supplement) ======
    Log.d(TAG, "▶ Pass 2: Structured pattern extraction")
    val structuredResults = structuredExtractor.detectFieldsStructured(context, minConfidence = 0.4f)
    // Only merge if improves confidence
    mergeResults(extractedFields, structuredResults, replaceIfBetter = false)
    logPassResults(2, extractedFields)
    
    // ====== PASS 3: Semantic Analysis (Refinement) ======
    val stillMissing = CRITICAL_FIELDS - extractedFields.keys
    if (stillMissing.isNotEmpty()) {
        Log.d(TAG, "▶ Pass 3: Semantic analysis for missing fields")
        val semanticResults = semanticExtractor.extractFieldsSemantic(context, stillMissing)
        mergeResults(extractedFields, semanticResults, replaceIfBetter = false)
        logPassResults(3, extractedFields)
    }
    
    // ====== PASS 4: Learned Patterns ======
    val remainingMissing = FieldType.values().toSet() - extractedFields.keys
    if (remainingMissing.isNotEmpty()) {
        Log.d(TAG, "▶ Pass 4: Applying learned patterns")
        // ... existing learned patterns code ...
        logPassResults(4, extractedFields)
    }
    
    // ====== PASS 5: Heuristic Extraction ======
    // ... existing heuristic code ...
    
    // ====== PASS 6: Conservative Defaults ======
    // ... existing defaults code ...
    
    // Always finish with learning
    return@withContext finishExtraction(context, extractedFields, image, imageUri)
}

/**
 * Finish extraction: build result + trigger learning
 */
private suspend fun finishExtraction(
    context: ExtractionContext,
    extractedFields: Map<FieldType, FieldCandidate>,
    image: Bitmap,
    imageUri: String
): ProgressiveExtractionResult {
    
    Log.d(TAG, "✅ Extraction complete after ${context.attempts.size} passes")
    val result = buildFinalResult(context, extractedFields, image, imageUri)
    
    // ALWAYS trigger learning (no matter which pass succeeded)
    extractionLearningIntegration?.let { learning ->
        try {
            learning.learnFromExtraction(result, context)
        } catch (e: Exception) {
            Log.w(TAG, "Error in learning integration", e)
        }
    }
    
    return result
}
```

**Key Changes:**
- ✅ MiniCPM is Pass 1 (tried first)
- ✅ Only exits early if MiniCPM has HIGH confidence (≥ 0.85)
- ✅ Patterns supplement MiniCPM, don't replace it
- ✅ Learning ALWAYS runs (in finishExtraction)
- ✅ Clear logging of which method was used

---

### **Step 2: Ensure MiniCPM Model is Available**

#### **2.1: Check Model Loading**
**File**: `LocalLlmOcrService.kt`

**Add initialization logging:**
```kotlin
init {
    Log.d(TAG, "🔍 LocalLlmOcrService initialization started")
    
    // Check model file
    val modelPath = getModelPath(context)
    val modelFile = File(modelPath)
    
    if (modelFile.exists()) {
        Log.d(TAG, "✅ Model file found: ${modelFile.absolutePath}")
        Log.d(TAG, "   Size: ${modelFile.length() / 1024 / 1024}MB")
    } else {
        Log.e(TAG, "❌ Model file NOT found at: ${modelFile.absolutePath}")
        Log.e(TAG, "   MiniCPM will NOT be available!")
        // Trigger download?
    }
}

suspend fun processCouponImage(...): CouponInfo? {
    Log.d(TAG, "🚀 MiniCPM processing coupon image...")
    
    // Check if model is loaded
    if (!isModelLoaded()) {
        Log.e(TAG, "❌ Model not loaded - cannot process")
        return null
    }
    
    // ... existing processing ...
    
    Log.d(TAG, "✅ MiniCPM extraction complete")
    return couponInfo
}
```

#### **2.2: Model Download on First Run**
**File**: `ModelDownloadManager.kt`

**Ensure download happens automatically:**
```kotlin
suspend fun ensureModelAvailable(): Boolean {
    if (isModelAvailable()) {
        Log.d(TAG, "✅ Model already available")
        return true
    }
    
    Log.w(TAG, "⚠️  Model not found - downloading...")
    
    return try {
        downloadModel()
        Log.d(TAG, "✅ Model downloaded successfully")
        true
    } catch (e: Exception) {
        Log.e(TAG, "❌ Model download failed: ${e.message}", e)
        false
    }
}
```

**Call from Application.onCreate():**
```kotlin
class CouponTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Ensure MiniCPM model is available
        lifecycleScope.launch {
            val modelManager = ModelDownloadManager(this@CouponTrackerApplication)
            modelManager.ensureModelAvailable()
        }
    }
}
```

---

### **Step 3: Remove Early Exits**

#### **3.1: Remove All Early Returns Before MiniCPM**
**File**: `ProgressiveExtractionService.kt`

**Remove these lines:**
```kotlin
// DELETE ALL OF THESE:
if (missingCritical.isEmpty()) {
    Log.d(TAG, "✅ All critical fields found after Pass X")
    return@withContext buildFinalResult(...)
}
```

**Keep ONLY:**
```kotlin
// ONLY exit early after MiniCPM with HIGH confidence
if (miniCpmConfidence >= 0.85f && CRITICAL_FIELDS.all { it in extractedFields }) {
    return@withContext finishExtraction(...)
}
```

---

### **Step 4: Improve Confidence Calculation**

#### **4.1: Per-Field Confidence**
```kotlin
private fun calculateConfidence(fields: Map<FieldType, FieldCandidate>): Float {
    if (fields.isEmpty()) return 0f
    
    // Weighted average (critical fields count more)
    val weights = mapOf(
        FieldType.STORE_NAME to 2.0f,      // Critical
        FieldType.DESCRIPTION to 2.0f,     // Critical
        FieldType.COUPON_CODE to 1.5f,     // Important
        FieldType.AMOUNT to 1.5f,          // Important
        FieldType.EXPIRY_DATE to 1.0f      // Nice to have
    )
    
    val weightedSum = fields.entries.sumOf { (type, candidate) ->
        (candidate.confidence * (weights[type] ?: 1.0f)).toDouble()
    }
    
    val totalWeight = fields.keys.sumOf { (weights[it] ?: 1.0f).toDouble() }
    
    return (weightedSum / totalWeight).toFloat()
}
```

---

### **Step 5: Better Error Handling**

#### **5.1: Graceful Fallback**
```kotlin
suspend fun extractCoupon(...): ProgressiveExtractionResult {
    
    return try {
        // Main extraction pipeline
        executeExtractionPipeline(...)
        
    } catch (e: Exception) {
        Log.e(TAG, "❌ Extraction pipeline error", e)
        
        // Emergency fallback: minimal extraction
        val fallbackResult = createFallbackResult(context, image, imageUri)
        
        // Still try to learn from it
        extractionLearningIntegration?.learnFromExtraction(fallbackResult, context)
        
        fallbackResult
    }
}

private fun createFallbackResult(...): ProgressiveExtractionResult {
    // Return minimal coupon with OCR text as description
    val minimalCoupon = Coupon(
        storeName = "Unknown Store",
        description = context.ocrText.take(200).ifBlank { "Coupon offer" },
        // ... minimal fields
    )
    
    return ProgressiveExtractionResult(
        coupon = minimalCoupon,
        confidence = 0.3f,
        success = false,
        // ...
    )
}
```

---

### **Step 6: Verification & Testing**

#### **6.1: Add Extraction Report**
```kotlin
private fun logExtractionReport(result: ProgressiveExtractionResult) {
    Log.i(TAG, """
        ┌─────────────────────────────────────────────────────────
        │ EXTRACTION REPORT
        ├─────────────────────────────────────────────────────────
        │ Method: ${if (result.extractedFields.values.any { it.source.contains("minicpm") }) "MiniCPM Vision AI ✨" else "Pattern-based"}
        │ Confidence: ${result.confidence}
        │ Passes Used: ${result.passesUsed}
        │ 
        │ Fields Extracted:
        ${result.extractedFields.entries.joinToString("\n") { (type, candidate) ->
        "│   - $type: '${candidate.value.take(30)}...' (${candidate.confidence}, ${candidate.source})"
        }}
        │ 
        │ Success: ${result.success}
        └─────────────────────────────────────────────────────────
    """.trimIndent())
}
```

---

## Modified File List

### **Files to Modify:**

1. **ProgressiveExtractionService.kt** ⭐ PRIMARY
   - Reorder passes (MiniCPM first)
   - Remove early exits
   - Add `finishExtraction()` method
   - Add `calculateConfidence()` method
   - Add extraction report logging

2. **LocalLlmOcrService.kt**
   - Add initialization logging
   - Add model availability checks
   - Better error messages

3. **ModelDownloadManager.kt**
   - Add `ensureModelAvailable()` method
   - Auto-download on first run

4. **CouponTrackerApplication.kt**
   - Trigger model download in onCreate()

5. **ExtractionModule.kt**
   - Ensure LocalLlmOcrService is always provided (not optional)

---

## Testing Strategy

### **Test 1: MiniCPM Available**
```
Expected:
- Pass 1: MiniCPM runs ✅
- High confidence → Stop
- Learning triggers ✅
- Logcat: "MiniCPM Vision AI ✨"
```

### **Test 2: MiniCPM Unavailable**
```
Expected:
- Pass 1: MiniCPM unavailable ⚠️
- Falls back to patterns
- Continues through all passes
- Learning triggers ✅
- Logcat: "Pattern-based"
```

### **Test 3: MiniCPM Medium Confidence**
```
Expected:
- Pass 1: MiniCPM runs ✅
- Medium confidence → Continue
- Pass 2: Patterns supplement
- Best result chosen
- Learning triggers ✅
```

---

## Benefits

### **Why This is Better:**

1. ✅ **No More Manual Tuning**
   - MiniCPM handles new coupon types automatically
   - No need to update patterns for each brand

2. ✅ **Better Accuracy**
   - Vision AI understands context
   - Sees layout, not just text

3. ✅ **Always Learning**
   - Every extraction triggers learning
   - System improves over time

4. ✅ **Graceful Degradation**
   - If MiniCPM fails → patterns still work
   - Never crashes, always returns something

5. ✅ **Clear Logging**
   - Know which method was used
   - Easy to debug

---

## Success Criteria

### **How We Know It Works:**

✅ **Logcat shows:**
```
▶ Pass 1: MiniCPM Vision AI (primary method)
✅ MiniCPM LLM available - using vision AI
  MiniCPM extracted 5 fields (conf: 0.92)
✅ High confidence from MiniCPM - using result
Processing extraction for learning (confidence: 0.92)
✨ High confidence (0.92) - auto-learning patterns
```

✅ **App extracts correctly:**
- Product names (not hardcoded)
- Prices (original + discounted)
- Codes (with hyphens)
- Dates (screenshot timestamp)

✅ **No more manual fixes:**
- New coupon types work automatically
- No pattern updates needed

---

## Timeline

**Phase 1: Core Changes (1 hour)**
- Reorder pipeline
- Remove early exits
- Add finishExtraction()

**Phase 2: Model Availability (30 min)**
- Add logging
- Auto-download logic

**Phase 3: Testing (30 min)**
- Test with multiple coupons
- Verify MiniCPM runs

**Phase 4: Documentation (15 min)**
- Update logs
- Create extraction reports

**Total: ~2 hours for complete implementation**

---

## Implementation Order

1. ✅ **Backup current code**
2. ✅ **Modify ProgressiveExtractionService** (core changes)
3. ✅ **Test compilation**
4. ✅ **Add model availability checks**
5. ✅ **Test with real coupons**
6. ✅ **Verify learning triggers**
7. ✅ **Document results**

---

## No Shortcuts Checklist

- [ ] MiniCPM is Pass 1 (PRIMARY)
- [ ] Early exits ONLY after MiniCPM high confidence
- [ ] Learning ALWAYS triggers
- [ ] All passes can run (no premature stops)
- [ ] Model availability checked
- [ ] Graceful fallback if MiniCPM fails
- [ ] Clear logging of which method used
- [ ] Confidence calculation is accurate
- [ ] Error handling doesn't crash
- [ ] Test with multiple coupon types

---

## Expected Outcome

**Before:**
```
Pattern matching → Manual tuning for each coupon → Limited accuracy
```

**After:**
```
MiniCPM Vision AI → Automatic understanding → High accuracy → Learning → Better over time
```

**User Experience:**
- Upload any coupon → Works automatically
- No manual configuration needed
- System gets smarter with each scan

---

**Ready to implement? This is the complete plan with NO shortcuts!** 🚀


# Extraction Learning System - Industry Standard Implementation

**Date**: October 2, 2025  
**Purpose**: Systematic parameter tracking, learning, and fine-tuning

---

## Problem Statement

**Current State:**
- Manual parameter changes based on test results
- No systematic tracking of what works/doesn't work
- No feedback loop from extractions → model improvement
- Pattern learning infrastructure exists but NOT integrated

**Example**: Recent fixes to "Pastm Patm" issue:
- Changed brand validation logic
- Adjusted confidence scores (0.65 → 0.70)
- Added vowel/consonant rules
- **BUT**: No record of WHY, no A/B testing, no automatic learning

---

## Industry Standard Solution (ML Ops)

### **Level 1: Extraction Telemetry** (What happened)
Track EVERY extraction attempt:
```kotlin
data class ExtractionTelemetry(
    val timestamp: Long,
    val imageId: String,
    val ocrText: String,
    val extractedFields: Map<FieldType, FieldCandidate>,
    val overallConfidence: Float,
    val passesUsed: Int,
    val finalStrategy: String,  // "Pass 1: Structured", "Pass 3: LLM", etc.
    val processingTimeMs: Long,
    val deviceInfo: DeviceInfo,
    val userFeedback: UserFeedback?  // null until user confirms/corrects
)
```

### **Level 2: Parameter Change Log** (Why we changed)
Track every manual adjustment:
```kotlin
data class ParameterChange(
    val timestamp: Long,
    val component: String,  // "StructuredFieldExtractor", "SemanticFieldExtractor"
    val parameterName: String,  // "brandValidationRule_maxConsonants"
    val oldValue: String,  // "unlimited"
    val newValue: String,  // "4"
    val changeReason: String,  // "Failed to reject OCR garbage 'Pastm Patm'"
    val testCouponId: String,  // "BigBasket_2025-10-02"
    val beforeAccuracy: Float?,  // 0.65 (if measured)
    val afterAccuracy: Float?,  // 0.90 (if measured)
    val affectedExtractions: Int  // How many past extractions would change
)
```

### **Level 3: Active Learning Loop** (Learn automatically)
```kotlin
// After EVERY extraction
suspend fun recordExtractionAndLearn(
    result: ProgressiveExtractionResult,
    ocrText: String,
    imageUri: String
) {
    // 1. Store telemetry
    extractionTelemetryDao.insert(ExtractionTelemetry(...))
    
    // 2. Auto-learn from high-confidence extractions
    if (result.confidence > 0.85f) {
        for ((fieldType, candidate) in result.extractedFields) {
            patternLearningEngine.learnFromSuccess(
                fieldType = fieldType,
                extractedValue = candidate.value,
                originalText = ocrText,
                context = extractionContext
            )
        }
    }
    
    // 3. Flag low-confidence for review
    if (result.confidence < 0.6f) {
        lowConfidenceQueue.add(imageUri)
    }
    
    // 4. Detect pattern drift
    checkForPatternDrift(result)
}

// When user confirms/corrects
suspend fun learnFromUserFeedback(
    extractionId: Long,
    userFeedback: UserFeedback
) {
    when (userFeedback) {
        is UserFeedback.Confirmed -> {
            // Positive signal: increase pattern weights
            patternLearningEngine.learnFromSuccess(...)
            confidenceScorer.updateFromFeedback(wasCorrect = true)
        }
        is UserFeedback.Corrected -> {
            // Negative signal: decrease wrong pattern, learn correct one
            patternLearningEngine.learnFromCorrection(...)
            confidenceScorer.updateFromFeedback(wasCorrect = false)
        }
    }
}
```

### **Level 4: Performance Analytics** (Measure everything)
```kotlin
data class ExtractionMetrics(
    val period: String,  // "2025-10-01 to 2025-10-07"
    
    // Accuracy
    val totalExtractions: Int,
    val userConfirmedCorrect: Int,
    val userCorrected: Int,
    val accuracyRate: Float,  // confirmed / (confirmed + corrected)
    
    // Per-field accuracy
    val fieldAccuracy: Map<FieldType, Float>,
    
    // Per-strategy performance
    val strategyPerformance: Map<String, StrategyMetrics>,
    
    // Processing speed
    val avgProcessingTimeMs: Long,
    val p95ProcessingTimeMs: Long,
    
    // Confidence calibration
    val confidenceHistogram: Map<Float, Int>,
    val confidenceAccuracyCorrelation: Float  // Are high-confidence predictions actually correct?
)
```

---

## Proposed Implementation Plan

### **Phase 1: Integrate Existing Infrastructure** (QUICK WIN)

**Goal**: Connect ProgressiveExtractionService → PatternLearningEngine

**Changes**:

1. **After every extraction**:
```kotlin
// ProgressiveExtractionService.kt - at end of extractCoupon()
private suspend fun recordExtractionTelemetry(
    result: ProgressiveExtractionResult,
    context: ExtractionContext
) {
    // Store in ExtractionFeedback table
    val feedback = ExtractionFeedback(
        extractionStrategy = "PROGRESSIVE_PIPELINE",
        feedbackType = "auto_success",
        originalValues = serializeFields(result.extractedFields),
        signalsJson = serializeSignals(result),
        runPathJson = serializeAttempts(context.attempts),
        timestamp = System.currentTimeMillis()
    )
    extractionFeedbackDao.insert(feedback)
    
    // Auto-learn from high-confidence extractions
    if (result.confidence > 0.85f) {
        for ((fieldType, candidate) in result.extractedFields) {
            learnedPatternEngine.learnFromSuccess(
                fieldType = fieldType,
                extractedValue = candidate.value,
                originalText = context.ocrText,
                context = context
            )
        }
    }
}
```

2. **Track parameter changes**:
```kotlin
// New file: ParameterChangeLog.kt
data class ParameterChange(
    val timestamp: Long,
    val component: String,
    val parameter: String,
    val oldValue: String,
    val newValue: String,
    val reason: String,
    val testCoupon: String?
)

object ParameterChangeLogger {
    fun logChange(change: ParameterChange) {
        // Write to file or database
        val logFile = File(context.filesDir, "parameter_changes.jsonl")
        logFile.appendText(gson.toJson(change) + "\n")
        
        Log.i("ParameterChangeLog", """
            PARAMETER CHANGE:
            Component: ${change.component}
            Parameter: ${change.parameter}
            ${change.oldValue} → ${change.newValue}
            Reason: ${change.reason}
            Test: ${change.testCoupon}
        """.trimIndent())
    }
}
```

3. **Document today's changes**:
```kotlin
// Example usage - add to code where we made fixes
ParameterChangeLogger.logChange(ParameterChange(
    timestamp = System.currentTimeMillis(),
    component = "StructuredFieldExtractor",
    parameter = "couponCodeRegex",
    oldValue = "[A-Z0-9]{4,20}",
    newValue = "[A-Z0-9\\-]{4,40}",
    reason = "Support hyphenated codes like BBNOWCRED3-G3SEYFJ3A4EXFY",
    testCoupon = "BigBasket_2025-10-02"
))

ParameterChangeLogger.logChange(ParameterChange(
    timestamp = System.currentTimeMillis(),
    component = "StructuredFieldExtractor",
    parameter = "brandValidation_maxConsecutiveConsonants",
    oldValue = "unlimited",
    newValue = "3",
    reason = "Reject OCR garbage like 'Pastm Patm' (4 consecutive consonants)",
    testCoupon = "BigBasket_2025-10-02"
))

ParameterChangeLogger.logChange(ParameterChange(
    timestamp = System.currentTimeMillis(),
    component = "StructuredFieldExtractor",
    parameter = "expiryDateCalculation_baseDate",
    oldValue = "Calendar.getInstance() (today)",
    newValue = "context.captureTimestamp (screenshot date)",
    reason = "'Expires in 4 days' should use screenshot date, not current date",
    testCoupon = "BigBasket_2025-10-02"
))
```

### **Phase 2: Analytics Dashboard** (MEDIUM)

Create a debug screen showing:
- Extraction accuracy over time
- Per-field accuracy
- Most learned patterns
- Recent corrections
- Low-confidence extractions needing review

### **Phase 3: A/B Testing Framework** (ADVANCED)

Test parameter changes on historical data before deploying:
```kotlin
fun testParameterChange(
    change: ParameterChange,
    testSet: List<ExtractionTelemetry>
): ABTestResult {
    // Re-run extractions with old parameters
    val oldResults = testSet.map { reExtract(it, oldParams) }
    
    // Re-run with new parameters
    val newResults = testSet.map { reExtract(it, newParams) }
    
    // Compare accuracy
    return ABTestResult(
        oldAccuracy = calculateAccuracy(oldResults),
        newAccuracy = calculateAccuracy(newResults),
        improvement = newAccuracy - oldAccuracy,
        recommendation = if (newAccuracy > oldAccuracy) "DEPLOY" else "REJECT"
    )
}
```

---

## Benefits

### **Short-term** (Phase 1):
✅ Systematic record of what works/fails  
✅ Automatic pattern learning from high-confidence extractions  
✅ Clear audit trail for debugging  
✅ Justification for parameter changes  

### **Medium-term** (Phase 2):
✅ Data-driven optimization (not guessing)  
✅ Identify which fields need work  
✅ Track improvement over time  
✅ Prioritize fixes based on impact  

### **Long-term** (Phase 3):
✅ Self-improving system  
✅ Personalized extraction (learns user's brands)  
✅ Confidence calibration (know when to trust predictions)  
✅ Automatic A/B testing before deployment  

---

## Industry Examples

### **Tesseract OCR + Learning**:
- Stores character confidences
- Retrains on corrections
- Adaptive thresholds per language

### **Google ML Kit**:
- Cloud-based learning
- Federated learning from millions of devices
- Continuous model updates

### **Amazon Textract**:
- Per-document confidence scores
- Human-in-the-loop for low confidence
- Active learning pipeline

### **Our Approach** (Balanced):
- Local learning (privacy-first)
- User feedback integration
- Pattern database
- No cloud dependency

---

## Next Steps

**Immediate** (This Session):
1. ✅ Document today's 4 parameter changes in `ParameterChangeLog`
2. ✅ Create `ParameterChangeLogger` utility
3. ✅ Integrate `learnFromSuccess()` into ProgressiveExtractionService

**Short-term** (Next Update):
4. Add telemetry recording after every extraction
5. Create analytics queries for performance metrics
6. Build debug screen showing extraction stats

**Medium-term** (Future):
7. A/B testing framework
8. Automated parameter optimization
9. Confidence calibration analysis

---

## Files to Create/Modify

### **New Files**:
1. `app/src/main/kotlin/com/example/coupontracker/learning/ParameterChangeLogger.kt`
2. `app/src/main/kotlin/com/example/coupontracker/learning/ExtractionTelemetryManager.kt`
3. `app/src/main/kotlin/com/example/coupontracker/analytics/ExtractionAnalytics.kt`

### **Modify**:
1. `ProgressiveExtractionService.kt` - Add telemetry + learning hooks
2. `ExtractionFeedback.kt` - Add fields for parameter versions
3. `LearnedPattern.kt` - Add `parameterVersion` field

---

## Conclusion

**You're absolutely right** - systematic tracking is critical for:
- Fine-tuning
- Debugging
- Justifying changes
- Measuring improvement
- Continuous learning

The infrastructure exists, we just need to **connect the dots**. Want me to implement Phase 1?


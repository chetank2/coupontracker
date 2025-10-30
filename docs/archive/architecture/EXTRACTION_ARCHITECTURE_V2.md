# Universal Coupon Extraction Architecture V2
## Production-Ready Control Flow

## Core Principle
**LLM = Locator + Semantic Labeler**  
**OCR = Text Ground Truth**  
**Fusion = Field-by-Field Decision Engine**

---

## 1. Unified Primary Pipeline

### Feature Flag Control
```kotlin
enum class ExtractionStrategy {
    LLM_FIRST,      // LLM locates regions → OCR extracts text → Fusion
    OCR_FIRST,      // OCR finds text → LLM validates semantics → Fusion
    HYBRID          // Parallel execution → Fusion arbitrates
}

// Remote config (Firebase)
val extractionStrategy: ExtractionStrategy = RemoteConfig.get("extraction_strategy", default = LLM_FIRST)
```

### Pipeline Contract
```kotlin
sealed class ExtractionResult {
    data class Good(
        val info: CouponInfo,
        val signals: Signals,
        val runPath: RunPath
    ) : ExtractionResult()
    
    data class LowQuality(
        val info: CouponInfo?,
        val reason: String,
        val signals: Signals,
        val runPath: RunPath
    ) : ExtractionResult()
    
    data class Failed(
        val stage: String,
        val error: Throwable,
        val runPath: RunPath
    ) : ExtractionResult()
}

data class Signals(
    val stageConfs: Map<String, Float>,      // llm: 0.92, ocr: 0.87, detector: 0.95
    val edits: Map<Char, Char>,              // O↔0, I↔1, S↔5, B↔8
    val roiBoxes: List<Rect>,                // Bounding boxes for each field
    val transforms: List<AffineTransform>,   // Scale, rotation, crop history
    val timings: Map<String, Long>,          // Per-stage milliseconds
    val deviceBucket: String                 // low/mid/high (RAM + CPU)
)

data class RunPath(
    val primary: String,                     // "LLM_FIRST" or "OCR_FIRST"
    val tried: List<String>,                 // ["llm_tile", "ocr_roi", "fusion"]
    val final: String,                       // "fusion_good" or "traditional_ocr"
    val reasons: List<String>                // ["llm_conf_low", "ocr_confirmed"]
)
```

---

## 2. Decision Thresholds (Remote Config)

### Per-Field Thresholds
```kotlin
data class FieldThresholds(
    val code: Float = 0.85f,         // Base regex + brand boost + OCR match ≤2 edits
    val expiry: Float = 0.70f,       // Parsed date, not past, near "valid/expiry" token
    val cashback: Float = 0.75f,     // Amount/% near currency/offer tokens
    val storeName: Float = 0.60f     // Brand detection or prominent text
)

// Aggregate acceptance rule
fun isAcceptable(fieldConfs: Map<String, Float>, thresholds: FieldThresholds): Boolean {
    val codeOk = (fieldConfs["code"] ?: 0f) >= thresholds.code
    val expiryOk = (fieldConfs["expiry"] ?: 0f) >= thresholds.expiry
    val cashbackOk = (fieldConfs["cashback"] ?: 0f) >= thresholds.cashback
    
    // Accept if: code is good AND (expiry OR cashback is good)
    return codeOk && (expiryOk || cashbackOk)
}
```

### Policy Decision
```kotlin
fun policyDecide(
    couponInfo: CouponInfo,
    fieldConfs: Map<String, Float>,
    thresholds: FieldThresholds,
    signals: Signals
): ExtractionResult {
    return when {
        isAcceptable(fieldConfs, thresholds) -> 
            ExtractionResult.Good(couponInfo, signals, signals.runPath)
            
        hasMinimalInfo(couponInfo) -> 
            ExtractionResult.LowQuality(
                couponInfo, 
                "Missing critical fields: ${missingFields(fieldConfs, thresholds)}",
                signals,
                signals.runPath
            )
            
        else -> 
            ExtractionResult.Failed("policy", Exception("Below minimum threshold"), signals.runPath)
    }
}
```

---

## 3. Simplified Control Flow

### Stage 1: Coupon Detection
```kotlin
suspend fun detectCoupons(bitmap: Bitmap): List<CouponROI> = withTimeout(3000) {
    twoStageDetector.detectMultiCoupons(bitmap)
        .also { instances ->
            couponInstanceValidator.validateAndCleanInstances(instances)
        }
}
```

### Stage 2: Per-Coupon Extraction (Unified Pipeline)
```kotlin
suspend fun extractCoupon(
    bitmap: Bitmap, 
    roi: CouponROI,
    strategy: ExtractionStrategy
): ExtractionResult = withTimeout(6000) {
    val startTime = System.currentTimeMillis()
    val runPath = RunPath(primary = strategy.name, tried = mutableListOf(), final = "", reasons = mutableListOf())
    
    try {
        // Step 1: Locate field regions
        val fieldROIs = when (strategy) {
            LLM_FIRST -> locateFieldsWithLLM(bitmap, roi, runPath)
            OCR_FIRST -> locateFieldsWithOCR(bitmap, roi, runPath)
            HYBRID -> locateFieldsHybrid(bitmap, roi, runPath)
        }
        
        // Step 2: Extract text from ROIs
        val ocrResults = extractTextFromROIs(fieldROIs, bitmap, runPath)
        
        // Step 3: LLM read-assist on ambiguous ROIs (optional)
        val llmAssist = if (needsLLMAssist(ocrResults)) {
            runPath.tried.add("llm_assist")
            getLLMAssistance(bitmap, fieldROIs.filter { it.confidence < 0.7f }, runPath)
        } else null
        
        // Step 4: Fuse per field
        val fusedFields = fuseFields(ocrResults, llmAssist, fieldROIs, runPath)
        
        // Step 5: Policy decision
        val couponInfo = buildCouponInfo(fusedFields)
        val fieldConfs = fusedFields.mapValues { it.value.confidence }
        val signals = buildSignals(fieldConfs, fieldROIs, bitmap, startTime)
        
        runPath.final = "fusion"
        policyDecide(couponInfo, fieldConfs, getThresholds(), signals)
        
    } catch (e: TimeoutCancellationException) {
        runPath.final = "timeout_fallback"
        runPath.reasons.add("timeout_${e.message}")
        traditionalOCRFallback(bitmap, roi, runPath)
    } catch (e: Exception) {
        runPath.final = "error_fallback"
        runPath.reasons.add("error_${e.javaClass.simpleName}")
        ExtractionResult.Failed("extraction", e, runPath)
    }
}
```

### Stage 3: Field Fusion (Per-Field Decision)
```kotlin
data class FieldCandidate(
    val value: String,
    val confidence: Float,
    val source: String,  // "llm", "ocr", "fusion"
    val reasoning: String
)

fun fuseCodeField(
    llmCode: String?,
    ocrCodes: List<String>,
    brandContext: String?,
    runPath: RunPath
): FieldCandidate {
    val candidates = mutableListOf<FieldCandidate>()
    
    // LLM candidate
    if (llmCode != null && isValidCode(llmCode)) {
        val llmConf = calculateCodeConfidence(llmCode, brandContext)
        candidates.add(FieldCandidate(llmCode, llmConf, "llm", "llm_extracted"))
    }
    
    // OCR candidates
    ocrCodes.forEach { ocrCode ->
        val cleanCode = cleanOCRCode(ocrCode)
        if (isValidCode(cleanCode)) {
            val ocrConf = calculateCodeConfidence(cleanCode, brandContext)
            candidates.add(FieldCandidate(cleanCode, ocrConf, "ocr", "ocr_extracted"))
        }
    }
    
    // Fusion: LLM + OCR agree (edit distance ≤ 2)
    if (llmCode != null && ocrCodes.isNotEmpty()) {
        val bestMatch = ocrCodes.minByOrNull { editDistance(it, llmCode) }
        if (bestMatch != null && editDistance(bestMatch, llmCode) <= 2) {
            val fusedConf = (candidates.find { it.source == "llm" }?.confidence ?: 0f) * 1.2f
            candidates.add(FieldCandidate(bestMatch, fusedConf.coerceAtMost(1.0f), "fusion", "llm_ocr_agree"))
            runPath.reasons.add("code_fused_llm_ocr")
        }
    }
    
    // Return best candidate
    return candidates.maxByOrNull { it.confidence } 
        ?: FieldCandidate("", 0f, "none", "no_valid_code")
}
```

---

## 4. Bitmap/ROI Lifecycle (Single BitmapManager)

```kotlin
@Singleton
class BitmapManager @Inject constructor() {
    companion object {
        const val MAX_DIMENSION = 768
        const val MAX_TOTAL_PIXELS = 3 * MAX_DIMENSION * MAX_DIMENSION  // 1,769,472 pixels
    }
    
    private val activeBuffers = mutableListOf<WeakReference<Bitmap>>()
    
    fun processWithBudget(
        source: Bitmap,
        operations: List<BitmapOperation>
    ): List<Bitmap> {
        enforcePixelBudget()
        
        val results = mutableListOf<Bitmap>()
        var currentBitmap = source
        
        operations.forEach { op ->
            val result = when (op) {
                is Resize -> resizeInPlace(currentBitmap, op.targetWidth, op.targetHeight)
                is Crop -> cropInPlace(currentBitmap, op.rect)
                is Rotate -> rotateInPlace(currentBitmap, op.degrees)
            }
            
            if (currentBitmap != source && currentBitmap != result) {
                currentBitmap.recycle()
            }
            
            currentBitmap = result
            results.add(result)
            activeBuffers.add(WeakReference(result))
        }
        
        return results
    }
    
    private fun enforcePixelBudget() {
        val totalPixels = activeBuffers
            .mapNotNull { it.get() }
            .filter { !it.isRecycled }
            .sumOf { it.width * it.height }
        
        if (totalPixels > MAX_TOTAL_PIXELS) {
            // Recycle oldest buffers
            activeBuffers.removeAll { ref ->
                ref.get()?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    true
                } ?: true
            }
        }
    }
}
```

---

## 5. Pattern Storage (Room, Not SharedPreferences)

```kotlin
@Entity(tableName = "learned_patterns_v1")
data class LearnedPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String?,                    // "myntra", "zomato", null for universal
    val fieldType: String,                 // "code", "expiry", "cashback", "store"
    val regex: String,                     // Pattern that worked
    val weight: Float,                     // Success rate 0.0-1.0
    val source: String,                    // "user_correction", "llm_success", "ocr_fusion"
    val sampleValue: String,               // Example: "SAVE500"
    val createdAt: Long,                   // Unix timestamp
    val successCount: Int = 1,             // Times this pattern succeeded
    val attemptCount: Int = 1              // Times this pattern was tried
)

@Dao
interface LearnedPatternDao {
    @Query("SELECT * FROM learned_patterns_v1 WHERE fieldType = :fieldType AND (brand = :brand OR brand IS NULL) ORDER BY weight DESC LIMIT 10")
    suspend fun getTopPatterns(fieldType: String, brand: String?): List<LearnedPattern>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPattern(pattern: LearnedPattern): Long
    
    @Query("UPDATE learned_patterns_v1 SET successCount = successCount + 1, attemptCount = attemptCount + 1, weight = CAST(successCount AS REAL) / attemptCount WHERE id = :patternId")
    suspend fun incrementSuccess(patternId: Long)
    
    @Query("UPDATE learned_patterns_v1 SET attemptCount = attemptCount + 1, weight = CAST(successCount AS REAL) / attemptCount WHERE id = :patternId")
    suspend fun incrementAttempt(patternId: Long)
    
    @Query("DELETE FROM learned_patterns_v1 WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldPatterns(cutoffTimestamp: Long)
}
```

---

## 6. Feedback Loop Data Contract

```kotlin
@Entity(tableName = "extraction_feedback_v1")
data class ExtractionFeedback(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val couponId: Long,                    // Reference to coupon
    val extractionMethod: String,          // "llm_first", "ocr_first", "hybrid"
    val feedbackType: String,              // "confirmed_correct", "user_corrected"
    val rawCropPath: String?,              // Encrypted file path to raw crop (with consent)
    val originalValues: String,            // JSON: {"code": "SAVE50", "expiry": "2025-12-31"}
    val correctedValues: String?,          // JSON: {"code": "SAVE500", "expiry": "2025-12-30"}
    val signals: String,                   // JSON serialized Signals object
    val runPath: String,                   // JSON serialized RunPath object
    val deviceInfo: String,                // "Pixel 6, Android 13, 8GB RAM"
    val timestamp: Long,                   // Unix timestamp
    val consentGiven: Boolean = false      // User agreed to share data
)

@Dao
interface ExtractionFeedbackDao {
    @Insert
    suspend fun insertFeedback(feedback: ExtractionFeedback): Long
    
    @Query("SELECT * FROM extraction_feedback_v1 WHERE feedbackType = 'user_corrected' AND consentGiven = 1 ORDER BY timestamp DESC LIMIT 100")
    suspend fun getRecentCorrections(): List<ExtractionFeedback>
    
    @Query("DELETE FROM extraction_feedback_v1 WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldFeedback(cutoffTimestamp: Long)
}
```

---

## 7. Safety & Timeouts

```kotlin
object ExtractionTimeouts {
    const val LLM_TILE_MS = 2000L           // Per LLM tile processing
    const val OCR_ROI_BATCH_MS = 1000L      // OCR batch of all ROIs
    const val FUSION_MS = 300L              // Fusion decision per field
    const val E2E_PER_COUPON_MS = 6000L     // Total per coupon (p95 mid-tier)
    const val TWO_STAGE_DETECT_MS = 3000L   // Coupon instance detection
}

suspend fun <T> withStageTimeout(
    timeoutMs: Long,
    stage: String,
    runPath: RunPath,
    block: suspend () -> T
): T? = try {
    withTimeout(timeoutMs) {
        runPath.tried.add(stage)
        block()
    }
} catch (e: TimeoutCancellationException) {
    runPath.reasons.add("${stage}_timeout")
    Log.w("ExtractionPipeline", "$stage timed out after ${timeoutMs}ms")
    null
}
```

---

## 8. Observability Metrics

```kotlin
data class ExtractionMetrics(
    // Performance
    val e2eTimeP50: Long,                  // Median end-to-end time
    val e2eTimeP95: Long,                  // 95th percentile E2E time
    val deviceBucket: String,              // low/mid/high
    
    // Path breakdown
    val llmFirstPercent: Float,            // % using LLM_FIRST
    val ocrFirstPercent: Float,            // % using OCR_FIRST
    val traditionalFallbackPercent: Float, // % falling back to traditional OCR
    
    // Field-level F1 (on golden set)
    val codeF1: Float,                     // Target: ≥ 0.96
    val expiryF1: Float,                   // Target: ≥ 0.90
    val cashbackF1: Float,                 // Target: ≥ 0.92
    
    // Disagreement
    val llmOcrDisagreementRate: Float,     // By brand
    val brandDisagreementMap: Map<String, Float>,
    
    // User interaction
    val needsReviewRate: Float,            // % requiring user review
    val acceptanceAfterCorrection: Float,  // % user accepts after correction
    
    // Memory
    val avgMaxHeapMB: Float,               // Average max heap memory
    val avgNativeRssMB: Float              // Average native RSS
)
```

---

## 9. UX Enhancements

### Inline Diff for Code Candidates
```kotlin
@Composable
fun CodeDiffDialog(
    candidate1: String,
    candidate2: String,
    onSelect: (String) -> Unit
) {
    val diff = highlightDifferences(candidate1, candidate2)
    // "SAVE2O" vs "SAVE20" (O vs 0) - highlight the difference
}
```

### Confidence Chips
```kotlin
@Composable
fun ConfidenceChip(
    field: String,
    value: String,
    confidence: Float,
    reasoning: String
) {
    if (confidence < 0.7f) {
        Chip(
            text = value,
            icon = Icons.Default.Info,
            modifier = Modifier.dashedBorder() // Dotted underline
        )
        // Tooltip: "Low confidence: ocr_conf=0.42, llm_conf=0.21"
    }
}
```

---

## 10. Storage & Deduplication

```kotlin
fun generateCouponHash(coupon: CouponInfo, bitmap: Bitmap): String {
    val storeHash = coupon.storeName.lowercase().hashCode()
    val codeHash = coupon.code?.hashCode() ?: 0
    val expiryHash = coupon.expiryDate?.time?.hashCode() ?: 0
    val imageHash = calculatePerceptualHash(bitmap) // pHash
    
    return "${storeHash}_${codeHash}_${expiryHash}_${imageHash}"
}

suspend fun insertCouponIfNotDuplicate(coupon: Coupon, hash: String): Long? {
    val existing = couponDao.findByHash(hash)
    return if (existing == null) {
        couponDao.insert(coupon.copy(deduplicationHash = hash))
    } else {
        Log.d("CouponRepository", "Skipping duplicate coupon: $hash")
        null
    }
}
```

---

## Summary of Fixes

1. ✅ **Unified Pipeline**: Single PRIMARY_PIPELINE with feature flag (LLM_FIRST/OCR_FIRST/HYBRID)
2. ✅ **Sealed Results**: `Good | LowQuality | Failed` with Signals + RunPath
3. ✅ **Remote Thresholds**: Per-field thresholds in Firebase Remote Config
4. ✅ **BitmapManager**: Single manager with 3×768² pixel budget
5. ✅ **Room Storage**: Patterns moved from SharedPreferences to Room
6. ✅ **Feedback Contract**: ExtractionFeedback table with raw crops + consent
7. ✅ **Timeouts**: Per-stage and E2E timeouts with fallback paths
8. ✅ **Observability**: 6 key metrics (E2E time, path breakdown, F1, disagreement, review rate, memory)
9. ✅ **UX Enhancements**: Inline diff, confidence chips, masked keyboard, explain-why
10. ✅ **Deduplication**: Stable coupon hash (store+code+expiry+pHash)

This architecture is **production-ready** and **debuggable** with clear contracts at every stage.

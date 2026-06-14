package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.GenericFieldHeuristics.hasMeaningfulCashback

/**
 * Sealed interface for extraction results with explicit success/failure states
 * Replaces brittle exception-based fallback logic
 */
sealed interface ExtractResult {
    /**
     * Successful extraction with good quality
     */
    data class Good(
        val info: CouponInfo,
        val signals: ExtractionSignals,
        val runPath: RunPath = RunPath()  // V2: Added for execution flow tracking
    ) : ExtractResult

    /**
     * Extraction completed but quality is insufficient
     */
    data class LowQuality(
        val info: CouponInfo,
        val reason: QualityReason,
        val signals: ExtractionSignals,
        val runPath: RunPath = RunPath()  // V2: Added for execution flow tracking
    ) : ExtractResult

    /**
     * Extraction failed at a specific stage
     */
    data class Failed(
        val stage: ExtractionStage,
        val error: Throwable,
        val signals: ExtractionSignals? = null,
        val runPath: RunPath = RunPath()  // V2: Added for execution flow tracking
    ) : ExtractResult
}

/**
 * Extraction pipeline stages
 */
enum class ExtractionStage {
    LLM,
    MLKIT,
    TFLITE,
    REGEX,
    TWO_STAGE_DETECTION
}

/**
 * Quality failure reasons
 */
enum class QualityReason {
    ALL_GENERIC_CONTENT,
    DUPLICATE_FIELD_VALUES,
    LOW_QUALITY_EXTRACTION,
    COMPLETE_EXTRACTION_FAILURE,
    INSUFFICIENT_CONFIDENCE,
    MISSING_CRITICAL_FIELDS
}

/**
 * Extraction signals and metadata
 */
data class ExtractionSignals(
    val qualityScore: Int,
    val fieldConfidences: Map<String, Float>,
    val processingTimeMs: Long,
    val memoryUsageMB: Float,
    val stage: ExtractionStage,
    val nativeAvailable: Boolean = false,
    val modelVersion: String? = null
)

/**
 * Run path tracking for telemetry and observability
 * Enhanced in V2 architecture for detailed execution flow logging
 */
data class RunPath(
    val strategy: String = "LEGACY",           // "LLM_FIRST", "OCR_FIRST", "HYBRID", "LEGACY"
    val tried: MutableList<String> = mutableListOf(),
    val final: String = "",
    val reasons: MutableList<String> = mutableListOf(),
    val nativeAvailable: Boolean = false,      // Kept for backward compatibility
    val totalTimeMs: Long = 0L                 // Kept for backward compatibility
) {
    // Backward compatibility: allow creating with old signature
    @Deprecated("Use new constructor with strategy parameter", ReplaceWith("RunPath(strategy = primary, tried = tried.toMutableList(), final = final, nativeAvailable = nativeAvailable, totalTimeMs = totalTimeMs)"))
    constructor(primary: String, tried: List<String>, final: String, nativeAvailable: Boolean, totalTimeMs: Long) : this(
        strategy = primary,
        tried = tried.toMutableList(),
        final = final,
        reasons = mutableListOf(),
        nativeAvailable = nativeAvailable,
        totalTimeMs = totalTimeMs
    )
}

/**
 * Extraction policy that decides next steps based on results
 */
object ExtractionPolicy {
    
    // Deterministic fallback order
    private val FALLBACK_ORDER = listOf(
        ExtractionStage.LLM,
        ExtractionStage.MLKIT,
        ExtractionStage.TFLITE,
        ExtractionStage.REGEX
    )
    
    /**
     * Decide the next extraction stage based on current result
     * Uses deterministic fallback order to prevent loops
     */
    fun decideNext(result: ExtractResult, availableStages: Set<ExtractionStage>): ExtractionStage? {
        val currentStage = when (result) {
            is ExtractResult.Good -> return null // Success, no next stage needed
            is ExtractResult.LowQuality -> result.signals.stage
            is ExtractResult.Failed -> result.stage
        }
        
        // Find current stage position in fallback order
        val currentIndex = FALLBACK_ORDER.indexOf(currentStage)
        if (currentIndex == -1) {
            // Unknown stage, fall back to first available
            return availableStages.intersect(FALLBACK_ORDER.toSet()).firstOrNull()
        }
        
        // Try next stages in deterministic order
        for (i in (currentIndex + 1) until FALLBACK_ORDER.size) {
            val nextStage = FALLBACK_ORDER[i]
            if (availableStages.contains(nextStage)) {
                return nextStage
            }
        }
        
        // No more fallbacks available
        return null
    }
    
    /**
     * Check if result is acceptable for final use
     */
    fun isAcceptable(result: ExtractResult): Boolean {
        return when (result) {
            is ExtractResult.Good -> true
            is ExtractResult.LowQuality -> {
                // Accept low quality if we have some useful information
                result.info.storeName != com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE ||
                !result.info.redeemCode.isNullOrBlank() ||
                hasMeaningfulCashback(result.info.cashbackDetail) ||
                hasMeaningfulCashback(result.info.description)
            }
            is ExtractResult.Failed -> false
        }
    }
}

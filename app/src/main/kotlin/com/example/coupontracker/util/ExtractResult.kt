package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon

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
        val signals: ExtractionSignals
    ) : ExtractResult

    /**
     * Extraction completed but quality is insufficient
     */
    data class LowQuality(
        val info: CouponInfo,
        val reason: QualityReason,
        val signals: ExtractionSignals
    ) : ExtractResult

    /**
     * Extraction failed at a specific stage
     */
    data class Failed(
        val stage: ExtractionStage,
        val error: Throwable,
        val signals: ExtractionSignals? = null
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
 * Run path tracking for telemetry
 */
data class RunPath(
    val primary: String,
    val tried: List<String>,
    val final: String,
    val nativeAvailable: Boolean,
    val totalTimeMs: Long
)

/**
 * Extraction policy that decides next steps based on results
 */
object ExtractionPolicy {
    
    /**
     * Decide the next extraction stage based on current result
     */
    fun decideNext(result: ExtractResult, availableStages: Set<ExtractionStage>): ExtractionStage? {
        return when (result) {
            is ExtractResult.Good -> null // Success, no next stage needed
            
            is ExtractResult.LowQuality -> {
                when (result.reason) {
                    QualityReason.ALL_GENERIC_CONTENT,
                    QualityReason.DUPLICATE_FIELD_VALUES -> {
                        // Try ML Kit for better text recognition
                        if (availableStages.contains(ExtractionStage.MLKIT)) ExtractionStage.MLKIT
                        else if (availableStages.contains(ExtractionStage.TFLITE)) ExtractionStage.TFLITE
                        else ExtractionStage.REGEX
                    }
                    
                    QualityReason.LOW_QUALITY_EXTRACTION,
                    QualityReason.INSUFFICIENT_CONFIDENCE -> {
                        // Try TensorFlow Lite models
                        if (availableStages.contains(ExtractionStage.TFLITE)) ExtractionStage.TFLITE
                        else if (availableStages.contains(ExtractionStage.MLKIT)) ExtractionStage.MLKIT
                        else ExtractionStage.REGEX
                    }
                    
                    QualityReason.COMPLETE_EXTRACTION_FAILURE,
                    QualityReason.MISSING_CRITICAL_FIELDS -> {
                        // Fall back to any available method
                        availableStages.firstOrNull { it != result.signals.stage }
                    }
                }
            }
            
            is ExtractResult.Failed -> {
                // Try next available stage, avoiding the failed one
                when (result.stage) {
                    ExtractionStage.LLM -> {
                        if (availableStages.contains(ExtractionStage.MLKIT)) ExtractionStage.MLKIT
                        else if (availableStages.contains(ExtractionStage.TFLITE)) ExtractionStage.TFLITE
                        else ExtractionStage.REGEX
                    }
                    
                    ExtractionStage.MLKIT -> {
                        if (availableStages.contains(ExtractionStage.TFLITE)) ExtractionStage.TFLITE
                        else ExtractionStage.REGEX
                    }
                    
                    ExtractionStage.TFLITE -> ExtractionStage.REGEX
                    
                    ExtractionStage.REGEX,
                    ExtractionStage.TWO_STAGE_DETECTION -> null // No more fallbacks
                }
            }
        }
    }
    
    /**
     * Check if result is acceptable for final use
     */
    fun isAcceptable(result: ExtractResult): Boolean {
        return when (result) {
            is ExtractResult.Good -> true
            is ExtractResult.LowQuality -> {
                // Accept low quality if we have some useful information
                result.info.storeName != "Unknown Store" || 
                !result.info.redeemCode.isNullOrBlank() ||
                (result.info.cashbackAmount != null && result.info.cashbackAmount > 0)
            }
            is ExtractResult.Failed -> false
        }
    }
}

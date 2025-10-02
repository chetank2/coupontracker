package com.example.coupontracker.learning

import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ExtractionContext
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.ProgressiveExtractionResult
import com.example.coupontracker.universal.PatternLearningEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Integrates extraction results with learning systems.
 * 
 * Industry Standard: Active Learning Loop
 * - High confidence → Auto-learn (reinforce patterns)
 * - Low confidence → Flag for review
 * - User corrections → Update patterns
 * - Track everything → Measure improvement
 */
@Singleton
class ExtractionLearningIntegration @Inject constructor(
    private val patternLearningEngine: PatternLearningEngine,
    private val parameterChangeLogger: ParameterChangeLogger
) {
    
    companion object {
        private const val TAG = "ExtractionLearning"
        
        // Confidence thresholds (tunable parameters!)
        private const val AUTO_LEARN_THRESHOLD = 0.85f  // High confidence → auto-learn
        private const val LOW_CONFIDENCE_THRESHOLD = 0.6f  // Low confidence → flag for review
        private const val MINIMUM_CONFIDENCE = 0.4f  // Below this → likely failure
    }
    
    /**
     * Learn from extraction result automatically
     * Called after EVERY extraction in ProgressiveExtractionService
     */
    suspend fun learnFromExtraction(
        result: ProgressiveExtractionResult,
        context: com.example.coupontracker.extraction.ExtractionContext
    ) {
        try {
            Log.d(TAG, "Processing extraction for learning (confidence: ${result.confidence})")
            
            // 1. Auto-learn from high-confidence extractions
            if (result.confidence >= AUTO_LEARN_THRESHOLD) {
                Log.d(TAG, "✨ High confidence (${result.confidence}) - auto-learning patterns")
                learnFromHighConfidenceExtraction(result, context)
            }
            
            // 2. Flag low-confidence extractions for review
            if (result.confidence < LOW_CONFIDENCE_THRESHOLD) {
                Log.w(TAG, "⚠️  Low confidence (${result.confidence}) - flagging for review")
                flagForReview(result, context)
            }
            
            // 3. Detect if any fields are consistently failing
            detectProblematicFields(result)
            
            // 4. Log extraction telemetry (for analytics)
            logExtractionTelemetry(result, context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in learning integration", e)
        }
    }
    
    /**
     * Learn patterns from high-confidence extraction
     */
    private suspend fun learnFromHighConfidenceExtraction(
        result: ProgressiveExtractionResult,
        context: com.example.coupontracker.extraction.ExtractionContext
    ) {
        // Create universal context adapter
        val universalContext = com.example.coupontracker.universal.ExtractionContext(
            brandHint = result.extractedFields[com.example.coupontracker.data.model.FieldType.STORE_NAME]?.value
        )
        
        for ((fieldType, candidate) in result.extractedFields) {
            // Only learn from reasonably confident fields
            if (candidate.confidence >= AUTO_LEARN_THRESHOLD) {
                Log.d(TAG, "  Learning $fieldType pattern: '${candidate.value}' (conf: ${candidate.confidence})")
                
                patternLearningEngine.learnFromSuccess(
                    fieldType = fieldType,
                    extractedValue = candidate.value,
                    originalText = context.ocrText,
                    context = universalContext
                )
            }
        }
    }
    
    /**
     * Flag low-confidence extraction for manual review
     * In future: Could build a review queue UI
     */
    private fun flagForReview(
        result: ProgressiveExtractionResult,
        context: com.example.coupontracker.extraction.ExtractionContext
    ) {
        Log.w(TAG, """
            LOW CONFIDENCE EXTRACTION - Review Recommended
            Overall: ${result.confidence}
            Fields:
            ${result.extractedFields.entries.joinToString("\n") { (type, candidate) ->
                "  - $type: '${candidate.value}' (conf: ${candidate.confidence}, source: ${candidate.source})"
            }}
            OCR Preview: ${context.ocrText.take(100)}...
        """.trimIndent())
        
        // TODO: Add to review queue database
        // reviewQueueDao.insert(ReviewQueueItem(...))
    }
    
    /**
     * Detect fields that are consistently low confidence
     * Could indicate need for parameter tuning
     */
    private fun detectProblematicFields(result: ProgressiveExtractionResult) {
        val problematicFields = result.extractedFields.filter { (_, candidate) ->
            candidate.confidence < MINIMUM_CONFIDENCE
        }
        
        if (problematicFields.isNotEmpty()) {
            Log.w(TAG, "🔧 Problematic fields detected (may need parameter tuning):")
            problematicFields.forEach { (type, candidate) ->
                Log.w(TAG, "  - $type: confidence=${candidate.confidence}, source=${candidate.source}")
            }
        }
    }
    
    /**
     * Log extraction telemetry for performance analysis
     */
    private fun logExtractionTelemetry(
        result: ProgressiveExtractionResult,
        context: ExtractionContext
    ) {
        Log.d(TAG, """
            📊 Extraction Telemetry:
            - Overall confidence: ${result.confidence}
            - Passes used: ${result.passesUsed} / 6
            - Fields extracted: ${result.extractedFields.size}
            - Strategies used: ${context.attempts.joinToString(", ") { it.strategy }}
        """.trimIndent())
        
        // TODO: Store in ExtractionFeedback table for analytics
        // extractionFeedbackDao.insert(ExtractionFeedback(...))
    }
    
    /**
     * Learn from user correction (when user edits extraction)
     * Called from UI when user confirms/corrects
     */
    suspend fun learnFromUserCorrection(
        originalResult: ProgressiveExtractionResult,
        correctedFields: Map<com.example.coupontracker.data.model.FieldType, String>,
        context: com.example.coupontracker.extraction.ExtractionContext
    ) {
        Log.d(TAG, "📝 Learning from user corrections (${correctedFields.size} fields)")
        
        for ((fieldType, correctValue) in correctedFields) {
            val originalValue = originalResult.extractedFields[fieldType]?.value
            
            if (originalValue != null && originalValue != correctValue) {
                Log.d(TAG, "  Correcting $fieldType: '$originalValue' → '$correctValue'")
                
                // Create universal context adapter
                val universalContext = com.example.coupontracker.universal.ExtractionContext(
                    brandHint = originalResult.extractedFields[com.example.coupontracker.data.model.FieldType.STORE_NAME]?.value
                )
                
                patternLearningEngine.learnFromCorrection(
                    fieldType = fieldType,
                    incorrectValue = originalValue,
                    correctValue = correctValue,
                    originalText = context.ocrText,
                    context = universalContext
                )
            }
        }
    }
    
    /**
     * Log a parameter change (for documentation and rollback)
     */
    fun logParameterChange(change: ParameterChange) {
        parameterChangeLogger.logChange(change)
    }
}


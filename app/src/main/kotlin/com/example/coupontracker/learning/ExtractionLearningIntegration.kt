package com.example.coupontracker.learning

import android.util.Log
import com.example.coupontracker.data.local.ExtractionFeedback
import com.example.coupontracker.data.local.ExtractionFeedbackDao
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ExtractionContext as ProgressiveExtractionContext
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.ProgressiveExtractionResult
import com.example.coupontracker.universal.PatternLearningEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
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
    private val parameterChangeLogger: ParameterChangeLogger,
    private val extractionFeedbackDao: ExtractionFeedbackDao
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
        context: ProgressiveExtractionContext
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
        context: ProgressiveExtractionContext
    ) {
        // Create universal context adapter
        val universalContext = com.example.coupontracker.universal.ExtractionContext(
            brandHint = result.extractedFields[FieldType.STORE_NAME]?.value
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
    private suspend fun flagForReview(
        result: ProgressiveExtractionResult,
        context: ProgressiveExtractionContext
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
        
        val fieldConfidences = buildFieldConfidenceJson(result.extractedFields)
        val strategy = determineStrategy(result, context)
        val reviewFeedback = ExtractionFeedback(
            couponId = result.coupon.id.takeIf { it > 0 },
            extractionStrategy = strategy,
            feedbackType = "needs_review",
            originalValues = buildFieldValuesJson(result.extractedFields),
            correctedValues = null,
            signalsJson = buildSignalsJson(result, context, fieldConfidences),
            runPathJson = buildRunPathJson(context, result),
            deviceInfo = extractDeviceInfo(context),
            consentGiven = isTelemetryConsentGranted(context)
        )

        try {
            withContext(Dispatchers.IO) {
                extractionFeedbackDao.insertFeedback(reviewFeedback)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to persist review queue entry", error)
        }
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
    private suspend fun logExtractionTelemetry(
        result: ProgressiveExtractionResult,
        context: ProgressiveExtractionContext
    ) {
        Log.d(TAG, """
            📊 Extraction Telemetry:
            - Overall confidence: ${result.confidence}
            - Passes used: ${result.passesUsed} / 6
            - Fields extracted: ${result.extractedFields.size}
            - Strategies used: ${context.attempts.joinToString(", ") { it.strategy }}
        """.trimIndent())
        
        val fieldConfidences = buildFieldConfidenceJson(result.extractedFields)
        val feedbackType = when {
            result.success && result.confidence >= AUTO_LEARN_THRESHOLD -> "auto_success"
            result.success -> "confirmed_correct"
            else -> "failed"
        }

        val telemetryFeedback = ExtractionFeedback(
            couponId = result.coupon.id.takeIf { it > 0 },
            extractionStrategy = determineStrategy(result, context),
            feedbackType = feedbackType,
            originalValues = buildFieldValuesJson(result.extractedFields),
            correctedValues = null,
            signalsJson = buildSignalsJson(result, context, fieldConfidences),
            runPathJson = buildRunPathJson(context, result),
            deviceInfo = extractDeviceInfo(context),
            consentGiven = isTelemetryConsentGranted(context)
        )

        try {
            withContext(Dispatchers.IO) {
                extractionFeedbackDao.insertFeedback(telemetryFeedback)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to persist extraction telemetry", error)
        }
    }
    
    /**
     * Learn from user correction (when user edits extraction)
     * Called from UI when user confirms/corrects
     */
    suspend fun learnFromUserCorrection(
        originalResult: ProgressiveExtractionResult,
        correctedFields: Map<FieldType, String>,
        context: ProgressiveExtractionContext
    ) {
        Log.d(TAG, "📝 Learning from user corrections (${correctedFields.size} fields)")
        
        for ((fieldType, correctValue) in correctedFields) {
            val originalValue = originalResult.extractedFields[fieldType]?.value
            
            if (originalValue != null && originalValue != correctValue) {
                Log.d(TAG, "  Correcting $fieldType: '$originalValue' → '$correctValue'")
                
                // Create universal context adapter
                val universalContext = com.example.coupontracker.universal.ExtractionContext(
                    brandHint = originalResult.extractedFields[FieldType.STORE_NAME]?.value
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

    private fun buildFieldValuesJson(fields: Map<FieldType, FieldCandidate>): String {
        val json = JSONObject()
        fields.forEach { (fieldType, candidate) ->
            json.put(fieldType.name.lowercase(Locale.US), candidate.value)
        }
        return json.toString()
    }

    private fun buildFieldConfidenceJson(fields: Map<FieldType, FieldCandidate>): JSONObject {
        val json = JSONObject()
        fields.forEach { (fieldType, candidate) ->
            json.put(fieldType.name.lowercase(Locale.US), candidate.confidence)
        }
        return json
    }

    private fun buildSignalsJson(
        result: ProgressiveExtractionResult,
        context: ProgressiveExtractionContext,
        fieldConfidences: JSONObject
    ): String {
        val signals = JSONObject()
        signals.put("overallConfidence", result.confidence)
        signals.put("success", result.success)
        signals.put("passesUsed", result.passesUsed)
        signals.put("fieldConfidences", fieldConfidences)
        result.error?.let { signals.put("error", it) }
        signals.put("attemptCount", context.attempts.size)
        if (context.metadata.isNotEmpty()) {
            val metadataJson = JSONObject()
            context.metadata.forEach { (key, value) ->
                metadataJson.put(key, value)
            }
            signals.put("metadata", metadataJson)
        }
        return signals.toString()
    }

    private fun buildRunPathJson(
        context: ProgressiveExtractionContext,
        result: ProgressiveExtractionResult
    ): String {
        val attempts = if (context.attempts.isNotEmpty()) {
            context.attempts
        } else {
            result.extractionAttempts
        }

        val array = JSONArray()
        attempts.forEach { attempt ->
            val attemptJson = JSONObject()
            attemptJson.put("pass", attempt.passName)
            attemptJson.put("strategy", attempt.strategy)
            attemptJson.put("confidence", attempt.confidence)
            attemptJson.put("durationMs", attempt.durationMs)
            attempt.reason?.let { attemptJson.put("reason", it) }
            array.put(attemptJson)
        }
        return array.toString()
    }

    private fun determineStrategy(
        result: ProgressiveExtractionResult,
        context: ProgressiveExtractionContext
    ): String {
        return result.extractionAttempts.lastOrNull()?.strategy
            ?: context.attempts.lastOrNull()?.strategy
            ?: "unknown"
    }

    private fun extractDeviceInfo(context: ProgressiveExtractionContext): String {
        return context.metadata["device_info"]
            ?: context.metadata["device"]
            ?: context.metadata["hardware"]
            ?: "unknown"
    }

    private fun isTelemetryConsentGranted(context: ProgressiveExtractionContext): Boolean {
        return context.metadata["telemetry_consent"]?.equals("true", ignoreCase = true) == true
    }
}

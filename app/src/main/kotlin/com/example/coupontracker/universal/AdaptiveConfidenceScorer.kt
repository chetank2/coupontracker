package com.example.coupontracker.universal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Adaptive confidence scorer that learns from user feedback and extraction success
 * to provide accurate confidence scores without hardcoded rules.
 */
@Singleton
class AdaptiveConfidenceScorer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AdaptiveConfidenceScorer"
        private const val PREFS_NAME = "confidence_scorer"
        private const val KEY_FEATURE_WEIGHTS = "feature_weights"
        private const val KEY_CALIBRATION_DATA = "calibration_data"
        private const val LEARNING_RATE = 0.1f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // Feature weights for different aspects of extraction
    private val featureWeights = mutableMapOf<String, Float>().apply {
        // Initialize with reasonable defaults
        put("source_visual_region", 0.8f)
        put("source_context_clues", 0.7f)
        put("source_pattern_matching", 0.6f)
        put("source_learned_pattern", 0.9f)
        put("length_appropriate", 0.5f)
        put("context_keywords", 0.6f)
        put("visual_prominence", 0.7f)
        put("position_typical", 0.5f)
        put("pattern_confidence", 0.8f)
    }
    
    // Calibration data for mapping raw scores to calibrated probabilities
    private val calibrationBuckets = mutableMapOf<FieldType, MutableList<CalibrationPoint>>()
    
    init {
        loadCalibrationData()
    }

    /**
     * Score an extraction candidate using learned features
     */
    suspend fun scoreCandidate(
        candidate: ExtractionCandidate,
        fieldType: FieldType
    ): Float = withContext(Dispatchers.Default) {
        
        val features = extractFeatures(candidate, fieldType)
        val rawScore = calculateRawScore(features)
        val calibratedScore = calibrateScore(rawScore, fieldType)
        
        Log.d(TAG, "Scored $fieldType candidate '${candidate.text}': raw=$rawScore, calibrated=$calibratedScore")
        
        calibratedScore
    }

    /**
     * Update confidence scoring based on user feedback
     */
    suspend fun updateFromFeedback(
        candidate: ExtractionCandidate,
        fieldType: FieldType,
        wasCorrect: Boolean
    ) = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Learning from feedback: ${candidate.text} was ${if (wasCorrect) "correct" else "incorrect"}")
        
        val features = extractFeatures(candidate, fieldType)
        val currentScore = calculateRawScore(features)
        
        // Update feature weights based on feedback
        updateFeatureWeights(features, wasCorrect, currentScore)
        
        // Add calibration data point
        addCalibrationPoint(fieldType, currentScore, wasCorrect)
        
        // Save updated weights and calibration data
        saveCalibrationData()
    }

    /**
     * Get feature importance for debugging and monitoring
     */
    fun getFeatureImportance(): Map<String, Float> {
        return featureWeights.toMap()
    }

    /**
     * Reset all learned weights (for testing)
     */
    suspend fun resetLearning() = withContext(Dispatchers.IO) {
        featureWeights.clear()
        calibrationBuckets.clear()
        prefs.edit().clear().apply()
        Log.i(TAG, "All confidence learning data reset")
    }

    // Private helper methods

    private fun extractFeatures(candidate: ExtractionCandidate, fieldType: FieldType): Map<String, Float> {
        val features = mutableMapOf<String, Float>()
        
        // Source-based features
        features["source_visual_region"] = if (candidate.source == ExtractionSource.VISUAL_REGION) 1.0f else 0.0f
        features["source_context_clues"] = if (candidate.source == ExtractionSource.CONTEXT_CLUES) 1.0f else 0.0f
        features["source_pattern_matching"] = if (candidate.source == ExtractionSource.PATTERN_MATCHING) 1.0f else 0.0f
        features["source_learned_pattern"] = if (candidate.source == ExtractionSource.LEARNED_PATTERN) 1.0f else 0.0f
        
        // Text-based features
        features["length_appropriate"] = calculateLengthScore(candidate.text, fieldType)
        features["context_keywords"] = calculateContextScore(candidate.context)
        features["pattern_confidence"] = candidate.confidence
        
        // Field-specific features
        when (fieldType) {
            FieldType.COUPON_CODE -> {
                features["code_alphanumeric"] = if (candidate.text.matches(Regex("[A-Z0-9-_]+"))) 1.0f else 0.0f
                features["code_has_numbers"] = if (candidate.text.contains(Regex("\\d"))) 1.0f else 0.0f
                features["code_reasonable_length"] = if (candidate.text.length in 4..20) 1.0f else 0.0f
            }
            FieldType.EXPIRY_DATE -> {
                features["date_has_month"] = if (candidate.text.contains(Regex("(?i)jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"))) 1.0f else 0.0f
                features["date_has_year"] = if (candidate.text.contains(Regex("20\\d{2}"))) 1.0f else 0.0f
                features["date_reasonable_format"] = calculateDateFormatScore(candidate.text)
            }
            FieldType.AMOUNT -> {
                features["amount_has_currency"] = if (candidate.text.contains(Regex("[₹$%]"))) 1.0f else 0.0f
                features["amount_has_numbers"] = if (candidate.text.contains(Regex("\\d"))) 1.0f else 0.0f
                features["amount_reasonable_value"] = calculateAmountReasonablenessScore(candidate.text)
            }
            FieldType.STORE_NAME -> {
                features["store_proper_case"] = if (candidate.text.matches(Regex("[A-Z][a-z]+"))) 1.0f else 0.0f
                features["store_reasonable_length"] = if (candidate.text.length in 3..25) 1.0f else 0.0f
                features["store_no_numbers_only"] = if (!candidate.text.matches(Regex("\\d+"))) 1.0f else 0.0f
            }
            else -> { /* No specific features */ }
        }
        
        return features
    }

    private fun calculateRawScore(features: Map<String, Float>): Float {
        var score = 0.0f
        var totalWeight = 0.0f
        
        for ((feature, value) in features) {
            val weight = featureWeights[feature] ?: 0.5f
            score += weight * value
            totalWeight += weight
        }
        
        return if (totalWeight > 0) score / totalWeight else 0.5f
    }

    private fun calibrateScore(rawScore: Float, fieldType: FieldType): Float {
        val calibrationPoints = calibrationBuckets[fieldType] ?: return rawScore
        
        if (calibrationPoints.isEmpty()) return rawScore
        
        // Find the calibration bucket for this raw score
        val bucketSize = 0.1f
        val bucketIndex = (rawScore / bucketSize).toInt().coerceIn(0, 9)
        
        val bucketPoints = calibrationPoints.filter { 
            val pointBucket = (it.rawScore / bucketSize).toInt()
            pointBucket == bucketIndex
        }
        
        if (bucketPoints.isEmpty()) return rawScore
        
        // Calculate calibrated probability for this bucket
        val correctCount = bucketPoints.count { it.wasCorrect }
        val totalCount = bucketPoints.size
        
        return correctCount.toFloat() / totalCount
    }

    private fun updateFeatureWeights(
        features: Map<String, Float>,
        wasCorrect: Boolean,
        currentScore: Float
    ) {
        val target = if (wasCorrect) 1.0f else 0.0f
        val error = target - currentScore
        
        // Update weights using gradient descent
        for ((feature, value) in features) {
            val currentWeight = featureWeights[feature] ?: 0.5f
            val gradient = error * value
            val newWeight = currentWeight + LEARNING_RATE * gradient
            
            featureWeights[feature] = newWeight.coerceIn(0.0f, 1.0f)
        }
    }

    private fun addCalibrationPoint(fieldType: FieldType, rawScore: Float, wasCorrect: Boolean) {
        val points = calibrationBuckets.getOrPut(fieldType) { mutableListOf() }
        points.add(CalibrationPoint(rawScore, wasCorrect))
        
        // Limit calibration points to prevent memory issues
        if (points.size > 1000) {
            points.removeAt(0) // Remove oldest point
        }
    }

    // Feature calculation helper methods

    private fun calculateLengthScore(text: String, fieldType: FieldType): Float {
        val idealRanges = mapOf(
            FieldType.COUPON_CODE to 4..20,
            FieldType.STORE_NAME to 3..25,
            FieldType.AMOUNT to 1..15,
            FieldType.EXPIRY_DATE to 5..30
        )
        
        val range = idealRanges[fieldType] ?: return 0.5f
        
        return if (text.length in range.first..range.last) 1.0f else {
            val distance = minOf(
                abs(text.length - range.first),
                abs(text.length - range.last)
            )
            exp(-distance * 0.1).toFloat()
        }
    }

    private fun calculateContextScore(context: Map<String, String>): Float {
        var score = 0.0f
        
        // Check for positive context indicators
        val positiveIndicators = listOf("pattern", "region", "context")
        positiveIndicators.forEach { indicator ->
            if (context.keys.any { it.contains(indicator, ignoreCase = true) }) {
                score += 0.3f
            }
        }
        
        return score.coerceAtMost(1.0f)
    }

    private fun calculateDateFormatScore(text: String): Float {
        val datePatterns = listOf(
            Regex("""\d{1,2}\s+\w+\s*,?\s*\d{4}"""), // "31 May, 2025"
            Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""), // "31/05/2025"
            Regex("""\w+\s+\d{1,2},\s*\d{4}""") // "May 31, 2025"
        )
        
        return if (datePatterns.any { it.containsMatchIn(text) }) 1.0f else 0.2f
    }

    private fun calculateAmountReasonablenessScore(text: String): Float {
        // Extract numeric value
        val numericValue = Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull() ?: return 0.0f
        
        // Check if value is in reasonable range
        return when {
            numericValue in 1.0..10000.0 -> 1.0f // Reasonable cashback range
            numericValue in 0.1..100.0 && text.contains("%") -> 1.0f // Reasonable percentage
            else -> 0.3f
        }
    }

    private fun loadCalibrationData() {
        try {
            val weightsJson = prefs.getString(KEY_FEATURE_WEIGHTS, null)
            if (weightsJson != null) {
                val type = object : TypeToken<Map<String, Float>>() {}.type
                val loadedWeights: Map<String, Float> = gson.fromJson(weightsJson, type)
                featureWeights.putAll(loadedWeights)
            }
            
            val calibrationJson = prefs.getString(KEY_CALIBRATION_DATA, null)
            if (calibrationJson != null) {
                val type = object : TypeToken<Map<FieldType, List<CalibrationPoint>>>() {}.type
                val loadedCalibration: Map<FieldType, List<CalibrationPoint>> = gson.fromJson(calibrationJson, type)
                calibrationBuckets.clear()
                loadedCalibration.forEach { (fieldType, points) ->
                    calibrationBuckets[fieldType] = points.toMutableList()
                }
            }
            
            Log.d(TAG, "Loaded confidence scorer calibration data")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading calibration data", e)
        }
    }

    private fun saveCalibrationData() {
        try {
            val weightsJson = gson.toJson(featureWeights)
            val calibrationJson = gson.toJson(calibrationBuckets)
            
            prefs.edit()
                .putString(KEY_FEATURE_WEIGHTS, weightsJson)
                .putString(KEY_CALIBRATION_DATA, calibrationJson)
                .apply()
                
        } catch (e: Exception) {
            Log.e(TAG, "Error saving calibration data", e)
        }
    }
}

/**
 * Represents a calibration data point
 */
data class CalibrationPoint(
    val rawScore: Float,
    val wasCorrect: Boolean
)

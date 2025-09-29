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
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine that learns extraction patterns from successful coupon extractions
 * and user corrections, eliminating the need for hardcoded brand-specific rules.
 */
@Singleton
class PatternLearningEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PatternLearningEngine"
        private const val PREFS_NAME = "pattern_learning"
        private const val KEY_LEARNED_PATTERNS = "learned_patterns"
        private const val KEY_PATTERN_STATS = "pattern_stats"
        private const val MAX_PATTERNS_PER_FIELD = 50
        private const val MIN_PATTERN_CONFIDENCE = 0.3f
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // In-memory cache of learned patterns
    private val learnedPatterns = mutableMapOf<FieldType, MutableList<LearnedPattern>>()
    private val patternStats = mutableMapOf<String, PatternStats>()
    
    init {
        loadLearnedPatterns()
    }

    /**
     * Learn from a successful extraction
     */
    suspend fun learnFromSuccess(
        fieldType: FieldType,
        extractedValue: String,
        originalText: String,
        context: ExtractionContext
    ) = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Learning from successful extraction: $fieldType = '$extractedValue'")
        
        // Extract pattern from successful extraction
        val pattern = extractPattern(extractedValue, originalText, fieldType)
        if (pattern != null) {
            recordPattern(fieldType, pattern, context, success = true)
        }
    }

    /**
     * Learn from user correction
     */
    suspend fun learnFromCorrection(
        fieldType: FieldType,
        incorrectValue: String,
        correctValue: String,
        originalText: String,
        context: ExtractionContext
    ) = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Learning from correction: $fieldType '$incorrectValue' -> '$correctValue'")
        
        // Penalize the incorrect pattern
        val incorrectPattern = extractPattern(incorrectValue, originalText, fieldType)
        if (incorrectPattern != null) {
            recordPattern(fieldType, incorrectPattern, context, success = false)
        }
        
        // Reward the correct pattern
        val correctPattern = extractPattern(correctValue, originalText, fieldType)
        if (correctPattern != null) {
            recordPattern(fieldType, correctPattern, context, success = true)
        }
    }

    /**
     * Get relevant patterns for a field type and context
     */
    suspend fun getRelevantPatterns(
        fieldType: FieldType,
        context: ExtractionContext
    ): List<LearnedPattern> = withContext(Dispatchers.IO) {
        
        val patterns = learnedPatterns[fieldType] ?: return@withContext emptyList()
        
        return@withContext patterns
            .filter { it.confidence >= MIN_PATTERN_CONFIDENCE }
            .filter { isRelevantForContext(it, context) }
            .sortedByDescending { it.confidence }
            .take(10) // Limit to top patterns
    }

    /**
     * Get pattern statistics for monitoring
     */
    fun getPatternStats(): Map<FieldType, PatternFieldStats> {
        return FieldType.values().associateWith { fieldType ->
            val patterns = learnedPatterns[fieldType] ?: emptyList()
            PatternFieldStats(
                totalPatterns = patterns.size,
                averageConfidence = patterns.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0,
                topPatterns = patterns.sortedByDescending { it.confidence }.take(5)
            )
        }
    }

    /**
     * Clear all learned patterns (for testing or reset)
     */
    suspend fun clearAllPatterns() = withContext(Dispatchers.IO) {
        learnedPatterns.clear()
        patternStats.clear()
        prefs.edit().clear().apply()
        Log.i(TAG, "All learned patterns cleared")
    }

    // Private helper methods

    private fun extractPattern(
        value: String,
        fullText: String,
        fieldType: FieldType
    ): String? {
        
        val valueIndex = fullText.indexOf(value, ignoreCase = true)
        if (valueIndex == -1) return null
        
        return when (fieldType) {
            FieldType.COUPON_CODE -> extractCodePattern(value, fullText, valueIndex)
            FieldType.EXPIRY_DATE -> extractDatePattern(value, fullText, valueIndex)
            FieldType.AMOUNT -> extractAmountPattern(value, fullText, valueIndex)
            FieldType.STORE_NAME -> extractStorePattern(value, fullText, valueIndex)
            else -> null
        }
    }

    private fun extractCodePattern(value: String, fullText: String, valueIndex: Int): String? {
        // Extract context around the code
        val beforeContext = fullText.substring(0, valueIndex).takeLast(20)
        val afterContext = fullText.substring(valueIndex + value.length).take(20)
        
        // Create pattern with context
        val beforePattern = if (beforeContext.contains(Regex("""(?i)\b(?:code|coupon|promo|use|apply)\s*:?\s*$"""))) {
            """(?i)(?:code|coupon|promo|use|apply)\s*:?\s*"""
        } else {
            ""
        }
        
        val codePattern = when {
            value.contains("-") -> """[A-Z0-9]+-[A-Z0-9-]+"""
            value.length >= 10 -> """[A-Z0-9]{10,}"""
            else -> """[A-Z0-9]{4,12}"""
        }
        
        return "$beforePattern($codePattern)"
    }

    private fun extractDatePattern(value: String, fullText: String, valueIndex: Int): String? {
        val beforeContext = fullText.substring(0, valueIndex).takeLast(30)
        
        // Look for date context keywords
        val contextPattern = if (beforeContext.contains(Regex("""(?i)\b(?:exp|expires|valid|until|till)\s*:?\s*$"""))) {
            """(?i)(?:exp|expires|valid|until|till)\s*:?\s*"""
        } else {
            ""
        }
        
        // Create flexible date pattern based on the actual value format
        val datePattern = when {
            value.matches(Regex("""\d{1,2}\s+\w+\s*,?\s*\d{4}""")) -> """\d{1,2}\s+\w+\s*,?\s*\d{4}"""
            value.matches(Regex("""\d{1,2}[/-]\d{1,2}[/-]\d{2,4}""")) -> """\d{1,2}[/-]\d{1,2}[/-]\d{2,4}"""
            else -> """\d{1,2}\s*\w+\s*,?\s*\d{2,4}"""
        }
        
        return "$contextPattern($datePattern)"
    }

    private fun extractAmountPattern(value: String, fullText: String, valueIndex: Int): String? {
        val beforeContext = fullText.substring(0, valueIndex).takeLast(20)
        val afterContext = fullText.substring(valueIndex + value.length).take(20)
        
        // Detect currency and percentage patterns
        val amountPattern = when {
            value.contains("₹") -> """₹\s*[0-9,]+(?:\.[0-9]{1,2})?"""
            value.contains("%") -> """[0-9]+(?:\.[0-9]{1,2})?\s*%"""
            value.matches(Regex("""[0-9,]+""")) -> """[0-9,]+(?:\.[0-9]{1,2})?"""
            else -> return null
        }
        
        // Add context if present
        val contextPrefix = if (beforeContext.contains(Regex("""(?i)(?:get|save|flat|up\s*to)\s*$"""))) {
            """(?i)(?:get|save|flat|up\s*to)\s*"""
        } else ""
        
        val contextSuffix = if (afterContext.contains(Regex("""^\s*(?:off|cashback|discount)"""))) {
            """\s*(?:off|cashback|discount)?"""
        } else ""
        
        return "$contextPrefix($amountPattern)$contextSuffix"
    }

    private fun extractStorePattern(value: String, fullText: String, valueIndex: Int): String? {
        val beforeContext = fullText.substring(0, valueIndex).takeLast(20)
        
        // Create pattern based on store name characteristics
        val storePattern = when {
            value.matches(Regex("""[A-Z][a-z]+""")) -> """[A-Z][a-z]+"""
            value.contains(" ") -> """[A-Za-z]+\s+[A-Za-z]+"""
            else -> """[A-Za-z0-9]+"""
        }
        
        // Add context if present
        val contextPrefix = if (beforeContext.contains(Regex("""(?i)(?:from|at|by|shop|store)\s*$"""))) {
            """(?i)(?:from|at|by|shop|store)\s+"""
        } else if (valueIndex < 50) { // Near beginning of text
            "^.*?"
        } else ""
        
        return "$contextPrefix($storePattern)"
    }

    private fun recordPattern(
        fieldType: FieldType,
        pattern: String,
        context: ExtractionContext,
        success: Boolean
    ) {
        val patterns = learnedPatterns.getOrPut(fieldType) { mutableListOf() }
        
        // Find existing pattern or create new one
        val existingPattern = patterns.find { it.pattern == pattern }
        
        if (existingPattern != null) {
            // Update existing pattern statistics
            val stats = patternStats.getOrPut(existingPattern.id) { 
                PatternStats(successCount = 0, failureCount = 0, contexts = mutableSetOf()) 
            }
            
            if (success) {
                stats.successCount++
            } else {
                stats.failureCount++
            }
            
            context.brandHint?.let { stats.contexts.add(it) }
            
            // Recalculate confidence
            existingPattern.confidence = calculateConfidence(stats)
            existingPattern.lastUsed = Date()
            
        } else {
            // Create new pattern
            val newPattern = LearnedPattern(
                id = UUID.randomUUID().toString(),
                fieldType = fieldType,
                pattern = pattern,
                confidence = if (success) 0.7f else 0.3f,
                createdAt = Date(),
                lastUsed = Date()
            )
            
            patterns.add(newPattern)
            
            val stats = PatternStats(
                successCount = if (success) 1 else 0,
                failureCount = if (success) 0 else 1,
                contexts = mutableSetOf<String>().apply { 
                    context.brandHint?.let { add(it) }
                }
            )
            patternStats[newPattern.id] = stats
        }
        
        // Limit number of patterns per field
        if (patterns.size > MAX_PATTERNS_PER_FIELD) {
            patterns.sortBy { it.confidence }
            val toRemove = patterns.take(patterns.size - MAX_PATTERNS_PER_FIELD)
            patterns.removeAll(toRemove.toSet())
            toRemove.forEach { patternStats.remove(it.id) }
        }
        
        // Save to persistent storage
        saveLearnedPatterns()
    }

    private fun calculateConfidence(stats: PatternStats): Float {
        val total = stats.successCount + stats.failureCount
        if (total == 0) return 0.5f
        
        val baseConfidence = stats.successCount.toFloat() / total
        
        // Boost confidence based on usage frequency
        val usageBoost = minOf(0.2f, total * 0.02f)
        
        return (baseConfidence + usageBoost).coerceIn(0.0f, 1.0f)
    }

    private fun isRelevantForContext(pattern: LearnedPattern, context: ExtractionContext): Boolean {
        // If no brand hint, pattern is relevant
        if (context.brandHint == null) return true
        
        // Check if pattern has been successful with this brand
        val stats = patternStats[pattern.id] ?: return true
        return stats.contexts.isEmpty() || stats.contexts.contains(context.brandHint)
    }

    private fun loadLearnedPatterns() {
        try {
            val patternsJson = prefs.getString(KEY_LEARNED_PATTERNS, null)
            if (patternsJson != null) {
                val type = object : TypeToken<Map<FieldType, List<LearnedPattern>>>() {}.type
                val loadedPatterns: Map<FieldType, List<LearnedPattern>> = gson.fromJson(patternsJson, type)
                
                learnedPatterns.clear()
                loadedPatterns.forEach { (fieldType, patterns) ->
                    learnedPatterns[fieldType] = patterns.toMutableList()
                }
            }
            
            val statsJson = prefs.getString(KEY_PATTERN_STATS, null)
            if (statsJson != null) {
                val type = object : TypeToken<Map<String, PatternStats>>() {}.type
                val loadedStats: Map<String, PatternStats> = gson.fromJson(statsJson, type)
                patternStats.putAll(loadedStats)
            }
            
            Log.d(TAG, "Loaded ${learnedPatterns.values.sumOf { it.size }} learned patterns")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error loading learned patterns", e)
            learnedPatterns.clear()
            patternStats.clear()
        }
    }

    private fun saveLearnedPatterns() {
        try {
            val patternsJson = gson.toJson(learnedPatterns)
            val statsJson = gson.toJson(patternStats)
            
            prefs.edit()
                .putString(KEY_LEARNED_PATTERNS, patternsJson)
                .putString(KEY_PATTERN_STATS, statsJson)
                .apply()
                
        } catch (e: Exception) {
            Log.e(TAG, "Error saving learned patterns", e)
        }
    }
}

/**
 * A pattern learned from successful extractions
 */
data class LearnedPattern(
    val id: String,
    val fieldType: FieldType,
    val pattern: String,
    var confidence: Float,
    val createdAt: Date,
    var lastUsed: Date
)

/**
 * Statistics for a learned pattern
 */
data class PatternStats(
    var successCount: Int,
    var failureCount: Int,
    val contexts: MutableSet<String>
)

/**
 * Statistics for patterns of a specific field type
 */
data class PatternFieldStats(
    val totalPatterns: Int,
    val averageConfidence: Double,
    val topPatterns: List<LearnedPattern>
)

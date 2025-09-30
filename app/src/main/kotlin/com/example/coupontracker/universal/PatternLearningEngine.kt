package com.example.coupontracker.universal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.coupontracker.data.local.LearnedPatternDao
import com.example.coupontracker.data.model.FieldType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine that learns extraction patterns from successful coupon extractions
 * and user corrections, eliminating the need for hardcoded brand-specific rules.
 * 
 * V2: Migrated from SharedPreferences to Room database for better storage and queries
 */
@Singleton
class PatternLearningEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val learnedPatternDao: LearnedPatternDao  // V2: Injected Room DAO
) {
    companion object {
        private const val TAG = "PatternLearningEngine"
        private const val PREFS_NAME = "pattern_learning"
        private const val KEY_LEARNED_PATTERNS = "learned_patterns"
        private const val KEY_PATTERN_STATS = "pattern_stats"
        private const val KEY_MIGRATED_TO_ROOM = "migrated_to_room_v2"  // V2: Migration flag
        private const val MAX_PATTERNS_PER_FIELD = 50
        private const val MIN_PATTERN_CONFIDENCE = 0.3f
    }

    // V2: Keep SharedPreferences for one-time migration only
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // V2: In-memory cache no longer used for primary storage
    // Kept temporarily for migration purposes only
    private val learnedPatterns = mutableMapOf<FieldType, MutableList<LearnedPattern>>()
    private val patternStats = mutableMapOf<String, PatternStats>()
    
    init {
        // V2: One-time migration from SharedPreferences to Room
        // Using CoroutineScope for async migration on app start
        CoroutineScope(Dispatchers.IO).launch {
            migrateFromSharedPreferencesIfNeeded()
        }
    }

    /**
     * V2: One-time migration from SharedPreferences to Room
     * Moves all learned patterns from old storage to new database
     */
    private suspend fun migrateFromSharedPreferencesIfNeeded() = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(KEY_MIGRATED_TO_ROOM, false)) {
            Log.d(TAG, "Already migrated to Room, skipping")
            return@withContext
        }
        
        try {
            Log.d(TAG, "Starting migration from SharedPreferences to Room...")
            
            // Load patterns from SharedPreferences
            loadLearnedPatterns()  // This populates learnedPatterns from prefs
            
            var migratedCount = 0
            var skippedCount = 0
            
            // Migrate each pattern to Room
            learnedPatterns.forEach { (fieldType, patterns) ->
                patterns.forEach { oldPattern ->
                    try {
                        // Get stats for this pattern
                        val stats = patternStats[oldPattern.id]
                        val successCount = stats?.successCount ?: 1
                        val failureCount = stats?.failureCount ?: 0
                        val totalCount = successCount + failureCount
                        
                        // Convert to Room entity
                        val roomPattern = com.example.coupontracker.data.local.LearnedPattern(
                            brand = stats?.contexts?.firstOrNull(),  // Use first context as brand
                            fieldType = fieldType.name,
                            regex = oldPattern.pattern,
                            weight = oldPattern.confidence,
                            source = "migrated_from_prefs",
                            sampleValue = "legacy_pattern",  // No sample value in old format
                            createdAt = oldPattern.createdAt.time,
                            successCount = successCount,
                            attemptCount = totalCount
                        )
                        
                        learnedPatternDao.insertPattern(roomPattern)
                        migratedCount++
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to migrate pattern ${oldPattern.id}", e)
                        skippedCount++
                    }
                }
            }
            
            // Mark migration as complete
            prefs.edit().putBoolean(KEY_MIGRATED_TO_ROOM, true).apply()
            
            Log.d(TAG, "✅ Migration complete: $migratedCount patterns migrated, $skippedCount skipped")
            
            // Clear old data from memory (keep SharedPreferences for rollback if needed)
            learnedPatterns.clear()
            patternStats.clear()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Migration failed", e)
            // Don't set migration flag so it can be retried
        }
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
     * V2: Now queries Room database directly
     */
    suspend fun getRelevantPatterns(
        fieldType: FieldType,
        context: ExtractionContext
    ): List<LearnedPattern> = withContext(Dispatchers.IO) {
        
        // V2: Query Room database, optionally filtered by brand
        val roomPatterns = if (context.brandHint != null) {
            learnedPatternDao.getPatternsByBrandAndField(context.brandHint, fieldType.name)
        } else {
            learnedPatternDao.getPatternsByField(fieldType.name)
        }
        
        // Convert Room entities to domain LearnedPattern objects
        return@withContext roomPatterns
            .filter { it.weight >= MIN_PATTERN_CONFIDENCE }
            .sortedByDescending { it.weight }
            .take(10) // Limit to top patterns
            .map { roomPattern ->
                LearnedPattern(
                    id = roomPattern.id.toString(),
                    fieldType = fieldType,
                    pattern = roomPattern.regex,
                    confidence = roomPattern.weight,
                    createdAt = Date(roomPattern.createdAt),
                    lastUsed = Date(roomPattern.createdAt) // Use createdAt as proxy for lastUsed
                )
            }
    }

    /**
     * Get pattern statistics for monitoring
     * V2: Now queries Room database
     */
    suspend fun getPatternStats(): Map<FieldType, PatternFieldStats> = withContext(Dispatchers.IO) {
        FieldType.values().associate { fieldType ->
            // V2: Query patterns from Room
            val roomPatterns = learnedPatternDao.getPatternsByField(fieldType.name)
            
            // Convert to domain objects
            val patterns = roomPatterns.map { roomPattern ->
                LearnedPattern(
                    id = roomPattern.id.toString(),
                    fieldType = fieldType,
                    pattern = roomPattern.regex,
                    confidence = roomPattern.weight,
                    createdAt = Date(roomPattern.createdAt),
                    lastUsed = Date(roomPattern.createdAt)
                )
            }
            
            fieldType to PatternFieldStats(
                totalPatterns = patterns.size,
                averageConfidence = patterns.map { it.confidence }.average().takeIf { !it.isNaN() } ?: 0.0,
                topPatterns = patterns.sortedByDescending { it.confidence }.take(5)
            )
        }
    }

    /**
     * Clear all learned patterns (for testing or reset)
     * V2: Now clears Room database
     */
    suspend fun clearAllPatterns() = withContext(Dispatchers.IO) {
        // V2: Clear all patterns from Room
        val deletedCount = learnedPatternDao.deleteAllPatterns()
        
        Log.i(TAG, "All learned patterns cleared from Room: $deletedCount patterns deleted")
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

    /**
     * V2: Record pattern using Room database
     * Replaces SharedPreferences-based storage
     */
    private suspend fun recordPattern(
        fieldType: FieldType,
        pattern: String,
        context: ExtractionContext,
        success: Boolean
    ) = withContext(Dispatchers.IO) {
        
        val brand = context.brandHint
        
        // V2: Query existing patterns from Room
        val existingPatterns = if (brand != null) {
            learnedPatternDao.getPatternsByBrandAndField(brand, fieldType.name)
        } else {
            learnedPatternDao.getPatternsByField(fieldType.name)
        }
        
        // Find if this exact pattern already exists
        val existingPattern = existingPatterns.find { it.regex == pattern }
        
        if (existingPattern != null) {
            // Update existing pattern in Room
            val newSuccessCount = existingPattern.successCount + if (success) 1 else 0
            val newAttemptCount = existingPattern.attemptCount + 1
            val newWeight = newSuccessCount.toFloat() / newAttemptCount
            
            val updatedPattern = existingPattern.copy(
                weight = newWeight,
                successCount = newSuccessCount,
                attemptCount = newAttemptCount
            )
            
            learnedPatternDao.updatePattern(updatedPattern)
            Log.d(TAG, "Updated pattern in Room: $pattern (weight: $newWeight, attempts: $newAttemptCount)")
            
        } else {
            // Create new pattern in Room
            val newPattern = com.example.coupontracker.data.local.LearnedPattern(
                brand = brand,
                fieldType = fieldType.name,
                regex = pattern,
                weight = if (success) 0.7f else 0.3f,
                source = "learned",
                sampleValue = "",  // Empty string since we don't track sample values yet
                createdAt = System.currentTimeMillis(),
                successCount = if (success) 1 else 0,
                attemptCount = 1
            )
            
            learnedPatternDao.insertPattern(newPattern)
            Log.d(TAG, "Created new pattern in Room: $pattern (brand: $brand, field: ${fieldType.name})")
        }
        
        // V2: Enforce limit via Room queries
        val allPatternsForField = learnedPatternDao.getPatternsByField(fieldType.name)
        if (allPatternsForField.size > MAX_PATTERNS_PER_FIELD) {
            // Delete lowest weighted patterns to enforce limit
            val toDelete = allPatternsForField
                .sortedBy { it.weight }
                .take(allPatternsForField.size - MAX_PATTERNS_PER_FIELD)
            
            toDelete.forEach { learnedPatternDao.deletePattern(it.id) }
            Log.d(TAG, "Deleted ${toDelete.size} low-weight patterns to enforce limit of $MAX_PATTERNS_PER_FIELD")
        }
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

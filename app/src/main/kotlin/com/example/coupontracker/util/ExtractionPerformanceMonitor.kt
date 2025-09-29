package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monitors extraction performance and learning progress
 * Provides statistics for the universal extraction system
 */
@Singleton
class ExtractionPerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "extraction_performance_prefs", Context.MODE_PRIVATE
    )
    
    private val sessionStats = ConcurrentHashMap<String, SessionStats>()
    
    companion object {
        private const val TAG = "ExtractionPerformanceMonitor"
        private const val KEY_DAILY_STATS = "daily_stats"
        private const val KEY_OVERALL_STATS = "overall_stats"
        private const val KEY_LEARNING_STATS = "learning_stats"
        private const val MAX_DAILY_RECORDS = 30 // Keep 30 days
    }
    
    /**
     * Record extraction attempt
     */
    suspend fun recordExtractionAttempt(
        method: ExtractionMethod,
        success: Boolean,
        confidence: Float,
        processingTimeMs: Long,
        fieldsExtracted: Set<String>
    ) = withContext(Dispatchers.IO) {
        try {
            val today = getCurrentDateKey()
            val sessionKey = "${method.name}_${today}"
            
            // Update session stats
            val stats = sessionStats.getOrPut(sessionKey) {
                SessionStats(method = method, date = today)
            }
            
            stats.totalAttempts++
            if (success) {
                stats.successfulAttempts++
                stats.totalConfidence += confidence
                stats.fieldsExtractedCount += fieldsExtracted.size
                
                // Track field-specific success rates
                fieldsExtracted.forEach { field ->
                    stats.fieldSuccessCount[field] = stats.fieldSuccessCount.getOrDefault(field, 0) + 1
                }
            }
            stats.totalProcessingTime += processingTimeMs
            
            // Persist stats periodically
            if (stats.totalAttempts % 10 == 0) {
                persistSessionStats()
            }
            
            Log.d(TAG, "Recorded extraction: method=$method, success=$success, confidence=$confidence, time=${processingTimeMs}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording extraction attempt", e)
        }
    }
    
    /**
     * Record user feedback
     */
    suspend fun recordUserFeedback(
        method: ExtractionMethod,
        feedbackType: FeedbackType,
        correctedFields: Set<String>
    ) = withContext(Dispatchers.IO) {
        try {
            val today = getCurrentDateKey()
            val sessionKey = "${method.name}_${today}"
            
            val stats = sessionStats.getOrPut(sessionKey) {
                SessionStats(method = method, date = today)
            }
            
            when (feedbackType) {
                FeedbackType.CONFIRMED_CORRECT -> {
                    stats.positiveFeeback++
                }
                FeedbackType.SUBMITTED_CORRECTIONS -> {
                    stats.negativeFeeback++
                    stats.correctedFieldsCount += correctedFields.size
                    
                    // Track which fields needed correction
                    correctedFields.forEach { field ->
                        stats.fieldCorrectionCount[field] = stats.fieldCorrectionCount.getOrDefault(field, 0) + 1
                    }
                }
            }
            
            // Update learning stats
            updateLearningStats(feedbackType, correctedFields)
            
            Log.d(TAG, "Recorded feedback: method=$method, type=$feedbackType, corrected=${correctedFields.size} fields")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording user feedback", e)
        }
    }
    
    /**
     * Get performance statistics for a specific method
     */
    suspend fun getMethodPerformance(method: ExtractionMethod): MethodPerformance = withContext(Dispatchers.IO) {
        try {
            // Ensure all session stats are persisted before reading
            persistSessionStats()
            
            val dailyStats = getDailyStats()
            val methodStats = dailyStats.values.filter { it.method == method }
            
            if (methodStats.isEmpty()) {
                return@withContext MethodPerformance(
                    method = method,
                    totalAttempts = 0,
                    successRate = 0f,
                    averageConfidence = 0f,
                    averageProcessingTime = 0L,
                    fieldAccuracy = emptyMap(),
                    learningTrend = emptyList()
                )
            }
            
            val totalAttempts = methodStats.sumOf { it.totalAttempts }
            val successfulAttempts = methodStats.sumOf { it.successfulAttempts }
            val totalConfidence = methodStats.sumOf { it.totalConfidence.toDouble() }.toFloat()
            val totalTime = methodStats.sumOf { it.totalProcessingTime }
            
            val successRate = if (totalAttempts > 0) successfulAttempts.toFloat() / totalAttempts else 0f
            val averageConfidence = if (successfulAttempts > 0) totalConfidence / successfulAttempts else 0f
            val averageTime = if (totalAttempts > 0) totalTime / totalAttempts else 0L
            
            // Calculate field accuracy
            val fieldAccuracy = calculateFieldAccuracy(methodStats)
            
            // Get learning trend (last 7 days)
            val learningTrend = calculateLearningTrend(method, 7)
            
            MethodPerformance(
                method = method,
                totalAttempts = totalAttempts,
                successRate = successRate,
                averageConfidence = averageConfidence,
                averageProcessingTime = averageTime,
                fieldAccuracy = fieldAccuracy,
                learningTrend = learningTrend
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting method performance", e)
            MethodPerformance(method, 0, 0f, 0f, 0L, emptyMap(), emptyList())
        }
    }
    
    /**
     * Get overall system performance
     */
    suspend fun getOverallPerformance(): SystemPerformance = withContext(Dispatchers.IO) {
        try {
            // Ensure all session stats are persisted before reading
            persistSessionStats()
            
            val dailyStats = getDailyStats()
            val allStats = dailyStats.values
            
            if (allStats.isEmpty()) {
                return@withContext SystemPerformance(
                    totalExtractions = 0,
                    overallSuccessRate = 0f,
                    methodBreakdown = emptyMap(),
                    topPerformingMethod = null,
                    learningProgress = LearningProgress(0, 0, 0f),
                    recentTrends = emptyList()
                )
            }
            
            val totalExtractions = allStats.sumOf { it.totalAttempts }
            val totalSuccessful = allStats.sumOf { it.successfulAttempts }
            val overallSuccessRate = if (totalExtractions > 0) totalSuccessful.toFloat() / totalExtractions else 0f
            
            // Method breakdown
            val methodBreakdown = mutableMapOf<ExtractionMethod, MethodPerformance>()
            for (method in ExtractionMethod.values()) {
                methodBreakdown[method] = getMethodPerformance(method)
            }
            
            // Top performing method
            val topPerformingMethod = methodBreakdown.maxByOrNull { it.value.successRate }?.key
            
            // Learning progress
            val learningStats = getLearningStats()
            val learningProgress = LearningProgress(
                totalFeedback = learningStats.totalFeedback,
                patternsLearned = learningStats.patternsLearned,
                improvementRate = calculateImprovementRate()
            )
            
            // Recent trends (last 7 days)
            val recentTrends = calculateSystemTrends(7)
            
            SystemPerformance(
                totalExtractions = totalExtractions,
                overallSuccessRate = overallSuccessRate,
                methodBreakdown = methodBreakdown,
                topPerformingMethod = topPerformingMethod,
                learningProgress = learningProgress,
                recentTrends = recentTrends
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting overall performance", e)
            SystemPerformance(0, 0f, emptyMap(), null, LearningProgress(0, 0, 0f), emptyList())
        }
    }
    
    /**
     * Clear old statistics (keep only recent data)
     */
    suspend fun cleanupOldStats() = withContext(Dispatchers.IO) {
        try {
            val dailyStats = getDailyStats().toMutableMap()
            val cutoffDate = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -MAX_DAILY_RECORDS)
            }.time
            
            val cutoffKey = formatDateKey(cutoffDate)
            dailyStats.keys.removeAll { it < cutoffKey }
            
            saveDailyStats(dailyStats)
            Log.d(TAG, "Cleaned up old statistics, kept ${dailyStats.size} days")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old stats", e)
        }
    }
    
    // Private helper methods
    
    suspend fun persistSessionStats() = withContext(Dispatchers.IO) {
        try {
            val dailyStats = getDailyStats().toMutableMap()
            
            // Merge session stats into daily stats
            sessionStats.values.forEach { sessionStat ->
                val key = "${sessionStat.method.name}_${sessionStat.date}"
                dailyStats[key] = sessionStat
            }
            
            saveDailyStats(dailyStats)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting session stats", e)
        }
    }
    
    private fun updateLearningStats(feedbackType: FeedbackType, correctedFields: Set<String>) {
        try {
            val learningStats = getLearningStats().copy()
            
            learningStats.totalFeedback++
            
            when (feedbackType) {
                FeedbackType.CONFIRMED_CORRECT -> learningStats.positiveFeedback++
                FeedbackType.SUBMITTED_CORRECTIONS -> {
                    learningStats.negativeFeedback++
                    // Each correction potentially creates a new learned pattern
                    learningStats.patternsLearned += correctedFields.size
                }
            }
            
            saveLearningStats(learningStats)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating learning stats", e)
        }
    }
    
    private fun calculateFieldAccuracy(stats: List<SessionStats>): Map<String, Float> {
        val fieldAccuracy = mutableMapOf<String, Float>()
        
        val allFields = stats.flatMap { it.fieldSuccessCount.keys + it.fieldCorrectionCount.keys }.toSet()
        
        for (field in allFields) {
            val successCount = stats.sumOf { it.fieldSuccessCount.getOrDefault(field, 0) }
            val correctionCount = stats.sumOf { it.fieldCorrectionCount.getOrDefault(field, 0) }
            val totalAttempts = successCount + correctionCount
            
            fieldAccuracy[field] = if (totalAttempts > 0) successCount.toFloat() / totalAttempts else 0f
        }
        
        return fieldAccuracy
    }
    
    private fun calculateLearningTrend(method: ExtractionMethod, days: Int): List<TrendPoint> {
        val trends = mutableListOf<TrendPoint>()
        val calendar = Calendar.getInstance()
        
        repeat(days) { dayOffset ->
            calendar.add(Calendar.DAY_OF_YEAR, -dayOffset)
            val dateKey = formatDateKey(calendar.time)
            val sessionKey = "${method.name}_$dateKey"
            
            val dailyStats = getDailyStats()
            val dayStats = dailyStats[sessionKey]
            
            val successRate = if (dayStats != null && dayStats.totalAttempts > 0) {
                dayStats.successfulAttempts.toFloat() / dayStats.totalAttempts
            } else {
                0f
            }
            
            trends.add(TrendPoint(dateKey, successRate))
            calendar.add(Calendar.DAY_OF_YEAR, dayOffset) // Reset for next iteration
        }
        
        return trends.reversed() // Return in chronological order
    }
    
    private fun calculateImprovementRate(): Float {
        try {
            val trends = calculateSystemTrends(14) // Look at 2 weeks
            if (trends.size < 7) return 0f // Need at least a week of data
            
            val firstWeek = trends.take(7).map { it.value }.average()
            val secondWeek = trends.takeLast(7).map { it.value }.average()
            
            return if (firstWeek > 0) ((secondWeek - firstWeek) / firstWeek).toFloat() else 0f
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating improvement rate", e)
            return 0f
        }
    }
    
    private fun calculateSystemTrends(days: Int): List<TrendPoint> {
        val trends = mutableListOf<TrendPoint>()
        val calendar = Calendar.getInstance()
        val dailyStats = getDailyStats()
        
        repeat(days) { dayOffset ->
            calendar.add(Calendar.DAY_OF_YEAR, -dayOffset)
            val dateKey = formatDateKey(calendar.time)
            
            // Get all stats for this day across all methods
            val dayStats = dailyStats.values.filter { it.date == dateKey }
            
            val totalAttempts = dayStats.sumOf { it.totalAttempts }
            val totalSuccessful = dayStats.sumOf { it.successfulAttempts }
            
            val successRate = if (totalAttempts > 0) totalSuccessful.toFloat() / totalAttempts else 0f
            
            trends.add(TrendPoint(dateKey, successRate))
            calendar.add(Calendar.DAY_OF_YEAR, dayOffset) // Reset for next iteration
        }
        
        return trends.reversed() // Return in chronological order
    }
    
    private fun getCurrentDateKey(): String = formatDateKey(Date())
    
    private fun formatDateKey(date: Date): String {
        val calendar = Calendar.getInstance().apply { time = date }
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}-${calendar.get(Calendar.DAY_OF_MONTH)}"
    }
    
    private fun getDailyStats(): Map<String, SessionStats> {
        return try {
            val json = prefs.getString(KEY_DAILY_STATS, "{}")
            val type = object : TypeToken<Map<String, SessionStats>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading daily stats", e)
            emptyMap()
        }
    }
    
    private fun saveDailyStats(stats: Map<String, SessionStats>) {
        try {
            val json = gson.toJson(stats)
            prefs.edit().putString(KEY_DAILY_STATS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving daily stats", e)
        }
    }
    
    private fun getLearningStats(): LearningStatsData {
        return try {
            val json = prefs.getString(KEY_LEARNING_STATS, "{}")
            gson.fromJson(json, LearningStatsData::class.java) ?: LearningStatsData()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading learning stats", e)
            LearningStatsData()
        }
    }
    
    private fun saveLearningStats(stats: LearningStatsData) {
        try {
            val json = gson.toJson(stats)
            prefs.edit().putString(KEY_LEARNING_STATS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving learning stats", e)
        }
    }
}

// Data classes for statistics

enum class ExtractionMethod {
    TWO_STAGE_DETECTOR,
    UNIVERSAL_EXTRACTION,
    LLM_OCR_FUSION,
    TRADITIONAL_OCR,
    MANUAL_ENTRY
}

enum class FeedbackType {
    CONFIRMED_CORRECT,
    SUBMITTED_CORRECTIONS
}

data class SessionStats(
    val method: ExtractionMethod,
    val date: String,
    var totalAttempts: Int = 0,
    var successfulAttempts: Int = 0,
    var totalConfidence: Float = 0f,
    var totalProcessingTime: Long = 0L,
    var fieldsExtractedCount: Int = 0,
    var positiveFeeback: Int = 0,
    var negativeFeeback: Int = 0,
    var correctedFieldsCount: Int = 0,
    val fieldSuccessCount: MutableMap<String, Int> = mutableMapOf(),
    val fieldCorrectionCount: MutableMap<String, Int> = mutableMapOf()
)

data class MethodPerformance(
    val method: ExtractionMethod,
    val totalAttempts: Int,
    val successRate: Float,
    val averageConfidence: Float,
    val averageProcessingTime: Long,
    val fieldAccuracy: Map<String, Float>,
    val learningTrend: List<TrendPoint>
)

data class SystemPerformance(
    val totalExtractions: Int,
    val overallSuccessRate: Float,
    val methodBreakdown: Map<ExtractionMethod, MethodPerformance>,
    val topPerformingMethod: ExtractionMethod?,
    val learningProgress: LearningProgress,
    val recentTrends: List<TrendPoint>
)

data class LearningProgress(
    val totalFeedback: Int,
    val patternsLearned: Int,
    val improvementRate: Float // Percentage improvement over time
)

data class TrendPoint(
    val date: String,
    val value: Float
)

data class LearningStatsData(
    var totalFeedback: Int = 0,
    var positiveFeedback: Int = 0,
    var negativeFeedback: Int = 0,
    var patternsLearned: Int = 0
)

package com.example.coupontracker.data.local

import androidx.room.*

/**
 * Entity for storing extraction feedback and telemetry
 * V2 Architecture: Enables learning from user corrections and performance analysis
 * 
 * Stores:
 * - Successful extractions (for replay/retrain)
 * - User corrections (for learning)
 * - Extraction signals (confidence, timing, device info)
 * - Run path (which strategies were tried)
 */
@Entity(
    tableName = "extraction_feedback_v1",
    indices = [
        Index(value = ["timestamp"], orders = [Index.Order.DESC]),
        Index(value = ["extractionStrategy"]),
        Index(value = ["feedbackType"]),
        Index(value = ["couponId"])
    ]
)
data class ExtractionFeedback(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * Reference to the coupon that was created
     * Nullable for failed extractions that didn't create a coupon
     */
    val couponId: Long? = null,
    
    /**
     * Which extraction strategy was used
     * Values: "LLM_FIRST", "OCR_FIRST", "HYBRID", "LEGACY"
     */
    val extractionStrategy: String,
    
    /**
     * Type of feedback
     * Values: "confirmed_correct", "user_corrected", "auto_success", "failed"
     */
    val feedbackType: String,
    
    /**
     * Original extracted values (JSON)
     * Example: {"code": "SAVE50", "expiry": "2025-12-31", "cashback": "500", "store": "Sample Store"}
     */
    val originalValues: String,
    
    /**
     * Corrected values if user made changes (JSON)
     * Null if user confirmed original was correct
     * Example: {"code": "SAVE500", "expiry": "2025-12-30"}
     */
    val correctedValues: String? = null,
    
    /**
     * Extraction signals (JSON serialized)
     * Includes: field confidences, processing time, memory usage, etc.
     */
    val signalsJson: String,
    
    /**
     * Run path (JSON serialized)
     * Shows which extraction stages were tried and which succeeded
     */
    val runPathJson: String,
    
    /**
     * Device information
     * Example: "Pixel 6, Android 13, 8GB RAM, mid-tier"
     */
    val deviceInfo: String,
    
    /**
     * Unix timestamp when feedback was recorded
     */
    val timestamp: Long = System.currentTimeMillis(),
    
    /**
     * Whether user explicitly consented to data collection
     * If false, data should not be uploaded/shared (local analysis only)
     */
    val consentGiven: Boolean = false
) {
    /**
     * Check if this was a successful extraction
     */
    fun isSuccess(): Boolean {
        return feedbackType in listOf("confirmed_correct", "auto_success")
    }
    
    /**
     * Check if user made corrections
     */
    fun hadCorrections(): Boolean {
        return feedbackType == "user_corrected" && !correctedValues.isNullOrBlank()
    }
    
    /**
     * Get age in days
     */
    fun getAgeDays(): Long {
        val now = System.currentTimeMillis()
        return (now - timestamp) / (1000 * 60 * 60 * 24)
    }
}

/**
 * DAO for extraction feedback
 * Provides queries for feedback analysis and pattern learning
 */
@Dao
interface ExtractionFeedbackDao {
    /**
     * Insert new feedback
     */
    @Insert
    suspend fun insertFeedback(feedback: ExtractionFeedback): Long
    
    /**
     * Insert multiple feedback entries
     */
    @Insert
    suspend fun insertFeedbackBatch(feedbacks: List<ExtractionFeedback>): List<Long>
    
    /**
     * Get recent user corrections (for learning)
     * Returns only entries where user made corrections and gave consent
     */
    @Query("""
        SELECT * FROM extraction_feedback_v1 
        WHERE feedbackType = 'user_corrected' 
        AND consentGiven = 1 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getRecentCorrections(limit: Int = 100): List<ExtractionFeedback>
    
    /**
     * Get all feedback for a specific coupon
     */
    @Query("""
        SELECT * FROM extraction_feedback_v1 
        WHERE couponId = :couponId 
        ORDER BY timestamp DESC
    """)
    suspend fun getFeedbackForCoupon(couponId: Long): List<ExtractionFeedback>
    
    /**
     * Get feedback by extraction strategy
     * Useful for comparing strategy performance
     */
    @Query("""
        SELECT * FROM extraction_feedback_v1 
        WHERE extractionStrategy = :strategy 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getFeedbackByStrategy(strategy: String, limit: Int = 100): List<ExtractionFeedback>
    
    /**
     * Get feedback by type
     */
    @Query("""
        SELECT * FROM extraction_feedback_v1 
        WHERE feedbackType = :feedbackType 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    suspend fun getFeedbackByType(feedbackType: String, limit: Int = 100): List<ExtractionFeedback>
    
    /**
     * Get feedback within date range
     */
    @Query("""
        SELECT * FROM extraction_feedback_v1 
        WHERE timestamp BETWEEN :startTime AND :endTime 
        ORDER BY timestamp DESC
    """)
    suspend fun getFeedbackInRange(startTime: Long, endTime: Long): List<ExtractionFeedback>
    
    /**
     * Count extractions by strategy (last N days)
     */
    @Query("""
        SELECT COUNT(*) FROM extraction_feedback_v1 
        WHERE extractionStrategy = :strategy 
        AND timestamp > :sinceTimestamp
    """)
    suspend fun countByStrategy(strategy: String, sinceTimestamp: Long): Int
    
    /**
     * Count successful extractions
     */
    @Query("""
        SELECT COUNT(*) FROM extraction_feedback_v1 
        WHERE feedbackType IN ('confirmed_correct', 'auto_success') 
        AND timestamp > :sinceTimestamp
    """)
    suspend fun countSuccesses(sinceTimestamp: Long): Int
    
    /**
     * Count user corrections
     */
    @Query("""
        SELECT COUNT(*) FROM extraction_feedback_v1 
        WHERE feedbackType = 'user_corrected' 
        AND timestamp > :sinceTimestamp
    """)
    suspend fun countCorrections(sinceTimestamp: Long): Int
    
    /**
     * Get success rate for a strategy
     * Returns percentage (0-100)
     */
    @Query("""
        SELECT 
            CAST(SUM(CASE WHEN feedbackType IN ('confirmed_correct', 'auto_success') THEN 1 ELSE 0 END) AS REAL) * 100.0 / 
            COUNT(*) as successRate
        FROM extraction_feedback_v1 
        WHERE extractionStrategy = :strategy 
        AND timestamp > :sinceTimestamp
    """)
    suspend fun getSuccessRate(strategy: String, sinceTimestamp: Long): Float?
    
    /**
     * Get extraction statistics
     */
    @Query("""
        SELECT 
            COUNT(*) as total,
            SUM(CASE WHEN feedbackType IN ('confirmed_correct', 'auto_success') THEN 1 ELSE 0 END) as successes,
            SUM(CASE WHEN feedbackType = 'user_corrected' THEN 1 ELSE 0 END) as corrections,
            SUM(CASE WHEN feedbackType = 'failed' THEN 1 ELSE 0 END) as failures,
            COUNT(DISTINCT extractionStrategy) as strategiesUsed
        FROM extraction_feedback_v1
        WHERE timestamp > :sinceTimestamp
    """)
    suspend fun getExtractionStats(sinceTimestamp: Long): ExtractionStats
    
    /**
     * Get strategy comparison (for dashboard)
     * Groups by strategy and shows success rates
     */
    @Query("""
        SELECT 
            extractionStrategy,
            COUNT(*) as attempts,
            SUM(CASE WHEN feedbackType IN ('confirmed_correct', 'auto_success') THEN 1 ELSE 0 END) as successes,
            CAST(SUM(CASE WHEN feedbackType IN ('confirmed_correct', 'auto_success') THEN 1 ELSE 0 END) AS REAL) * 100.0 / 
            COUNT(*) as successRate
        FROM extraction_feedback_v1
        WHERE timestamp > :sinceTimestamp
        GROUP BY extractionStrategy
        ORDER BY successRate DESC
    """)
    suspend fun getStrategyComparison(sinceTimestamp: Long): List<StrategyPerformance>
    
    /**
     * Delete old feedback (older than cutoff)
     * Keeps storage manageable and respects privacy
     */
    @Query("DELETE FROM extraction_feedback_v1 WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteOldFeedback(cutoffTimestamp: Long): Int
    
    /**
     * Delete feedback for a specific coupon
     */
    @Query("DELETE FROM extraction_feedback_v1 WHERE couponId = :couponId")
    suspend fun deleteFeedbackForCoupon(couponId: Long): Int
    
    /**
     * Get total feedback count
     */
    @Query("SELECT COUNT(*) FROM extraction_feedback_v1")
    suspend fun getFeedbackCount(): Int
    
    /**
     * Clear all feedback (use with caution!)
     */
    @Query("DELETE FROM extraction_feedback_v1")
    suspend fun deleteAllFeedback(): Int
}

/**
 * Overall extraction statistics
 */
data class ExtractionStats(
    val total: Int,
    val successes: Int,
    val corrections: Int,
    val failures: Int,
    val strategiesUsed: Int
) {
    fun getSuccessRate(): Float {
        return if (total > 0) (successes.toFloat() / total) * 100f else 0f
    }
    
    fun getCorrectionRate(): Float {
        return if (total > 0) (corrections.toFloat() / total) * 100f else 0f
    }
}

/**
 * Per-strategy performance metrics
 */
data class StrategyPerformance(
    val extractionStrategy: String,
    val attempts: Int,
    val successes: Int,
    val successRate: Float
)

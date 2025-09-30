package com.example.coupontracker.data.local

import androidx.room.*

/**
 * Entity for storing learned extraction patterns
 * V2 Architecture: Replaces SharedPreferences for pattern storage
 * 
 * Patterns are learned from:
 * - Successful extractions (auto-learning)
 * - User corrections (feedback-based learning)
 * - OCR-LLM fusion agreements
 */
@Entity(
    tableName = "learned_patterns_v1",
    indices = [
        Index(value = ["fieldType"]),
        Index(value = ["brand"]),
        Index(value = ["weight"], orders = [Index.Order.DESC])
    ]
)
data class LearnedPattern(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    /**
     * Brand/store name for brand-specific patterns
     * Null for universal patterns that work across all brands
     */
    val brand: String? = null,
    
    /**
     * Field type this pattern applies to
     * Values: "code", "expiry", "cashback", "store"
     */
    @ColumnInfo(name = "fieldType")
    val fieldType: String,
    
    /**
     * Regular expression pattern that successfully extracted this field
     * Example: "SAVE[0-9]{3,4}" for codes like SAVE500, SAVE1000
     */
    val regex: String,
    
    /**
     * Pattern success rate (0.0 to 1.0)
     * Calculated as: successCount / attemptCount
     * Higher weight = more reliable pattern
     */
    val weight: Float,
    
    /**
     * Where this pattern came from
     * Values: "user_correction", "llm_success", "ocr_fusion", "auto_success"
     */
    val source: String,
    
    /**
     * Example value this pattern matched
     * Used for debugging and pattern analysis
     * Example: "SAVE500", "31 Dec 2025", "₹1,500 OFF"
     */
    val sampleValue: String,
    
    /**
     * Unix timestamp when pattern was created
     */
    val createdAt: Long = System.currentTimeMillis(),
    
    /**
     * Number of times this pattern successfully extracted a field
     */
    val successCount: Int = 1,
    
    /**
     * Number of times this pattern was tried
     * Used to calculate weight (success rate)
     */
    val attemptCount: Int = 1
) {
    /**
     * Calculate current success rate
     */
    fun getSuccessRate(): Float {
        return if (attemptCount > 0) {
            successCount.toFloat() / attemptCount
        } else {
            0f
        }
    }
    
    /**
     * Check if pattern is reliable (success rate >= 70%)
     */
    fun isReliable(): Boolean = weight >= 0.7f
    
    /**
     * Get age in days
     */
    fun getAgeDays(): Long {
        val now = System.currentTimeMillis()
        return (now - createdAt) / (1000 * 60 * 60 * 24)
    }
}

/**
 * DAO for learned patterns
 * Provides efficient queries for pattern retrieval and management
 */
@Dao
interface LearnedPatternDao {
    /**
     * Get top patterns for a specific field type and optional brand
     * Returns patterns sorted by weight (most reliable first)
     * Includes both brand-specific and universal patterns
     */
    @Query("""
        SELECT * FROM learned_patterns_v1 
        WHERE fieldType = :fieldType 
        AND (brand = :brand OR brand IS NULL) 
        ORDER BY weight DESC, createdAt DESC 
        LIMIT :limit
    """)
    suspend fun getTopPatterns(
        fieldType: String, 
        brand: String?, 
        limit: Int = 10
    ): List<LearnedPattern>
    
    /**
     * Get all patterns for a specific field type (all brands)
     */
    @Query("""
        SELECT * FROM learned_patterns_v1 
        WHERE fieldType = :fieldType 
        ORDER BY weight DESC, createdAt DESC
    """)
    suspend fun getPatternsForField(fieldType: String): List<LearnedPattern>
    
    /**
     * Get all patterns for a specific brand
     */
    @Query("""
        SELECT * FROM learned_patterns_v1 
        WHERE brand = :brand 
        ORDER BY weight DESC, createdAt DESC
    """)
    suspend fun getPatternsForBrand(brand: String): List<LearnedPattern>
    
    /**
     * Insert a new pattern or replace if exists
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPattern(pattern: LearnedPattern): Long
    
    /**
     * Insert multiple patterns
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatterns(patterns: List<LearnedPattern>): List<Long>
    
    /**
     * Update an existing pattern
     */
    @Update
    suspend fun updatePattern(pattern: LearnedPattern): Int
    
    /**
     * Increment success count when pattern works
     * Automatically recalculates weight
     */
    @Query("""
        UPDATE learned_patterns_v1 
        SET successCount = successCount + 1, 
            attemptCount = attemptCount + 1, 
            weight = CAST(successCount + 1 AS REAL) / (attemptCount + 1)
        WHERE id = :patternId
    """)
    suspend fun incrementSuccess(patternId: Long): Int
    
    /**
     * Increment attempt count when pattern is tried but fails
     * Automatically recalculates weight (will decrease)
     */
    @Query("""
        UPDATE learned_patterns_v1 
        SET attemptCount = attemptCount + 1, 
            weight = CAST(successCount AS REAL) / (attemptCount + 1)
        WHERE id = :patternId
    """)
    suspend fun incrementAttempt(patternId: Long): Int
    
    /**
     * Delete patterns older than cutoff timestamp
     * Useful for cleanup of stale patterns
     */
    @Query("DELETE FROM learned_patterns_v1 WHERE createdAt < :cutoffTimestamp")
    suspend fun deleteOldPatterns(cutoffTimestamp: Long): Int
    
    /**
     * Delete patterns with low success rate
     * Removes unreliable patterns (weight < threshold)
     */
    @Query("DELETE FROM learned_patterns_v1 WHERE weight < :minWeight")
    suspend fun deleteUnreliablePatterns(minWeight: Float = 0.3f): Int
    
    /**
     * Delete a specific pattern by ID
     */
    @Query("DELETE FROM learned_patterns_v1 WHERE id = :patternId")
    suspend fun deletePattern(patternId: Long): Int
    
    /**
     * Get total pattern count
     */
    @Query("SELECT COUNT(*) FROM learned_patterns_v1")
    suspend fun getPatternCount(): Int
    
    /**
     * Get pattern count by field type
     */
    @Query("SELECT COUNT(*) FROM learned_patterns_v1 WHERE fieldType = :fieldType")
    suspend fun getPatternCountForField(fieldType: String): Int
    
    /**
     * Get statistics for patterns
     * Returns: total count, avg weight, brands covered
     */
    @Query("""
        SELECT 
            COUNT(*) as total,
            AVG(weight) as avgWeight,
            COUNT(DISTINCT brand) as uniqueBrands
        FROM learned_patterns_v1
    """)
    suspend fun getPatternStats(): PatternStats
    
    /**
     * Clear all patterns (use with caution!)
     */
    @Query("DELETE FROM learned_patterns_v1")
    suspend fun deleteAllPatterns(): Int
}

/**
 * Statistics for pattern storage
 */
data class PatternStats(
    val total: Int,
    val avgWeight: Float,
    val uniqueBrands: Int
)

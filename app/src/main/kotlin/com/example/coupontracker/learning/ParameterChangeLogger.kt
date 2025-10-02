package com.example.coupontracker.learning

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Systematic logging of parameter changes for extraction algorithms.
 * 
 * Industry standard approach: Track WHY parameters changed, not just WHAT changed.
 * Enables:
 * - Debugging (what caused this behavior?)
 * - A/B testing (did the change improve accuracy?)
 * - Rollback (revert problematic changes)
 * - Documentation (audit trail of optimization)
 */
@Singleton
class ParameterChangeLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "ParameterChangeLogger"
        private const val LOG_FILE_NAME = "parameter_changes.jsonl"
    }
    
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    
    private val logFile: File by lazy {
        File(context.filesDir, LOG_FILE_NAME)
    }
    
    /**
     * Log a parameter change with full context
     */
    fun logChange(change: ParameterChange) {
        try {
            // Append to JSONL file (one JSON object per line)
            logFile.appendText(gson.toJson(change) + "\n")
            
            // Also log to logcat for immediate visibility
            Log.i(TAG, """
                ┌─ PARAMETER CHANGE ─────────────────────────────────────────
                │ Component: ${change.component}
                │ Parameter: ${change.parameter}
                │ Change: ${change.oldValue} → ${change.newValue}
                │ Reason: ${change.reason}
                │ Test Coupon: ${change.testCoupon ?: "N/A"}
                │ Impact: ${change.estimatedImpact ?: "Unknown"}
                │ Timestamp: ${formatTimestamp(change.timestamp)}
                └────────────────────────────────────────────────────────────
            """.trimIndent())
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log parameter change", e)
        }
    }
    
    /**
     * Log multiple related changes as a batch (e.g., one bug fix touching multiple params)
     */
    fun logBatch(
        changes: List<ParameterChange>,
        batchReason: String
    ) {
        Log.i(TAG, "┌─ PARAMETER BATCH: $batchReason ─────────────────")
        changes.forEach { change ->
            logChange(change)
        }
        Log.i(TAG, "└─ END BATCH (${changes.size} changes) ─────────────────")
    }
    
    /**
     * Get all parameter changes (for analytics/debugging)
     */
    fun getAllChanges(): List<ParameterChange> {
        return try {
            if (!logFile.exists()) return emptyList()
            
            logFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    try {
                        gson.fromJson(line, ParameterChange::class.java)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse change log line: $line", e)
                        null
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read parameter changes", e)
            emptyList()
        }
    }
    
    /**
     * Get changes for a specific component
     */
    fun getChangesForComponent(component: String): List<ParameterChange> {
        return getAllChanges().filter { it.component == component }
    }
    
    /**
     * Get changes related to a specific test coupon
     */
    fun getChangesForTest(testCoupon: String): List<ParameterChange> {
        return getAllChanges().filter { it.testCoupon == testCoupon }
    }
    
    /**
     * Get recent changes (last N days)
     */
    fun getRecentChanges(days: Int): List<ParameterChange> {
        val cutoffTime = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000L)
        return getAllChanges().filter { it.timestamp >= cutoffTime }
    }
    
    /**
     * Generate summary report
     */
    fun generateReport(): ParameterChangeSummary {
        val allChanges = getAllChanges()
        
        return ParameterChangeSummary(
            totalChanges = allChanges.size,
            componentBreakdown = allChanges.groupBy { it.component }
                .mapValues { it.value.size },
            recentChanges = getRecentChanges(7),
            mostChangedParameters = allChanges.groupBy { it.parameter }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(10)
        )
    }
    
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(timestamp))
    }
}

/**
 * Represents a single parameter change
 */
data class ParameterChange(
    /** When the change was made */
    val timestamp: Long = System.currentTimeMillis(),
    
    /** Which component was changed (e.g., "StructuredFieldExtractor") */
    val component: String,
    
    /** Which parameter was changed (e.g., "couponCodeRegex") */
    val parameter: String,
    
    /** Old value (for rollback) */
    val oldValue: String,
    
    /** New value */
    val newValue: String,
    
    /** WHY the change was made (critical for understanding) */
    val reason: String,
    
    /** Test coupon that motivated the change (e.g., "BigBasket_2025-10-02") */
    val testCoupon: String? = null,
    
    /** Measured accuracy before change (if available) */
    val beforeAccuracy: Float? = null,
    
    /** Measured accuracy after change (if available) */
    val afterAccuracy: Float? = null,
    
    /** Estimated impact: "low", "medium", "high", or percentage */
    val estimatedImpact: String? = null,
    
    /** Additional context (JSON string for flexibility) */
    val metadata: String? = null
)

/**
 * Summary of all parameter changes
 */
data class ParameterChangeSummary(
    val totalChanges: Int,
    val componentBreakdown: Map<String, Int>,
    val recentChanges: List<ParameterChange>,
    val mostChangedParameters: List<Pair<String, Int>>
)


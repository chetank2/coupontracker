package com.example.coupontracker.llm

import android.content.Context
import android.util.Log
import com.example.coupontracker.util.AnalyticsTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Telemetry service for monitoring MiniCPM LLM performance and behavior
 * Tracks inference timing, memory usage, fallback counts, and error patterns
 */
class LlmTelemetryService(
    private val context: Context,
    private val analyticsTracker: AnalyticsTracker? = null
) {
    
    companion object {
        private const val TAG = "LlmTelemetryService"
        private const val TELEMETRY_FILE = "llm_telemetry.json"
        private const val MAX_LOG_ENTRIES = 1000
        
        @Volatile
        private var INSTANCE: LlmTelemetryService? = null
        
        fun getInstance(context: Context, analyticsTracker: AnalyticsTracker? = null): LlmTelemetryService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmTelemetryService(context.applicationContext, analyticsTracker).also { INSTANCE = it }
            }
        }
    }
    
    // Performance counters
    private val totalInferences = AtomicLong(0)
    private val successfulInferences = AtomicLong(0)
    private val failedInferences = AtomicLong(0)
    private val timeoutInferences = AtomicLong(0)
    private val fallbackToMlKit = AtomicLong(0)
    private val fallbackToModelBased = AtomicLong(0)
    
    // Timing statistics
    private val totalInferenceTime = AtomicLong(0) // milliseconds
    private val modelLoadCount = AtomicInteger(0)
    private val modelUnloadCount = AtomicInteger(0)
    
    // Memory tracking
    private var peakMemoryUsageMB = AtomicLong(0)
    private var averageMemoryUsageMB = AtomicLong(0)
    
    // Session tracking
    private val sessionStartTime = System.currentTimeMillis()
    private var lastInferenceTime = 0L
    
    /**
     * Record inference attempt with timing and outcome
     */
    fun recordInference(
        durationMs: Long,
        success: Boolean,
        errorType: String? = null,
        fallbackUsed: String? = null,
        extractedFieldCount: Int = 0,
        memoryUsageMB: Long = 0
    ) {
        totalInferences.incrementAndGet()
        totalInferenceTime.addAndGet(durationMs)
        lastInferenceTime = System.currentTimeMillis()
        
        if (success) {
            successfulInferences.incrementAndGet()
        } else {
            failedInferences.incrementAndGet()
        }
        
        // Track fallback usage
        when (fallbackUsed) {
            "ML_KIT" -> fallbackToMlKit.incrementAndGet()
            "MODEL_BASED" -> fallbackToModelBased.incrementAndGet()
        }
        
        // Update memory statistics
        if (memoryUsageMB > peakMemoryUsageMB.get()) {
            peakMemoryUsageMB.set(memoryUsageMB)
        }
        
        // Send analytics event for performance monitoring
        analyticsTracker?.let { tracker ->
            CoroutineScope(Dispatchers.IO).launch {
                val eventData = mapOf(
                    "duration_ms" to durationMs,
                    "success" to success,
                    "error_type" to (errorType ?: "none"),
                    "fallback_used" to (fallbackUsed ?: "none"),
                    "extracted_fields" to extractedFieldCount,
                    "memory_usage_mb" to memoryUsageMB,
                    "total_inferences" to totalInferences.get(),
                    "success_rate" to (successfulInferences.get().toFloat() / totalInferences.get().toFloat())
                )
                
                tracker.trackEvent("llm_inference", eventData)
            }
        }
        
        // Log detailed metrics
        Log.d(TAG, "Inference recorded: ${durationMs}ms, success=$success, " +
                "fields=$extractedFieldCount, memory=${memoryUsageMB}MB, fallback=$fallbackUsed")
        
        // Persist telemetry data asynchronously
        CoroutineScope(Dispatchers.IO).launch {
            persistTelemetryData(durationMs, success, errorType, fallbackUsed, extractedFieldCount, memoryUsageMB)
        }
    }
    
    /**
     * Record timeout-specific inference failure
     */
    fun recordTimeout(durationMs: Long, memoryUsageMB: Long = 0) {
        timeoutInferences.incrementAndGet()
        recordInference(durationMs, false, "TIMEOUT", null, 0, memoryUsageMB)
    }
    
    /**
     * Record model load/unload events
     */
    fun recordModelLoad(success: Boolean, loadTimeMs: Long) {
        if (success) {
            modelLoadCount.incrementAndGet()
            Log.d(TAG, "Model load recorded: ${loadTimeMs}ms")
            
            // Send analytics event for model loading
            analyticsTracker?.let { tracker ->
                CoroutineScope(Dispatchers.IO).launch {
                    val eventData = mapOf(
                        "load_time_ms" to loadTimeMs,
                        "success" to success,
                        "total_loads" to modelLoadCount.get()
                    )
                    tracker.trackEvent("llm_model_load", eventData)
                }
            }
        }
    }
    
    fun recordModelUnload() {
        modelUnloadCount.incrementAndGet()
        Log.d(TAG, "Model unload recorded")
        
        // Send analytics event for model unloading
        analyticsTracker?.let { tracker ->
            CoroutineScope(Dispatchers.IO).launch {
                val eventData = mapOf(
                    "total_unloads" to modelUnloadCount.get(),
                    "session_duration_ms" to (System.currentTimeMillis() - sessionStartTime)
                )
                tracker.trackEvent("llm_model_unload", eventData)
            }
        }
    }
    
    /**
     * Get comprehensive performance metrics
     */
    fun getPerformanceMetrics(): LlmPerformanceMetrics {
        val totalCount = totalInferences.get()
        val avgInferenceTime = if (totalCount > 0) {
            totalInferenceTime.get() / totalCount
        } else 0L
        
        val successRate = if (totalCount > 0) {
            (successfulInferences.get().toDouble() / totalCount * 100).toFloat()
        } else 0f
        
        val timeoutRate = if (totalCount > 0) {
            (timeoutInferences.get().toDouble() / totalCount * 100).toFloat()
        } else 0f
        
        val fallbackRate = if (totalCount > 0) {
            ((fallbackToMlKit.get() + fallbackToModelBased.get()).toDouble() / totalCount * 100).toFloat()
        } else 0f
        
        return LlmPerformanceMetrics(
            totalInferences = totalCount,
            successfulInferences = successfulInferences.get(),
            failedInferences = failedInferences.get(),
            timeoutInferences = timeoutInferences.get(),
            averageInferenceTimeMs = avgInferenceTime,
            successRate = successRate,
            timeoutRate = timeoutRate,
            fallbackRate = fallbackRate,
            fallbackToMlKitCount = fallbackToMlKit.get(),
            fallbackToModelBasedCount = fallbackToModelBased.get(),
            modelLoadCount = modelLoadCount.get(),
            modelUnloadCount = modelUnloadCount.get(),
            peakMemoryUsageMB = peakMemoryUsageMB.get(),
            sessionDurationMs = System.currentTimeMillis() - sessionStartTime,
            lastInferenceTimeMs = lastInferenceTime
        )
    }
    
    /**
     * Get telemetry summary for analytics
     */
    fun getTelemetrySummary(): String {
        val metrics = getPerformanceMetrics()
        
        return """
            |MiniCPM LLM Telemetry Summary:
            |  Total Inferences: ${metrics.totalInferences}
            |  Success Rate: ${String.format("%.1f", metrics.successRate)}%
            |  Average Time: ${metrics.averageInferenceTimeMs}ms
            |  Timeout Rate: ${String.format("%.1f", metrics.timeoutRate)}%
            |  Fallback Rate: ${String.format("%.1f", metrics.fallbackRate)}%
            |  Peak Memory: ${metrics.peakMemoryUsageMB}MB
            |  Model Loads: ${metrics.modelLoadCount}
            |  Session Duration: ${metrics.sessionDurationMs / 1000}s
        """.trimMargin()
    }
    
    /**
     * Reset all telemetry counters
     */
    fun resetMetrics() {
        totalInferences.set(0)
        successfulInferences.set(0)
        failedInferences.set(0)
        timeoutInferences.set(0)
        fallbackToMlKit.set(0)
        fallbackToModelBased.set(0)
        totalInferenceTime.set(0)
        modelLoadCount.set(0)
        modelUnloadCount.set(0)
        peakMemoryUsageMB.set(0)
        
        Log.i(TAG, "Telemetry metrics reset")
    }
    
    /**
     * Export telemetry data for analysis
     */
    fun exportTelemetryData(): String {
        val metrics = getPerformanceMetrics()
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("session_start", sessionStartTime)
            put("total_inferences", metrics.totalInferences)
            put("successful_inferences", metrics.successfulInferences)
            put("failed_inferences", metrics.failedInferences)
            put("timeout_inferences", metrics.timeoutInferences)
            put("average_inference_time_ms", metrics.averageInferenceTimeMs)
            put("success_rate", metrics.successRate)
            put("timeout_rate", metrics.timeoutRate)
            put("fallback_rate", metrics.fallbackRate)
            put("fallback_mlkit_count", metrics.fallbackToMlKitCount)
            put("fallback_model_based_count", metrics.fallbackToModelBasedCount)
            put("model_load_count", metrics.modelLoadCount)
            put("model_unload_count", metrics.modelUnloadCount)
            put("peak_memory_usage_mb", metrics.peakMemoryUsageMB)
            put("session_duration_ms", metrics.sessionDurationMs)
        }
        
        return json.toString(2)
    }
    
    /**
     * Persist telemetry data to local storage
     */
    private fun persistTelemetryData(
        durationMs: Long,
        success: Boolean,
        errorType: String?,
        fallbackUsed: String?,
        extractedFieldCount: Int,
        memoryUsageMB: Long
    ) {
        try {
            val telemetryFile = File(context.filesDir, TELEMETRY_FILE)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            val entry = JSONObject().apply {
                put("timestamp", timestamp)
                put("duration_ms", durationMs)
                put("success", success)
                put("error_type", errorType)
                put("fallback_used", fallbackUsed)
                put("extracted_fields", extractedFieldCount)
                put("memory_usage_mb", memoryUsageMB)
            }
            
            // Append to telemetry log (keep last MAX_LOG_ENTRIES)
            val existingData = if (telemetryFile.exists()) {
                telemetryFile.readText()
            } else {
                "[]"
            }
            
            val jsonArray = org.json.JSONArray(existingData)
            jsonArray.put(entry)
            
            // Keep only recent entries
            if (jsonArray.length() > MAX_LOG_ENTRIES) {
                val trimmedArray = org.json.JSONArray()
                for (i in (jsonArray.length() - MAX_LOG_ENTRIES) until jsonArray.length()) {
                    trimmedArray.put(jsonArray.get(i))
                }
                telemetryFile.writeText(trimmedArray.toString(2))
            } else {
                telemetryFile.writeText(jsonArray.toString(2))
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist telemetry data", e)
        }
    }
}

/**
 * Data class for LLM performance metrics
 */
data class LlmPerformanceMetrics(
    val totalInferences: Long,
    val successfulInferences: Long,
    val failedInferences: Long,
    val timeoutInferences: Long,
    val averageInferenceTimeMs: Long,
    val successRate: Float,
    val timeoutRate: Float,
    val fallbackRate: Float,
    val fallbackToMlKitCount: Long,
    val fallbackToModelBasedCount: Long,
    val modelLoadCount: Int,
    val modelUnloadCount: Int,
    val peakMemoryUsageMB: Long,
    val sessionDurationMs: Long,
    val lastInferenceTimeMs: Long
)

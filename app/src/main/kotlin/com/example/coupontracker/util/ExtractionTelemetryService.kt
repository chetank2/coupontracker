package com.example.coupontracker.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for tracking extraction pipeline telemetry and run paths
 */
@Singleton
class ExtractionTelemetryService @Inject constructor(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ExtractionTelemetry"
    }
    
    private val telemetryScope = CoroutineScope(Dispatchers.IO)
    
    /**
     * Track extraction run path for analysis
     */
    fun trackRunPath(runPath: RunPath) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "extraction_path")
                    put("strategy", runPath.strategy)  // V2: renamed from primary
                    put("tried", runPath.tried.joinToString(","))
                    put("final", runPath.final)
                    put("reasons", runPath.reasons.joinToString(","))  // V2: added reasons
                    put("native_available", runPath.nativeAvailable)
                    put("total_time_ms", runPath.totalTimeMs)
                    put("device_info", getDeviceInfo())
                }
                
                Log.d(TAG, "Run path: ${runPath.strategy} → ${runPath.final} (${runPath.totalTimeMs}ms)")
                
                // In production, send to analytics service
                // analyticsService.track(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking run path", e)
            }
        }
    }
    
    /**
     * Track extraction result with detailed signals
     */
    fun trackExtractionResult(
        result: ExtractResult,
        runPath: RunPath
    ) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "extraction_result")
                    put("result_type", result::class.simpleName)
                    put("run_path", JSONObject().apply {
                        put("strategy", runPath.strategy)  // V2: renamed from primary
                        put("final", runPath.final)
                        put("total_time_ms", runPath.totalTimeMs)
                        put("reasons", runPath.reasons.joinToString(","))  // V2: added reasons
                    })
                    
                    when (result) {
                        is ExtractResult.Good -> {
                            put("quality_score", result.signals.qualityScore)
                            put("processing_time_ms", result.signals.processingTimeMs)
                            put("memory_usage_mb", result.signals.memoryUsageMB)
                            put("field_confidences", JSONObject(result.signals.fieldConfidences))
                        }
                        
                        is ExtractResult.LowQuality -> {
                            put("quality_score", result.signals.qualityScore)
                            put("quality_reason", result.reason.name)
                            put("processing_time_ms", result.signals.processingTimeMs)
                            put("memory_usage_mb", result.signals.memoryUsageMB)
                        }
                        
                        is ExtractResult.Failed -> {
                            put("failed_stage", result.stage.name)
                            put("error_type", result.error::class.simpleName)
                            put("error_message", result.error.message)
                            result.signals?.let { signals ->
                                put("processing_time_ms", signals.processingTimeMs)
                                put("memory_usage_mb", signals.memoryUsageMB)
                            }
                        }
                    }
                    
                    put("device_info", getDeviceInfo())
                }
                
                Log.d(TAG, "Extraction result: ${result::class.simpleName}")
                
                // In production, send to analytics service
                // analyticsService.track(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking extraction result", e)
            }
        }
    }
    
    fun trackStrategySelection(
        requestedStrategy: String,
        activeStrategy: String,
        allowed: Boolean,
        reason: String?,
        advancedEnabled: Boolean
    ) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "strategy_selection")
                    put("requested", requestedStrategy)
                    put("active", activeStrategy)
                    put("allowed", allowed)
                    put("advanced_enabled", advancedEnabled)
                    reason?.let { put("reason", it) }
                    put("device_info", getDeviceInfo())
                    put("timestamp", System.currentTimeMillis())
                }

                Log.d(
                    TAG,
                    "Strategy selection: requested=$requestedStrategy active=$activeStrategy allowed=$allowed reason=${reason ?: "none"}"
                )

                // analyticsService.track(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking strategy selection", e)
            }
        }
    }

    /**
     * Track native library state at boot
     */
    fun trackNativeState(state: NativeState) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "llm_native_state")
                    put("state", state.name)
                    put("device_info", getDeviceInfo())
                    put("timestamp", System.currentTimeMillis())
                }
                
                Log.i(TAG, "Native state: $state")
                
                // In production, send to analytics service
                // analyticsService.track(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking native state", e)
            }
        }
    }

    /**
     * Track an OCR fallback decision. `triggered=true` means Tesseract ran;
     * `false` means MLKit alone was sufficient.
     */
    fun recordOcrFallback(reason: String, triggered: Boolean) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "ocr_fallback")
                    put("reason", reason)
                    put("triggered", triggered)
                }
                Log.d(TAG, "OCR fallback: reason=$reason triggered=$triggered")
                // Event emission to the real sink would happen here if wired.
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record OCR fallback", e)
            }
        }
    }

    /**
     * Track an OCR fallback engine failure.
     */
    fun recordOcrFallbackFailure(reason: String, exceptionName: String) {
        telemetryScope.launch {
            try {
                Log.w(TAG, "OCR fallback failed: reason=$reason ex=$exceptionName")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record OCR fallback failure", e)
            }
        }
    }

    fun trackMultiCouponDetectorState(
        state: MultiCouponDetectorState,
        reason: String? = null
    ) {
        telemetryScope.launch {
            try {
                val event = JSONObject().apply {
                    put("event", "multi_coupon_detector_state")
                    put("state", state.name)
                    reason?.let { put("reason", it) }
                    put("device_info", getDeviceInfo())
                    put("timestamp", System.currentTimeMillis())
                }

                Log.i(TAG, "Multi-coupon detector state=$state reason=${reason ?: "none"}")

                // analyticsService.track(event)
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking multi-coupon detector state", e)
            }
        }
    }
    
    /**
     * Get device information for telemetry stratification
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("api_level", Build.VERSION.SDK_INT)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            
            // Memory information
            val runtime = Runtime.getRuntime()
            put("max_memory_mb", runtime.maxMemory() / (1024 * 1024))
            put("total_memory_mb", runtime.totalMemory() / (1024 * 1024))
            put("free_memory_mb", runtime.freeMemory() / (1024 * 1024))
            
            // Thermal and battery state would be added here in production
            // put("thermal_state", getThermalState())
            // put("battery_level", getBatteryLevel())
        }
    }
}

/**
 * Native library availability states
 */
enum class NativeState {
    REAL,      // Native library loaded and functional
    MOCK,      // Using mock implementation (library not available)
    MISSING    // Library completely missing
}

enum class MultiCouponDetectorState {
    ENABLED,
    DISABLED,
    STUB
}

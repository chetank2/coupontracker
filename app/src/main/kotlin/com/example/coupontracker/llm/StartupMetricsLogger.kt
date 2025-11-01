package com.example.coupontracker.llm

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight logger that emits one-time startup telemetry about the native LLM state.
 *
 * Logs are designed to be human-readable in logcat while also acting as structured
 * breadcrumbs for automated startup diagnostics.
 */
object StartupMetricsLogger {

    private const val TAG = "StartupMetrics"

    private val nativeLogged = AtomicBoolean(false)
    private val modelValidationLogged = AtomicBoolean(false)
    private val warmupLogged = AtomicBoolean(false)

    fun logNativeLlmLoaded(success: Boolean, message: String? = null, throwable: Throwable? = null) {
        if (success) {
            if (nativeLogged.compareAndSet(false, true)) {
                Log.i(TAG, formatSuccess("✅ Native LLM loaded", message))
            }
            return
        }

        nativeLogged.set(false)
        Log.e(TAG, formatFailure("❌ Native LLM load failed", message), throwable)
    }

    fun logModelFilesValidated(success: Boolean, message: String? = null) {
        if (success) {
            if (modelValidationLogged.compareAndSet(false, true)) {
                Log.i(TAG, formatSuccess("✅ Model files validated", message))
            }
            return
        }

        modelValidationLogged.set(false)
        Log.w(TAG, formatFailure("⚠️ Model file validation failed", message))
    }

    fun logWarmupComplete(
        success: Boolean,
        durationMs: Long? = null,
        tokensPerSecond: Double? = null,
        message: String? = null,
        throwable: Throwable? = null
    ) {
        if (success) {
            val details = buildList {
                durationMs?.let { add("${it}ms") }
                tokensPerSecond?.let { add(String.format("%.2f tokens/sec", it)) }
                message?.let { add(it) }
            }.joinToString(separator = ", ").takeIf { it.isNotBlank() }

            if (warmupLogged.compareAndSet(false, true)) {
                val suffix = details?.let { " ($it)" } ?: ""
                Log.i(TAG, "✅ LLM warmup complete$suffix")
            }
            return
        }

        warmupLogged.set(false)
        Log.e(TAG, formatFailure("❌ LLM warmup failed", message), throwable)
    }

    private fun formatSuccess(prefix: String, message: String?): String {
        return if (message.isNullOrBlank()) prefix else "$prefix - $message"
    }

    private fun formatFailure(prefix: String, message: String?): String {
        return if (message.isNullOrBlank()) prefix else "$prefix: $message"
    }
}


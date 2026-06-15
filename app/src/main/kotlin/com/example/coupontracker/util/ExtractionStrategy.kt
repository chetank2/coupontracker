package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Capture extraction strategy.
 *
 * Capture is intentionally OCR-first. Qwen cleanup runs only from the explicit
 * Clean action after a coupon has been saved.
 */
enum class ExtractionStrategy {
    /**
     * OCR extracts text immediately and local heuristics build the saved coupon.
     */
    OCR_FIRST
}

/**
 * Global capture extraction configuration.
 */
object ExtractionConfig {
    private const val TAG = "ExtractionConfig"
    private const val PREFS_NAME = "extraction_config"
    private const val KEY_STRATEGY = "extraction_strategy"

    private var prefs: SharedPreferences? = null

    private var _strategy: ExtractionStrategy = ExtractionStrategy.OCR_FIRST
    private var isInitialized = false

    @VisibleForTesting
    internal var runtimeAvailabilityChecker: (Context) -> Boolean = { false }
    
    private lateinit var telemetry: ExtractionTelemetryService

    /**
     * Initialize with application context
     * Call this from Application.onCreate() or before first use
     */
    fun init(context: Context) {
        if (isInitialized) return

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        telemetry = ExtractionTelemetryService(context)

        val savedStrategy = readSavedStrategy()

        if (savedStrategy != null && !isStrategyAllowed(savedStrategy)) {
            Log.i(
                TAG,
                "Saved strategy ${savedStrategy.name} is no longer supported for capture – using OCR_FIRST"
            )
            recordStrategyTelemetry(
                requested = savedStrategy.name,
                active = computeDefaultStrategy().name,
                allowed = false,
                reason = "manual_clean_required"
            )
        }

        val defaultStrategy = computeDefaultStrategy()

        val chosen = savedStrategy?.takeIf { isStrategyAllowed(it) } ?: defaultStrategy

        _strategy = chosen

        Log.d(TAG, "Loaded capture strategy: ${_strategy.name}")
        recordStrategyTelemetry(
            requested = savedStrategy?.name ?: defaultStrategy.name,
            active = chosen.name,
            allowed = true,
            reason = null
        )

        isInitialized = true
    }

    private fun computeDefaultStrategy(): ExtractionStrategy {
        return ExtractionStrategy.OCR_FIRST
    }
    
    /**
     * Get current extraction strategy
     * Returns OCR_FIRST if not initialized (safe default)
     */
    fun getStrategy(): ExtractionStrategy {
        return if (isInitialized) _strategy else ExtractionStrategy.OCR_FIRST
    }
    
    /**
     * Set capture strategy. Capture currently supports OCR_FIRST only.
     */
    fun setStrategy(strategy: ExtractionStrategy) {
        if (!isStrategyAllowed(strategy)) {
            Log.w(TAG, "Attempted to set unsupported capture strategy ${strategy.name}; keeping OCR_FIRST")
            prefs?.edit()?.putString(KEY_STRATEGY, strategy.name)?.apply()
            recordStrategyTelemetry(
                requested = strategy.name,
                active = _strategy.name,
                allowed = false,
                reason = "manual_clean_required"
            )
            return
        }

        if (_strategy != strategy) {
            Log.d(TAG, "Strategy changed: ${_strategy.name} → ${strategy.name}")
            updateStrategyInternal(strategy, persist = true)
            recordStrategyTelemetry(
                requested = strategy.name,
                active = strategy.name,
                allowed = true,
                reason = null
            )
        }
    }

    private fun recordStrategyTelemetry(
        requested: String,
        active: String,
        allowed: Boolean,
        reason: String?
    ) {
        runCatching {
            telemetry.trackStrategySelection(
                requestedStrategy = requested,
                activeStrategy = active,
                allowed = allowed,
                reason = reason,
                advancedEnabled = false
            )
        }.onFailure { error ->
            Log.w(TAG, "Telemetry recording failed", error)
        }
    }

    /**
     * Capture always starts with OCR. Model cleanup is a separate explicit action.
     */
    fun getAvailableStrategies(): List<ExtractionStrategy> {
        return listOf(ExtractionStrategy.OCR_FIRST)
    }
    
    /**
     * Check if a specific strategy is available
     */
    fun isStrategyAvailable(strategy: ExtractionStrategy): Boolean {
        return getAvailableStrategies().contains(strategy)
    }

    fun areAdvancedStrategiesEnabled(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun setAdvancedStrategiesEnabled(enabled: Boolean, persist: Boolean = true) {
        updateStrategyInternal(ExtractionStrategy.OCR_FIRST, persist = false)
        recordStrategyTelemetry(
            requested = enabled.toString(),
            active = ExtractionStrategy.OCR_FIRST.name,
            allowed = false,
            reason = "manual_clean_required"
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun refreshRuntimeAvailability(context: Context) {
        if (!isInitialized) {
            return
        }

        if (_strategy != ExtractionStrategy.OCR_FIRST) {
            Log.i(TAG, "Manual-clean flow requires OCR_FIRST capture – reverting from ${_strategy.name}")
            updateStrategyInternal(ExtractionStrategy.OCR_FIRST, persist = false)
        }
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        prefs = null
        _strategy = ExtractionStrategy.OCR_FIRST
        isInitialized = false
        runtimeAvailabilityChecker = { false }
    }

    private fun updateStrategyInternal(strategy: ExtractionStrategy, persist: Boolean) {
        _strategy = strategy
        if (persist) {
            prefs?.edit()?.putString(KEY_STRATEGY, strategy.name)?.apply()
        }
    }

    private fun readSavedStrategy(): ExtractionStrategy? {
        val savedStrategyName = prefs?.getString(KEY_STRATEGY, null) ?: return null
        return try {
            ExtractionStrategy.valueOf(savedStrategyName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid saved strategy: $savedStrategyName, ignoring")
            null
        }
    }

    private fun isStrategyAllowed(strategy: ExtractionStrategy): Boolean {
        return strategy == ExtractionStrategy.OCR_FIRST
    }
    
    /**
     * Per-field confidence thresholds
     * These determine when to accept extraction results
     * Can be customized via Remote Config for fine-tuning
     */
    object Thresholds {
        var code: Float = 0.85f         // Base regex + brand boost + OCR match ≤2 edits
        var expiry: Float = 0.70f       // Parsed date, not past, near "valid/expiry" token
        var cashback: Float = 0.75f     // Amount/% near currency/offer tokens
        var storeName: Float = 0.60f    // Brand detection or prominent text
        
        /**
         * Check if field confidences meet aggregate acceptance criteria
         * Rule: code ≥ threshold AND (expiry ≥ threshold OR cashback ≥ threshold)
         */
        fun isAcceptable(fieldConfs: Map<String, Float>): Boolean {
            val codeOk = (fieldConfs["code"] ?: 0f) >= code
            val expiryOk = (fieldConfs["expiry"] ?: 0f) >= expiry
            val cashbackOk = (fieldConfs["cashback"] ?: 0f) >= cashback
            
            return codeOk && (expiryOk || cashbackOk)
        }
        
        /**
         * Get missing fields that fall below threshold
         * Useful for "Needs Review" UI messages
         */
        fun getMissingFields(fieldConfs: Map<String, Float>): List<String> {
            val missing = mutableListOf<String>()
            
            if ((fieldConfs["code"] ?: 0f) < code) missing.add("code")
            if ((fieldConfs["expiry"] ?: 0f) < expiry) missing.add("expiry")
            if ((fieldConfs["cashback"] ?: 0f) < cashback) missing.add("cashback")
            if ((fieldConfs["storeName"] ?: 0f) < storeName) missing.add("storeName")
            
            return missing
        }
    }
    
    /**
     * Per-stage timeouts in milliseconds
     * Prevents hanging on slow devices or problematic images
     */
    object Timeouts {
        const val LLM_TILE_MS = 2000L           // Per LLM tile processing
        const val OCR_ROI_BATCH_MS = 1000L      // OCR batch of all ROIs
        const val FUSION_MS = 300L              // Fusion decision per field
        const val E2E_PER_COUPON_MS = 6000L     // Total per coupon (p95 mid-tier device)
        const val TWO_STAGE_DETECT_MS = 3000L   // Coupon instance detection
    }
    
    /**
     * Remote Config integration. Unsupported values keep capture on OCR_FIRST.
     */
    fun updateFromRemoteConfig(remoteStrategy: String) {
        try {
            val strategy = ExtractionStrategy.valueOf(remoteStrategy.uppercase())
            setStrategy(strategy)
            Log.d(TAG, "Updated strategy from Remote Config: ${strategy.name}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid strategy from Remote Config: $remoteStrategy, keeping current: ${_strategy.name}")
        }
    }
    
    /**
     * Get strategy name for logging and analytics
     */
    fun getStrategyName(): String = _strategy.name
    
    fun isLegacyMode(): Boolean = false

    fun isV2Mode(): Boolean = true
}

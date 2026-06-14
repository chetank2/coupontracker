package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Extraction strategy for V2 architecture
 * Determines how LLM and OCR are combined in the extraction pipeline
 */
enum class ExtractionStrategy {
    /**
     * LLM locates field regions → OCR extracts text from those regions → Fusion decides
     * Recommended for most cases - leverages LLM's semantic understanding
     */
    LLM_FIRST,
    
    /**
     * OCR finds text regions → LLM validates and labels semantically → Fusion decides
     * Useful when OCR is more reliable than LLM for text extraction
     */
    OCR_FIRST,
    
    /**
     * Parallel execution of LLM and OCR → Fusion arbitrates between results
     * Experimental - highest accuracy but slowest
     */
    HYBRID,
    
    /**
     * Current existing behavior (for backward compatibility)
     * Uses existing extraction flow without V2 architecture changes
     */
    LEGACY
}

/**
 * Global extraction configuration
 * Can be controlled via Remote Config (Firebase) for production rollout
 * 
 * V2 Fix: Now persists strategy to SharedPreferences
 */
object ExtractionConfig {
    private const val TAG = "ExtractionConfig"
    private const val PREFS_NAME = "extraction_config"
    private const val KEY_STRATEGY = "extraction_strategy"
    private const val KEY_ADVANCED_ENABLED = "advanced_strategies_enabled"

    private var prefs: SharedPreferences? = null

    // Default strategy (OCR_FIRST - most reliable when advanced runtime unavailable)
    private var _strategy: ExtractionStrategy = ExtractionStrategy.OCR_FIRST
    @Volatile
    private var advancedStrategiesEnabled: Boolean = false
    @Volatile
    private var pendingAdvancedFlag: PendingAdvancedFlag? = null
    private var isInitialized = false
    @Volatile
    private var runtimeAdvancedAvailable: Boolean = false

    private data class PendingAdvancedFlag(val enabled: Boolean, val persist: Boolean)

    @VisibleForTesting
    internal var runtimeAvailabilityChecker: (Context) -> Boolean = { context ->
        val nativeReady = runCatching {
            com.example.coupontracker.llm.MlcLlmNative.loadLibrary(context)
        }.getOrDefault(false) ||
            runCatching { com.example.coupontracker.llm.MlcLlmNative.isAvailable() }
                .getOrDefault(false)

        val modelReady = runCatching {
            com.example.coupontracker.llm.LlmRuntimeManager.getInstance(context).isModelAvailable()
        }.getOrDefault(false)

        nativeReady && modelReady
    }
    
    private lateinit var telemetry: ExtractionTelemetryService

    /**
     * Initialize with application context
     * Call this from Application.onCreate() or before first use
     */
    fun init(context: Context) {
        if (isInitialized) return

        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        telemetry = ExtractionTelemetryService(context)

        val pending = pendingAdvancedFlag
        runtimeAdvancedAvailable = runCatching { runtimeAvailabilityChecker(context) }.getOrDefault(false)

        advancedStrategiesEnabled = false

        if (pending != null) {
            if (pending.persist) {
                prefs?.edit()?.putBoolean(KEY_ADVANCED_ENABLED, pending.enabled)?.apply()
            }
            pendingAdvancedFlag = null
        }

        val savedStrategy = readSavedStrategy()

        if (advancedStrategiesEnabled && !runtimeAdvancedAvailable) {
            Log.i(TAG, "Advanced strategies preference set but runtime unavailable – awaiting model readiness")
            recordStrategyTelemetry(
                requested = savedStrategy?.name ?: computeDefaultStrategy().name,
                active = ExtractionStrategy.OCR_FIRST.name,
                allowed = false,
                reason = "runtime_unavailable"
            )
        }
        if (savedStrategy != null && !isStrategyAllowed(savedStrategy)) {
            Log.i(
                TAG,
                "Saved strategy ${savedStrategy.name} blocked by rollout guard – using OCR_FIRST until enabled"
            )
            recordStrategyTelemetry(
                requested = savedStrategy.name,
                active = computeDefaultStrategy().name,
                allowed = false,
                reason = "blocked_by_rollout"
            )
        }

        val defaultStrategy = computeDefaultStrategy()

        val chosen = savedStrategy?.takeIf { isStrategyAllowed(it) } ?: defaultStrategy

        _strategy = chosen

        Log.d(TAG, "Loaded strategy: ${_strategy.name} (advanced=${advancedStrategiesEnabled})")
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
     * Set extraction strategy (can be called from Settings UI or Remote Config)
     * Persists to SharedPreferences immediately
     * 
     * Note: LLM_FIRST and HYBRID are blocked at load time if real models not available
     */
    fun setStrategy(strategy: ExtractionStrategy) {
        if (!isStrategyAllowed(strategy)) {
            Log.w(TAG, "Attempted to set blocked strategy ${strategy.name} – ignoring due to rollout guard")
            prefs?.edit()?.putString(KEY_STRATEGY, strategy.name)?.apply()
            recordStrategyTelemetry(
                requested = strategy.name,
                active = _strategy.name,
                allowed = false,
                reason = "blocked_by_rollout"
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
                advancedEnabled = advancedStrategiesEnabled
            )
        }.onFailure { error ->
            Log.w(TAG, "Telemetry recording failed", error)
        }
    }

    /**
     * Get available strategies based on current model availability
     * Returns only OCR_FIRST and LEGACY until the native llama.cpp binaries are integrated
     */
    fun getAvailableStrategies(): List<ExtractionStrategy> {
        val strategies = linkedSetOf(
            ExtractionStrategy.OCR_FIRST
        )

        if (shouldExposeAdvancedStrategies()) {
            strategies.add(ExtractionStrategy.LLM_FIRST)
            strategies.add(ExtractionStrategy.HYBRID)
        }

        return strategies.toList()
    }
    
    /**
     * Check if a specific strategy is available
     */
    fun isStrategyAvailable(strategy: ExtractionStrategy): Boolean {
        return getAvailableStrategies().contains(strategy)
    }

    fun areAdvancedStrategiesEnabled(): Boolean = false

    fun setAdvancedStrategiesEnabled(enabled: Boolean, persist: Boolean = true) {
        advancedStrategiesEnabled = false
        if (persist && isInitialized) {
            prefs?.edit()?.putBoolean(KEY_ADVANCED_ENABLED, false)?.apply()
        }
        if (isStrategyGuarded(_strategy)) {
            updateStrategyInternal(ExtractionStrategy.OCR_FIRST, persist = false)
        }
        recordStrategyTelemetry(
            requested = enabled.toString(),
            active = ExtractionStrategy.OCR_FIRST.name,
            allowed = false,
            reason = "manual_clean_required"
        )
    }

    fun refreshRuntimeAvailability(context: Context) {
        if (!isInitialized) {
            return
        }

        runtimeAdvancedAvailable = runCatching { runtimeAvailabilityChecker(context) }.getOrDefault(false)
        advancedStrategiesEnabled = false
        if (_strategy != ExtractionStrategy.OCR_FIRST) {
            Log.i(TAG, "Manual-clean flow requires OCR_FIRST capture – reverting from ${_strategy.name}")
            updateStrategyInternal(ExtractionStrategy.OCR_FIRST, persist = false)
        }
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        prefs = null
        _strategy = ExtractionStrategy.OCR_FIRST
        advancedStrategiesEnabled = false
        pendingAdvancedFlag = null
        isInitialized = false
        runtimeAdvancedAvailable = false
        runtimeAvailabilityChecker = { context ->
            val nativeReady = runCatching {
                com.example.coupontracker.llm.MlcLlmNative.loadLibrary(context)
            }.getOrDefault(false) ||
                runCatching { com.example.coupontracker.llm.MlcLlmNative.isAvailable() }
                    .getOrDefault(false)

            val modelReady = runCatching {
                com.example.coupontracker.llm.LlmRuntimeManager.getInstance(context).isModelAvailable()
            }.getOrDefault(false)

            nativeReady && modelReady
        }
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

    private fun isStrategyGuarded(strategy: ExtractionStrategy): Boolean {
        return when (strategy) {
            ExtractionStrategy.LLM_FIRST, ExtractionStrategy.HYBRID -> true
            else -> false
        }
    }

    private fun isStrategyAllowed(strategy: ExtractionStrategy): Boolean {
        return strategy == ExtractionStrategy.OCR_FIRST
    }

    private fun shouldExposeAdvancedStrategies(): Boolean {
        if (!advancedStrategiesEnabled) {
            return false
        }

        if (isStrategyGuarded(_strategy)) {
            return true
        }

        return runtimeAdvancedAvailable
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
     * Remote Config integration
     * Call this from Application.onCreate() to fetch strategy from Firebase
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
    
    /**
     * Check if using legacy mode (backward compatibility)
     */
    fun isLegacyMode(): Boolean = _strategy == ExtractionStrategy.LEGACY
    
    /**
     * Check if using any V2 architecture strategy
     */
    fun isV2Mode(): Boolean = _strategy != ExtractionStrategy.LEGACY
}

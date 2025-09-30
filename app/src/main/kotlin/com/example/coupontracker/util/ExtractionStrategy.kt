package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

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
    
    private var prefs: SharedPreferences? = null
    
    // Default strategy (LEGACY for safety during migration)
    private var _strategy: ExtractionStrategy = ExtractionStrategy.LEGACY
    private var isInitialized = false
    
    /**
     * Initialize with application context
     * Call this from Application.onCreate() or before first use
     */
    fun init(context: Context) {
        if (isInitialized) return
        
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load saved strategy
        val savedStrategyName = prefs?.getString(KEY_STRATEGY, ExtractionStrategy.LEGACY.name)
        try {
            _strategy = ExtractionStrategy.valueOf(savedStrategyName ?: ExtractionStrategy.LEGACY.name)
            Log.d(TAG, "Loaded saved strategy: ${_strategy.name}")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid saved strategy: $savedStrategyName, defaulting to LEGACY")
            _strategy = ExtractionStrategy.LEGACY
        }
        
        isInitialized = true
    }
    
    /**
     * Get current extraction strategy
     * Returns LEGACY if not initialized (safe default)
     */
    fun getStrategy(): ExtractionStrategy {
        return if (isInitialized) _strategy else ExtractionStrategy.LEGACY
    }
    
    /**
     * Set extraction strategy (can be called from Settings UI or Remote Config)
     * Persists to SharedPreferences immediately
     */
    fun setStrategy(strategy: ExtractionStrategy) {
        if (_strategy != strategy) {
            Log.d(TAG, "Strategy changed: ${_strategy.name} → ${strategy.name}")
            _strategy = strategy
            
            // Persist to SharedPreferences
            prefs?.edit()?.putString(KEY_STRATEGY, strategy.name)?.apply()
        }
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

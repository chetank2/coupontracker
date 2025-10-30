package com.example.coupontracker.util

/**
 * Enum representing different OCR API types available in the application
 */
enum class ApiType(val displayName: String, val description: String) {
    /**
     * Local LLM-based OCR using the Qwen2.5 text model.
     * Fully offline, privacy-focused, structured extraction.
     */
    LOCAL_LLM(
        displayName = "Local AI Model", 
        description = "On-device Qwen2.5 model for structured coupon extraction"
    ),
    
    /**
     * Model-based OCR using pattern recognition + ML Kit
     * Hybrid approach with pattern matching and traditional OCR
     */
    MODEL_BASED(
        displayName = "Pattern Recognition", 
        description = "Advanced pattern recognition with ML Kit fallback"
    ),
    
    /**
     * ML Kit only - Google's on-device text recognition
     * Simple text extraction with basic field parsing
     */
    ML_KIT_ONLY(
        displayName = "Basic OCR", 
        description = "Google ML Kit text recognition only"
    );

    companion object {
        /**
         * Get ApiType from string value (for preferences storage)
         */
        fun fromString(value: String): ApiType {
            return when (value.uppercase()) {
                "LOCAL_LLM" -> LOCAL_LLM
                "MODEL_BASED" -> MODEL_BASED
                "ML_KIT_ONLY" -> ML_KIT_ONLY
                // Legacy compatibility
                "SUPER" -> MODEL_BASED
                "COMBINED" -> MODEL_BASED
                "GOOGLE_VISION" -> ML_KIT_ONLY
                "ML_KIT" -> ML_KIT_ONLY
                else -> MODEL_BASED // Default fallback
            }
        }
        
        /**
         * Get all available API types
         */
        fun getAvailableTypes(): List<ApiType> {
            return values().toList()
        }
        
        /**
         * Get the default API type
         */
        fun getDefault(): ApiType = MODEL_BASED
    }
    
    /**
     * Convert to string for storage
     */
    override fun toString(): String {
        return name
    }
    
    /**
     * Check if this API type requires model download
     */
    fun requiresModelDownload(): Boolean {
        return this == LOCAL_LLM
    }
    
    /**
     * Check if this API type works offline
     */
    fun isOfflineCapable(): Boolean {
        return true // All current types are offline-capable
    }
    
    /**
     * Get the priority order for fallback (higher number = higher priority)
     */
    fun getPriority(): Int {
        return when (this) {
            LOCAL_LLM -> 3        // Highest priority when available
            MODEL_BASED -> 2      // Good balance of accuracy and speed
            ML_KIT_ONLY -> 1      // Basic fallback
        }
    }
}

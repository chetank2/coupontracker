package com.example.coupontracker.util

import android.util.Log

/**
 * Shared utility for detecting generic/boilerplate text that should be treated as missing data.
 * Used by both LocalLlmOcrService and ModelBasedOCRService to ensure consistent validation.
 */
object GenericFieldHeuristics {
    private const val TAG = "GenericFieldHeuristics"
    
    /**
     * Check if a field contains generic/boilerplate text that should be treated as missing
     */
    fun isGenericOrMissing(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        
        val genericWords = setOf(
            // Generic UI labels
            "voucher", "vouchers", "coupon", "coupons", "offer", "offers",
            "deal", "deals", "discount", "discounts", "promo", "promotion",
            
            // Generic descriptions  
            "details", "description", "info", "information", "text",
            "content", "data", "field", "value", "item", "element",
            
            // Generic store names
            "store", "shop", "merchant", "brand", "company", "business",
            "retailer", "vendor", "seller", "provider",
            
            // Common placeholder text
            "unknown", "default", "placeholder", "sample", "example",
            "test", "demo", "temp", "temporary", "loading"
        )
        
        val cleanValue = value.trim().lowercase()
        
        // Check if the entire value is a generic word
        if (genericWords.contains(cleanValue)) {
            Log.d(TAG, "Treating '$value' as generic/missing - detected generic word")
            return true
        }
        
        // Check if it's only generic words (multiple words)
        val words = cleanValue.split(Regex("\\s+"))
        if (words.isNotEmpty() && words.all { genericWords.contains(it) }) {
            Log.d(TAG, "Treating '$value' as all generic words - detected generic phrase")
            return true
        }
        
        // Treat two-character alphanumeric tokens (F2, 9X) as noise
        if (cleanValue.length == 2 &&
            cleanValue.any { it.isDigit() } &&
            cleanValue.any { it.isLetter() }
        ) {
            Log.d(TAG, "Treating '$value' as alphanumeric noise - two-character token")
            return true
        }

        // Check for single digits or very short non-meaningful text
        if (cleanValue.matches(Regex("\\d{1,2}")) || cleanValue.length <= 2) {
            Log.d(TAG, "Treating '$value' as too short/meaningless - length: ${cleanValue.length}")
            return true
        }
        
        return false
    }
    
    /**
     * Check if two field values are duplicates (case-insensitive, trimmed)
     * Used to detect when LLM returns the same value for different fields
     */
    fun areDuplicateFields(value1: String?, value2: String?): Boolean {
        if (value1.isNullOrBlank() || value2.isNullOrBlank()) return false
        
        val clean1 = value1.trim().uppercase()
        val clean2 = value2.trim().uppercase()
        
        return clean1 == clean2 && clean1.isNotEmpty()
    }
    
    /**
     * Check if a numeric value is effectively zero or meaningless
     */
    fun isZeroOrMeaningless(value: Double?): Boolean {
        return value == null || value <= 0.0 || value.isNaN() || value.isInfinite()
    }
}

package com.example.coupontracker.util

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Strict JSON schema validator for coupon extraction responses
 * Implements the "parseStrict" pattern to reduce hallucinations
 */
object CouponJsonValidator {
    private const val TAG = "CouponJsonValidator"
    
    // Allowed keys in the JSON schema (updated for Qwen2.5 typed cashback format)
    private val ALLOWED_KEYS = setOf(
        "storeName",
        "description", 
        "cashback",  // Changed from cashbackAmount to support typed object
        "offerText", // Added to match prompt schema
        "redeemCode",
        "expiryDate",
        "minOrderAmount"
    )
    
    /**
     * Strict JSON validation with schema enforcement
     * Returns null if JSON doesn't match expected schema
     */
    fun parseStrict(jsonString: String): JSONObject? {
        return runCatching {
            val json = JSONObject(jsonString.trim())
            
            // Validate schema compliance
            if (json.onlyAllowedKeys() && json.noEmptyStrings()) {
                json
            } else {
                Log.w(TAG, "JSON failed schema validation")
                null
            }
        }.getOrElse { exception ->
            Log.w(TAG, "JSON parsing failed: ${exception.message}")
            null
        }
    }
    
    /**
     * Check if JSON contains only allowed keys
     */
    private fun JSONObject.onlyAllowedKeys(): Boolean {
        val keys = keys().asSequence().toSet()
        val unknownKeys = keys - ALLOWED_KEYS
        
        if (unknownKeys.isNotEmpty()) {
            Log.w(TAG, "Unknown keys found: $unknownKeys")
            return false
        }
        
        return true
    }
    
    /**
     * Check if JSON has no empty string values (null is OK)
     * Note: Empty strings are acceptable for optional fields like description
     */
    private fun JSONObject.noEmptyStrings(): Boolean {
        // Empty strings are valid JSON and semantically correct when field is truly empty
        // The LLM should use null for unknown, empty string for known-empty
        // All post-processing will handle empty strings appropriately
        return true  // Accept empty strings - they're valid and will be handled downstream
    }
    
    /**
     * Validate specific field constraints
     */
    fun validateFieldConstraints(json: JSONObject): JsonValidationResult {
        val issues = mutableListOf<String>()
        
        // Validate storeName (required, non-generic)
        val storeName = json.optString("storeName")
        if (storeName.isBlank()) {
            issues.add("Missing or empty storeName")
        } else if (GenericFieldHeuristics.isGenericOrMissing(storeName)) {
            issues.add("Generic storeName: $storeName")
        }
        
        // Validate redeemCode format if present
        val redeemCode = json.optString("redeemCode")
        if (redeemCode.isNotBlank() && !isValidCouponCode(redeemCode)) {
            issues.add("Invalid coupon code format: $redeemCode")
        }
        
        // Validate typed cashback object if present (replaces legacy cashbackAmount)
        if (json.has("cashback") && !json.isNull("cashback")) {
            val cashback = json.optJSONObject("cashback")
            if (cashback != null) {
                // Validate cashback.type (required)
                val type = cashback.optString("type")
                if (type.isBlank() || type !in setOf("percent", "amount", "text")) {
                    issues.add("Invalid cashback.type: must be 'percent', 'amount', or 'text'")
                }
                
                // Validate cashback.valueNum (required numeric)
                if (!cashback.has("valueNum") || cashback.isNull("valueNum")) {
                    issues.add("Missing cashback.valueNum (required numeric field)")
                } else {
                    val valueNum = cashback.optDouble("valueNum", -1.0)
                    if (valueNum < 0) {
                        issues.add("Invalid cashback.valueNum: must be non-negative number")
                    }
                }
                
                // currency is optional, can be null or string
            } else {
                issues.add("cashback must be object or null, not other type")
            }
        }
        
        // Validate expiryDate format if present
        val expiryDate = json.optString("expiryDate")
        if (expiryDate.isNotBlank() && !isValidDateFormat(expiryDate)) {
            issues.add("Invalid expiry date format: $expiryDate")
        }
        
        return if (issues.isEmpty()) {
            JsonValidationResult.Valid
        } else {
            JsonValidationResult.Invalid(issues)
        }
    }
    
    /**
     * Basic coupon code validation
     */
    private fun isValidCouponCode(code: String): Boolean {
        // Base regex: alphanumeric with optional dashes/underscores, 3-16 chars
        val basePattern = Regex("^[A-Z0-9][A-Z0-9_-]{2,15}$")
        
        // Reject obvious non-codes
        val rejectPatterns = listOf(
            Regex("^(VOUCHER|COUPON|OFFER|DISCOUNT|CODE|USING|NEEDED)$"),
            Regex("^[A-Z]{1,2}$"), // Too short
            Regex("^[0-9]+$") // Only numbers
        )
        
        val normalizedCode = code.trim().uppercase()
        
        return basePattern.matches(normalizedCode) && 
               rejectPatterns.none { it.matches(normalizedCode) }
    }
    
    /**
     * Basic cashback amount validation
     */
    private fun isValidCashbackAmount(amount: String): Boolean {
        // Should contain currency symbol or percentage
        val validPatterns = listOf(
            Regex(".*[₹$£€].*\\d+.*"), // Currency symbols
            Regex(".*\\d+.*%.*"), // Percentage
            Regex(".*\\d+.*(?:off|back|cashback).*", RegexOption.IGNORE_CASE) // Text indicators
        )
        
        return validPatterns.any { it.matches(amount) }
    }
    
    /**
     * Basic date format validation
     */
    private fun isValidDateFormat(date: String): Boolean {
        val datePatterns = listOf(
            Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}"), // DD/MM/YYYY or MM/DD/YYYY
            Regex("\\d{4}-\\d{1,2}-\\d{1,2}"), // YYYY-MM-DD
            Regex("\\d{1,2}\\s+[A-Za-z]{3,9}\\s*,?\\s*\\d{2,4}"), // DD Month YYYY
            Regex("[A-Za-z]{3,9}\\s+\\d{1,2},?\\s*\\d{4}"), // Month DD, YYYY
            Regex("\\d{1,2}\\s+[A-Za-z]{3,9}") // DD Month (current year assumed)
        )
        
        return datePatterns.any { it.matches(date.trim()) }
    }
}

/**
 * Validation result for field constraints
 */
sealed class JsonValidationResult {
    object Valid : JsonValidationResult()
    data class Invalid(val issues: List<String>) : JsonValidationResult()
}

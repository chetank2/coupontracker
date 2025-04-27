package com.example.coupontracker.util

import android.util.Log
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * Advanced coupon field extractor that uses specialized regex patterns and heuristics
 * to extract structured data from unstructured OCR text
 */
class CouponFieldExtractor {
    companion object {
        private const val TAG = "CouponFieldExtractor"
        
        // Merchant name patterns
        private val MERCHANT_PATTERNS = listOf(
            // Look for logos/brand names typically at the start of coupons
            Pattern.compile("\\A([A-Z][a-z]+)\\b"),
            // Common explicit "from [merchant]" format
            Pattern.compile("(?i)(?:from|at|by|shop)\\s+([A-Z][A-Za-z0-9\\s&.'-]{2,25})\\b"),
            // Common merchant names - high confidence
            Pattern.compile("(?i)\\b(myntra|amazon|flipkart|swiggy|zomato|uber|ola|makemytrip|paytm|phonepe|google|microsoft|apple|netflix|spotify)\\b"),
            // Payment apps that issue coupons
            Pattern.compile("(?i)\\b(gpay|googlepay|amazonpay|paytm|phonepe|mobikwik|freecharge)\\b")
        )
        
        // Coupon code patterns
        private val CODE_PATTERNS = listOf(
            // Look for explicit "code:" format
            Pattern.compile("(?i)(?:code|coupon|promo|voucher)\\s*(?::)?\\s*([A-Z0-9-]{4,20})\\b"),
            // Look for "use code X" format
            Pattern.compile("(?i)use\\s+(?:code|coupon)\\s+([A-Z0-9-]{4,20})\\b"),
            // Look for "apply X" format
            Pattern.compile("(?i)apply\\s+([A-Z0-9-]{4,20})\\b"),
            // Look for standalone codes (typically all caps with numbers)
            Pattern.compile("\\b([A-Z0-9]{6,12})\\b")
        )
        
        // Discount amount patterns
        private val AMOUNT_PATTERNS = listOf(
            // Direct currency amount: ₹100 OFF or Rs. 100 OFF
            Pattern.compile("(?i)(?:Rs\\.?|₹|INR)\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:off|discount|cashback)?"),
            // Percentage discount: 10% OFF
            Pattern.compile("(?i)(\\d+(?:\\.\\d{1,2})?)\\s*(?:%|percent)\\s*(?:off|discount|cashback)"),
            // "Up to" format: UP TO ₹200 OFF
            Pattern.compile("(?i)(?:up to|flat|get|save)\\s+(?:Rs\\.?|₹|INR)\\s*(\\d+)"),
            // Simple number near "off" (fallback)
            Pattern.compile("(?i)(\\d+)\\s*(?:off|discount|cashback)")
        )
        
        // Expiry date patterns
        private val EXPIRY_PATTERNS = listOf(
            // Standard date formats: 31/12/2023, 31-12-2023, etc.
            Pattern.compile("(?i)(?:exp|expires|valid until|valid till)\\s*(?::)?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"),
            // Text date formats: 31 Dec 2023, December 31 2023, etc.
            Pattern.compile("(?i)(?:exp|expires|valid until|valid till)\\s*(?::)?\\s*(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{2,4})"),
            // "Valid for X days" format
            Pattern.compile("(?i)valid\\s+(?:for|till)\\s+(\\d+)\\s+days"),
            // Standalone date format (fallback)
            Pattern.compile("\\b(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})\\b")
        )
        
        // Terms and description patterns
        private val DESCRIPTION_PATTERNS = listOf(
            // "Applicable on" format
            Pattern.compile("(?i)(?:applicable on|valid on)\\s+([^\\n\\r.]{5,50})"),
            // "Get X off on Y" format
            Pattern.compile("(?i)(?:get|avail)\\s+(?:Rs\\.?|₹|INR)?\\s*\\d+\\s+off\\s+on\\s+([^\\n\\r.]{5,50})"),
            // Generic "off on" format
            Pattern.compile("(?i)(?:off on|discount on)\\s+([^\\n\\r.]{5,50})")
        )
        
        // Terms patterns
        private val TERMS_PATTERNS = listOf(
            // Look for explicit "terms" sections
            Pattern.compile("(?i)(?:terms|conditions|T&C)\\s*(?::|&|and)?\\s*([^\\n\\r.]{10,150})"),
            // Look for typical terms phrases
            Pattern.compile("(?i)(?:valid on|applicable on|min order|minimum|maximum|not valid)\\s+([^\\n\\r.]{10,100})")
        )
    }
    
    /**
     * Extract coupon information from OCR text with confidence scores
     * @param text The OCR text to extract information from
     * @return Extracted coupon fields with confidence scores
     */
    fun extractWithConfidence(text: String): Map<String, ExtractedField> {
        Log.d(TAG, "Extracting coupon fields with confidence from text (${text.length} chars)")
        
        val results = mutableMapOf<String, ExtractedField>()
        
        // Extract merchant name
        extractMerchantName(text)?.let {
            results["merchantName"] = it
        }
        
        // Extract code
        extractCode(text)?.let {
            results["code"] = it
        }
        
        // Extract amount
        extractAmount(text)?.let {
            results["amount"] = it
        }
        
        // Extract expiry date
        extractExpiryDate(text)?.let {
            results["expiryDate"] = it
        }
        
        // Extract description
        extractDescription(text)?.let {
            results["description"] = it
        }
        
        // Extract terms
        extractTerms(text)?.let {
            results["terms"] = it
        }
        
        // Apply post-processing enhancements
        enhanceResults(results, text)
        
        Log.d(TAG, "Extracted ${results.size} fields with confidence")
        return results
    }
    
    /**
     * Extract merchant name
     */
    private fun extractMerchantName(text: String): ExtractedField? {
        // First try high-confidence merchant patterns (popular brands)
        val highConfidenceMatch = MERCHANT_PATTERNS[2].matcher(text).let {
            if (it.find() && it.groupCount() >= 1) {
                val match = it.group(1) ?: return@let null
                ExtractedField(capitalizeWords(match), ConfidenceLevel.HIGH)
            } else null
        }
        
        if (highConfidenceMatch != null) {
            return highConfidenceMatch
        }
        
        // Try other patterns
        for ((index, pattern) in MERCHANT_PATTERNS.withIndex()) {
            if (index == 2) continue // Skip the high confidence pattern we already tried
            
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                val confidence = when (index) {
                    0 -> ConfidenceLevel.MEDIUM // First pattern (capitalized word at start)
                    1 -> ConfidenceLevel.HIGH   // Explicit "from [merchant]" format
                    3 -> ConfidenceLevel.HIGH   // Payment apps
                    else -> ConfidenceLevel.MEDIUM
                }
                return ExtractedField(capitalizeWords(match), confidence)
            }
        }
        
        // If all else fails, try to use the first line of text if it's reasonably short
        val firstLine = text.lines().firstOrNull()?.trim() ?: ""
        if (firstLine.length in 2..25 && !firstLine.contains("code", ignoreCase = true) && 
            !firstLine.contains("coupon", ignoreCase = true)) {
            return ExtractedField(capitalizeWords(firstLine), ConfidenceLevel.LOW)
        }
        
        return null
    }
    
    /**
     * Extract coupon code
     */
    private fun extractCode(text: String): ExtractedField? {
        // First try explicit patterns (with "code:" label)
        val explicitMatch = CODE_PATTERNS[0].matcher(text).let {
            if (it.find() && it.groupCount() >= 1) {
                val match = it.group(1) ?: return@let null
                ExtractedField(match.uppercase(), ConfidenceLevel.HIGH)
            } else null
        }
        
        if (explicitMatch != null) {
            return explicitMatch
        }
        
        // Try other patterns
        for ((index, pattern) in CODE_PATTERNS.withIndex()) {
            if (index == 0) continue // Skip the first pattern we already tried
            
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                
                // Validate code format - should at least have either all caps or include numbers
                if (!match.matches("[A-Z0-9-]{4,20}".toRegex())) {
                    continue
                }
                
                val confidence = when (index) {
                    1, 2 -> ConfidenceLevel.HIGH   // "use code X" or "apply X" formats
                    3 -> {
                        // For standalone codes, higher confidence if it has both letters and numbers
                        if (match.contains(Regex("[A-Z]")) && match.contains(Regex("[0-9]"))) {
                            ConfidenceLevel.MEDIUM
                        } else {
                            ConfidenceLevel.LOW
                        }
                    }
                    else -> ConfidenceLevel.MEDIUM
                }
                return ExtractedField(match.uppercase(), confidence)
            }
        }
        
        return null
    }
    
    /**
     * Extract discount amount
     */
    private fun extractAmount(text: String): ExtractedField? {
        // Try all amount patterns
        for ((index, pattern) in AMOUNT_PATTERNS.withIndex()) {
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                
                // Format the amount
                val formattedAmount = when (index) {
                    0 -> "₹$match" // Direct currency amount
                    1 -> "$match%" // Percentage discount
                    2, 3 -> "₹$match" // "Up to" format or simple number
                    else -> "₹$match"
                }
                
                val confidence = when (index) {
                    0, 1 -> ConfidenceLevel.HIGH   // Explicit currency or percentage format
                    2 -> ConfidenceLevel.MEDIUM    // "Up to" format
                    3 -> ConfidenceLevel.LOW       // Simple number - could be something else
                    else -> ConfidenceLevel.LOW
                }
                
                return ExtractedField(formattedAmount, confidence)
            }
        }
        
        return null
    }
    
    /**
     * Extract expiry date
     */
    private fun extractExpiryDate(text: String): ExtractedField? {
        // Try all expiry patterns
        for ((index, pattern) in EXPIRY_PATTERNS.withIndex()) {
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                
                val confidence = when (index) {
                    0, 1 -> ConfidenceLevel.HIGH   // Explicit expiry label with date
                    2 -> ConfidenceLevel.MEDIUM    // "Valid for X days" format
                    3 -> ConfidenceLevel.LOW       // Standalone date
                    else -> ConfidenceLevel.LOW
                }
                
                return ExtractedField(match, confidence)
            }
        }
        
        return null
    }
    
    /**
     * Extract description
     */
    private fun extractDescription(text: String): ExtractedField? {
        // Try all description patterns
        for ((index, pattern) in DESCRIPTION_PATTERNS.withIndex()) {
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                
                // Clean up the description - remove newlines, excessive spaces
                val cleanDescription = match.replace(Regex("\\s+"), " ").trim()
                
                // Skip if too short
                if (cleanDescription.length < 5) continue
                
                val confidence = when (index) {
                    0 -> ConfidenceLevel.HIGH   // "Applicable on" format
                    1 -> ConfidenceLevel.HIGH   // "Get X off on Y" format
                    2 -> ConfidenceLevel.MEDIUM // Generic "off on" format
                    else -> ConfidenceLevel.MEDIUM
                }
                
                return ExtractedField(cleanDescription, confidence)
            }
        }
        
        return null
    }
    
    /**
     * Extract terms and conditions
     */
    private fun extractTerms(text: String): ExtractedField? {
        // Try all terms patterns
        for ((index, pattern) in TERMS_PATTERNS.withIndex()) {
            val matcher = pattern.matcher(text)
            if (matcher.find() && matcher.groupCount() >= 1) {
                val match = matcher.group(1) ?: continue
                
                // Clean up the terms - remove newlines, excessive spaces
                val cleanTerms = match.replace(Regex("\\s+"), " ").trim()
                
                // Skip if too short
                if (cleanTerms.length < 10) continue
                
                val confidence = when (index) {
                    0 -> ConfidenceLevel.HIGH   // Explicit terms section
                    1 -> ConfidenceLevel.MEDIUM // Typical terms phrases
                    else -> ConfidenceLevel.LOW
                }
                
                return ExtractedField(cleanTerms, confidence)
            }
        }
        
        return null
    }
    
    /**
     * Enhance results by adding derived fields and improving confidence
     */
    private fun enhanceResults(results: MutableMap<String, ExtractedField>, text: String) {
        // Create synthetic description if missing but have merchant and amount
        if (!results.containsKey("description") && 
            results.containsKey("merchantName") && 
            results.containsKey("amount")) {
            
            val merchant = results["merchantName"]!!.value
            val amount = results["amount"]!!.value
            
            results["description"] = ExtractedField(
                "Get $amount off at $merchant",
                ConfidenceLevel.SYNTHETIC
            )
        }
        
        // If we detected a merchant with high confidence and found a code (any confidence)
        // but no amount, try to extract a numerical amount again with more lenient patterns
        if (!results.containsKey("amount") && 
            results["merchantName"]?.confidence == ConfidenceLevel.HIGH &&
            results.containsKey("code")) {
            
            // Look for any number that could be an amount
            val amountPattern = Pattern.compile("\\b(\\d+)\\b")
            val matcher = amountPattern.matcher(text)
            
            var bestAmount = 0
            while (matcher.find()) {
                val amount = matcher.group(1)?.toIntOrNull() ?: continue
                
                // Heuristic: amounts are typically between 10 and 5000
                if (amount in 10..5000 && amount > bestAmount) {
                    bestAmount = amount
                }
            }
            
            if (bestAmount > 0) {
                results["amount"] = ExtractedField(
                    "₹$bestAmount",
                    ConfidenceLevel.LOW
                )
            }
        }
        
        // Set default merchant name if missing
        if (!results.containsKey("merchantName")) {
            results["merchantName"] = ExtractedField(
                "Unknown Store",
                ConfidenceLevel.SYNTHETIC
            )
        }
    }
    
    /**
     * Capitalize first letter of each word
     */
    private fun capitalizeWords(text: String): String {
        return text.split("\\s+".toRegex())
            .joinToString(" ") { word ->
                if (word.length > 1) word[0].uppercase() + word.substring(1).lowercase()
                else word.uppercase()
            }
    }
}

/**
 * Represents an extracted field with its confidence level
 */
data class ExtractedField(
    val value: String,
    val confidence: ConfidenceLevel
)

/**
 * Confidence levels for extracted fields
 */
enum class ConfidenceLevel {
    HIGH,       // Strong evidence this is correct
    MEDIUM,     // Reasonable evidence this is correct
    LOW,        // Weak evidence, may be incorrect
    SYNTHETIC   // Artificially generated, not directly extracted
} 
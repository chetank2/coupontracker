package com.example.coupontracker.util

import android.util.Log
import com.example.coupontracker.extraction.quality.CouponFieldNoise

/**
 * Shared utility for detecting generic/boilerplate text that should be treated as missing data.
 * Used by both LocalLlmOcrService and ModelBasedOCRService to ensure consistent validation.
 */
object GenericFieldHeuristics {
    private const val TAG = "GenericFieldHeuristics"
    private val descriptionKeywords = setOf(
        "off", "discount", "cashback", "save", "flat", "upto", "offer", "deal",
        "free", "gift", "reward", "voucher", "bonus", "win", "won", "buy",
        "purchase", "order", "products"
    )
    private val monthTokens = setOf(
        "jan", "january", "feb", "february", "mar", "march", "apr", "april",
        "may", "jun", "june", "jul", "july", "aug", "august", "sep", "sept",
        "september", "oct", "october", "nov", "november", "dec", "december"
    )
    private val expiryOnlyTokens = setOf(
        "expires", "expire", "expiry", "valid", "till", "until", "ends", "end",
        "on", "in", "at", "am", "pm"
    )
    private val currencyRegex = Regex("[₹$€£¥]")
    private val ordinalDateFragmentRegex = Regex("""(?i)^\s*\d{1,2}(?:st|nd|rd|th)\s*$""")
    private val numericDateRegex = Regex("""^\s*\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?\s*$""")
    private val isoDateRegex = Regex("""^\s*\d{4}-\d{1,2}-\d{1,2}\s*$""")

    /**
     * Check if a field contains generic/boilerplate text that should be treated as missing
     */
    fun isGenericOrMissing(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        
        val genericWords = setOf(
            // Generic UI labels
            "voucher", "vouchers", "coupon", "coupons", "offer", "offers",
            "deal", "deals", "discount", "discounts", "cashback", "cashbacks",
            "promo", "promotion", "expires", "expired", "valid",
            "expiry", "hour", "hours", "hr", "hrs", "day", "days",
            "week", "weeks", "month", "months", "in",
            
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
        if (CouponFieldNoise.isExpiryBadgeOrFragment(cleanValue)) {
            Log.d(TAG, "Treating '$value' as generic/missing - expiry badge fragment")
            return true
        }
        
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
        
        // Check for single digits or very short non-meaningful text
        if (cleanValue.matches(Regex("\\d{1,2}")) || cleanValue.length <= 2) {
            Log.d(TAG, "Treating '$value' as too short/meaningless - length: ${cleanValue.length}")
            return true
        }
        return false
    }

    /**
     * Determine if a description contains enough concrete detail to be treated as meaningful.
     * Used for quality scoring so truncated or vague descriptions lose credit.
     */
    fun isMeaningfulDescription(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (isGenericOrMissing(value)) return false

        val trimmed = value.trim()
        val lower = trimmed.lowercase()
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val hasNumberToken = trimmed.any { it.isDigit() } ||
            trimmed.contains('%') ||
            currencyRegex.containsMatchIn(trimmed)
        val hasKeyword = descriptionKeywords.any { lower.contains(it) }
        val looksTruncated = trimmed.endsWith("...") || trimmed.endsWith("..") || trimmed.endsWith("-")
        val fetchFailurePhrases = listOf(
            "not fetched",
            "unable to fetch",
            "failed to fetch",
            "not available",
            "coming soon",
            "loading"
        )

        if (looksTruncated) {
            Log.d(TAG, "Treating '$value' as weak description - appears truncated")
            return false
        }

        if (fetchFailurePhrases.any { lower.contains(it) }) {
            Log.d(TAG, "Treating '$value' as weak description - fetch failure phrase detected")
            return false
        }

        if (looksLikeSavingsWithoutConcreteValue(lower, hasNumberToken)) {
            Log.d(TAG, "Treating '$value' as weak description - savings text has no concrete value")
            return false
        }

        if (looksLikeDateOnlyFragment(trimmed)) {
            Log.d(TAG, "Treating '$value' as weak description - date/expiry fragment")
            return false
        }

        if (words.size < 3 && !hasNumberToken) {
            Log.d(TAG, "Treating '$value' as weak description - too few words without numbers")
            return false
        }

        if (!hasKeyword && !hasNumberToken) {
            Log.d(TAG, "Treating '$value' as weak description - missing keywords or numbers")
            return false
        }

        return true
    }

    private fun looksLikeSavingsWithoutConcreteValue(lower: String, hasNumberToken: Boolean): Boolean {
        if (hasNumberToken) return false
        val hasSavingsClaim = Regex("""\b(off|discount|cashback|save|flat|upto|up to)\b""")
            .containsMatchIn(lower)
        if (!hasSavingsClaim) return false
        val hasNonNumericValue = Regex("""\b(free|gift|reward|voucher|membership)\b""")
            .containsMatchIn(lower)
        return !hasNonNumericValue
    }

    private fun looksLikeDateOnlyFragment(value: String): Boolean {
        val normalized = value
            .lowercase()
            .replace(",", " ")
            .replace(".", " ")
            .replace(":", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        if (ordinalDateFragmentRegex.matches(normalized)) return true
        if (numericDateRegex.matches(normalized) || isoDateRegex.matches(normalized)) return true

        val tokens = normalized
            .split(' ')
            .map { it.trim('*', '-', '/', '(', ')') }
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false

        val hasMonth = tokens.any { it in monthTokens }
        val hasDateNumber = tokens.any { token ->
            token.matches(Regex("""\d{1,4}(?:st|nd|rd|th)?"""))
        }
        if (!hasMonth || !hasDateNumber) return false

        return tokens.all { token ->
            token in monthTokens ||
                token in expiryOnlyTokens ||
                token.matches(Regex("""\d{1,4}(?:st|nd|rd|th)?"""))
        }
    }

    fun isGenericOrMissingCode(value: String?): Boolean {
        if (value.isNullOrBlank()) return true
        val cleanValue = value.trim().uppercase()
        if (cleanValue.length < 4) return true
        if (!cleanValue.matches(Regex("^[A-Z0-9_-]{4,}$"))) return true
        // Reject generic placeholders but allow hyphenated long codes
        if (isGenericOrMissing(value)) return true
        
        // Detect repeated single segment forms like NO_CODE_NEEDED or lorem words
        if (cleanValue.contains("NO_CODE") || cleanValue.contains("NEEDED")) {
            Log.d(TAG, "Treating '$value' as placeholder code")
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

    /**
     * Determine if a cashback/detail string contains meaningful savings information.
     */
    fun hasMeaningfulCashback(detail: String?): Boolean {
        if (detail.isNullOrBlank()) return false
        val normalized = detail.trim().lowercase()
        val hasDigit = normalized.any { it.isDigit() }
        val hasPercent = normalized.contains('%')
        val hasCurrencySymbol = currencyRegex.containsMatchIn(normalized)
        val hasKeyword = descriptionKeywords.any { normalized.contains(it) }
        val standalonePercent = normalized.matches(Regex("""\d{1,3}(?:\.\d+)?%"""))

        if (standalonePercent) return false
        if (hasCurrencySymbol && hasDigit) return true
        if (hasPercent && hasKeyword) return true
        if (hasDigit && hasKeyword) return true
        return hasKeyword && normalized.contains("cashback")
    }
}

package com.example.coupontracker.util

/**
 * Utility to normalize rupee-like currency tokens to a canonical format.
 * Converts variants such as "₹ 500", "Rs 500", or misrecognized "T500" into "₹500".
 */
object CurrencySanitizer {
    private val RUPEE_LIKE_PATTERN = Regex(
        pattern = "(?i)(?<![A-Za-z0-9₹$])(₹\\s*|rs\\.?\\s*|t\\s*)([0-9]+(?:[\\s,][0-9]+)*(?:\\.[0-9]+)?)",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Replace all rupee-like tokens in the provided [input] with a canonical `₹` representation.
     */
    fun sanitizeRupeeTokens(input: String?): String? {
        if (input.isNullOrEmpty()) return input

        return RUPEE_LIKE_PATTERN.replace(input) { matchResult ->
            val amount = matchResult.groupValues[2].replace("[\\s,]".toRegex(), "")
            "₹$amount"
        }
    }
}

package com.example.coupontracker.data.util

import java.util.Locale

/**
 * Utility helpers for deduplicating coupons based on their textual descriptions.
 */
object CouponDedupUtils {
    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /**
     * Normalizes a coupon description by lowercasing, stripping non-alphanumeric
     * characters, and collapsing repeated whitespace. This mirrors the logic
     * used by the deduplication query so it can be reused across migrations and
     * repository operations.
     */
    fun normalizeDescription(description: String): String {
        val lowercased = description.lowercase(Locale.US)
        val cleaned = NON_ALPHANUMERIC_REGEX.replace(lowercased, " ")
        return WHITESPACE_REGEX.replace(cleaned.trim(), " ")
    }
}

package com.example.coupontracker.util

import java.util.Locale

/**
 * Utilities to normalize coupon text and help with deduplication heuristics.
 */
object CouponDedupUtils {
    private val nonAlphaNumericRegex = Regex("[^a-z0-9]+")

    fun normalizeDescription(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val collapsed = nonAlphaNumericRegex
            .replace(description.lowercase(Locale.getDefault()), " ")
            .trim()
        if (collapsed.isBlank()) return null
        return collapsed.replace(Regex("\\s+"), " ")
    }

    fun shouldReplaceDescription(existing: String?, incoming: String?): Boolean {
        if (incoming.isNullOrBlank()) return false
        if (GenericFieldHeuristics.isGenericOrMissing(incoming)) return false
        if (existing.isNullOrBlank()) return true
        if (incoming.length > existing.length) return true
        return false
    }

    fun shouldReplaceTerms(existing: String?, incoming: String?): Boolean {
        if (incoming.isNullOrBlank()) return false
        if (existing.isNullOrBlank()) return true
        return incoming.length > existing.length
    }
}

package com.example.coupontracker.util

/**
 * Utility helpers for normalising coupon codes extracted from LLM or OCR sources.
 *
 * Codes frequently contain errant whitespace or trailing characters when parsed from
 * screenshots or raw text. The helpers below aggressively trim that noise so downstream
 * validation operates on a predictable, uppercase alphanumeric token.
 */
object CouponCodeSanitizer {

    private val whitespaceRegex = Regex("\\s+")

    /**
     * Normalise a raw coupon code string.
     *
     * Steps:
     * 1. Trim the value and split by whitespace so we can discard stray trailing
     *    one-character alphabetic fragments (e.g. "Q2SQ74 JS7CK O" -> "Q2SQ74 JS7CK").
     * 2. Collapse all whitespace, remove trailing non-alphanumeric characters and
     *    uppercase the value for consistent comparisons.
     */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val segments = trimmed.split(whitespaceRegex)
            .filter { it.isNotBlank() }
            .toMutableList()

        while (segments.size > 1 && segments.last().length == 1 && segments.last().all { it.isLetter() }) {
            segments.removeAt(segments.lastIndex)
        }

        val collapsed = segments.joinToString(separator = "")
        val whitespaceRemoved = collapsed.replace(whitespaceRegex, "")
        val cleaned = whitespaceRemoved.trimEnd { !it.isLetterOrDigit() }.uppercase()

        return cleaned.takeIf { it.isNotBlank() }
    }
}

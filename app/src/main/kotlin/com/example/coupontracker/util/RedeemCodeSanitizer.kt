package com.example.coupontracker.util

object RedeemCodeSanitizer {
    private val whitespaceRegex = "\\s+".toRegex()

    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) {
            return null
        }

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }

        val tokens = whitespaceRegex.split(trimmed).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            return null
        }

        val cleanedTokens = tokens.map { token ->
            token.trim { !it.isLetterOrDigit() }
        }

        val prunedTokens = cleanedTokens.dropLastWhile { token ->
            token.isBlank() || token.any { !it.isLetterOrDigit() } || token.length < 2
        }

        val baseTokens = if (prunedTokens.isNotEmpty()) prunedTokens else cleanedTokens

        val collapsed = baseTokens.joinToString(separator = "") { token ->
            token.filter { it.isLetterOrDigit() }
        }

        val sanitized = collapsed.trimEnd { !it.isLetterOrDigit() }
        val uppercase = sanitized.uppercase()

        return uppercase.takeIf { it.isNotBlank() }
    }
}

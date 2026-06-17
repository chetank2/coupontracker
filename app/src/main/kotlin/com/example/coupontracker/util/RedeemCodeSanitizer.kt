package com.example.coupontracker.util

import java.util.Locale

object RedeemCodeSanitizer {
    private val whitespaceRegex = "\\s+".toRegex()
    private val CTA_TOKENS = setOf(
        "COPY",
        "COPYCODE",
        "COPY-CODE",
        "COPYNOW",
        "APPLY",
        "APPLYNOW",
        "APPLYCODE",
        "USE",
        "USECODE",
        "USENOW",
        "TAPTOCOPY",
        "TODAY",
        "NOW",
        "CLICK",
        "CLICKHERE"
    )

    fun sanitize(raw: String?): String? {
        return sanitizeInternal(raw, preserveInterior = false)
    }

    fun sanitizePreserve(raw: String?): String? {
        return sanitizeInternal(raw, preserveInterior = true)
    }

    private fun sanitizeInternal(raw: String?, preserveInterior: Boolean): String? {
        if (raw.isNullOrBlank()) return null

        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val tokens = whitespaceRegex.split(trimmed).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null

        val cleanedTokens = tokens.map { token -> token.trim { !it.isLetterOrDigit() } }

        val prunedTokens = cleanedTokens
            .dropLastWhile { token ->
                val upper = token.uppercase(Locale.US)
                upper.isBlank() || CTA_TOKENS.contains(upper) || (!preserveInterior && token.any { !it.isLetterOrDigit() }) || token.length < 2
            }

        val baseTokens = if (prunedTokens.isNotEmpty()) prunedTokens else cleanedTokens

        val collapsed = baseTokens.joinToString(separator = "") { token ->
            if (preserveInterior) token.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            else token.filter { it.isLetterOrDigit() }
        }

        val sanitized = collapsed.trimEnd { !it.isLetterOrDigit() && it != '-' && it != '_' }
        val uppercase = sanitized.uppercase()
        return uppercase.takeIf { it.isNotBlank() }
    }
}

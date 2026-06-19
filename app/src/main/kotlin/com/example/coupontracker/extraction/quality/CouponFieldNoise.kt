package com.example.coupontracker.extraction.quality

import java.util.Locale

object CouponFieldNoise {
    private val expiryOnlyTokens = setOf(
        "expire",
        "expires",
        "expired",
        "expiry",
        "valid",
        "validity",
        "in",
        "hr",
        "hrs",
        "hour",
        "hours",
        "day",
        "days",
        "week",
        "weeks",
        "month",
        "months"
    )

    private val expiryBadgePattern = Regex(
        "(?i)^\\s*(?:expires?|expiry|valid(?:ity)?|valid\\s+for)?\\s*(?:in)?\\s*[0-9o]{1,3}\\s*(?:h(?:ou)?rs?|hours?|days?|weeks?|months?)\\s*$"
    )

    private val expiryFragmentPattern = Regex(
        "(?i)^\\s*(?:in\\s*)?[0-9o]{1,3}\\s*(?:x\\s*)?(?:h(?:ou)?rs?|hours?|days?|weeks?|months?)?\\s*$"
    )

    fun isExpiryUnitToken(value: String?): Boolean {
        val normalized = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return normalized in expiryOnlyTokens
    }

    fun isExpiryBadgeOrFragment(value: String?): Boolean {
        val normalized = value?.trim().orEmpty()
        if (normalized.isBlank()) return true
        val lower = normalized.lowercase(Locale.ROOT)
        val tokens = Regex("[a-z0-9]+")
            .findAll(lower)
            .map { it.value }
            .toList()
        val allTokensAreExpiryNoise = tokens.isNotEmpty() && tokens.all { token ->
                token in expiryOnlyTokens ||
                    token.all(Char::isDigit) ||
                    token.all { ch -> ch.isDigit() || ch == 'o' || ch == 'x' }
            }
        val hasExpiryNoiseAnchor = tokens.any { token ->
            token in expiryOnlyTokens || token.all { ch -> ch == 'o' || ch == 'x' }
        }
        if (allTokensAreExpiryNoise && hasExpiryNoiseAnchor) {
            return true
        }
        return expiryBadgePattern.matches(normalized) ||
            (containsExpiryBadgeLanguage(normalized) && expiryFragmentPattern.matches(normalized))
    }

    fun containsExpiryBadgeLanguage(value: String?): Boolean {
        val lower = value?.lowercase(Locale.ROOT).orEmpty()
        return Regex("\\b(?:expires?|expiry|valid(?:ity)?|valid\\s+for)\\b").containsMatchIn(lower) ||
            Regex("\\b\\d{1,3}\\s*(?:h(?:ou)?rs?|hours?|days?|weeks?|months?)\\b").containsMatchIn(lower)
    }
}

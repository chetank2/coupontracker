package com.example.coupontracker.util

import com.example.coupontracker.extraction.quality.CouponFieldNoise
import java.util.Locale

object StoreCandidateValidator {
    private val tokenRegex = Regex("[a-z0-9]+")
    private val noiseTokens = setOf(
        "am", "pm", "vit", "otp", "upi", "url", "www", "com", "app",
        "now", "new", "get", "off", "for", "the", "and", "use", "code",
        "cashback", "cred", "pay", "via", "expires", "expired", "valid",
        "expiry", "hour", "hours", "hr", "hrs", "day", "days", "week",
        "weeks", "month", "months", "in"
    )

    fun isAcceptable(candidate: String?, rawOcr: String? = null): Boolean {
        if (candidate.isNullOrBlank()) return false
        if (CouponFieldNoise.isExpiryBadgeOrFragment(candidate)) return false
        if (GenericFieldHeuristics.isGenericOrMissing(candidate)) return false

        val tokens = tokens(candidate)
        if (tokens.isEmpty()) return false
        val tokensForNoiseCheck = if (tokens.size > 1 && tokens.first() == "the") {
            tokens.drop(1)
        } else {
            tokens
        }
        if (tokensForNoiseCheck.any { it in noiseTokens }) return false

        val letterTokens = tokens.filter { token -> token.any(Char::isLetter) }
        if (letterTokens.isEmpty()) return false
        if (tokens.any { token -> token.any(Char::isLetter) && token.any(Char::isDigit) }) {
            return false
        }

        if (letterTokens.size > 1 && letterTokens.any { it.length <= 2 }) {
            return false
        }

        if (letterTokens.size == 1) {
            val token = letterTokens.first()
            val raw = candidate.trim()
            val isAllCapsBrand = raw.filter { it.isLetterOrDigit() }.all { !it.isLetter() || it.isUpperCase() }
            if (token.length < 3) return false
            if (token.length == 3 && !isAllCapsBrand && !looksLikeTitleCaseBrand(raw)) {
                return false
            }
        }

        if (!rawOcr.isNullOrBlank() && !OcrEvidenceValidator.isPhraseSupported(candidate, rawOcr)) {
            return false
        }

        return true
    }

    private fun looksLikeTitleCaseBrand(value: String): Boolean {
        val letters = value.filter { it.isLetter() }
        return letters.firstOrNull()?.isUpperCase() == true &&
            letters.drop(1).all { it.isLowerCase() }
    }

    private fun tokens(value: String): List<String> {
        return tokenRegex.findAll(value.lowercase(Locale.ROOT))
            .map { it.value }
            .toList()
    }
}

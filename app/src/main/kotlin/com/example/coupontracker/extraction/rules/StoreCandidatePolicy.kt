package com.example.coupontracker.extraction.rules

import com.example.coupontracker.util.StoreCandidateValidator
import java.util.Locale
import java.util.regex.Pattern

internal class StoreCandidatePolicy {
    fun cleanCandidate(raw: String?, fullText: String? = null): String? {
        val initial = raw?.trim()?.takeIf { it.length >= 3 } ?: return null
        val normalizedInitial = stripNumericCounterArtifact(initial, fullText)
        val tokens = normalizedInitial.split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .toMutableList()
        if (tokens.isEmpty()) {
            return null
        }

        while (tokens.isNotEmpty() &&
            GENERIC_LEADING_STORE_TOKENS.contains(tokens.first().lowercase(Locale.ROOT))
        ) {
            tokens.removeAt(0)
        }

        if (tokens.isEmpty()) {
            return null
        }

        val builder = mutableListOf<String>()
        for (token in tokens) {
            val trimmedToken = token.trim().trimEnd(',', '.', ';', ':')
            if (trimmedToken.isEmpty()) continue

            val leadingLetter = trimmedToken.firstOrNull { it.isLetter() }
            if (builder.isNotEmpty() && leadingLetter != null && leadingLetter.isLowerCase()) {
                break
            }

            builder.add(trimmedToken)
        }

        while (builder.isNotEmpty() && GENERIC_TRAILING_TOKENS.contains(builder.last().uppercase(Locale.ROOT))) {
            builder.removeAt(builder.lastIndex)
        }

        val cleaned = builder.joinToString(" ").trim()
        if (cleaned.length < 3) {
            return null
        }

        if (!cleaned.any { it.isLetter() }) {
            return null
        }

        val lower = cleaned.lowercase(Locale.ROOT)
        val allCapsAcronym = cleaned
            .filter { it.isLetterOrDigit() }
            .let { compact ->
                compact.length in 3..8 &&
                    compact.any(Char::isLetter) &&
                    compact.all { !it.isLetter() || it.isUpperCase() }
            }

        if (!allCapsAcronym && !lower.any { it in "aeiouy" }) {
            return null
        }

        val trailingConsonants = lower.takeLastWhile { it.isLetter() && it !in "aeiouy" }
        if (!allCapsAcronym && trailingConsonants.length >= 3) {
            return null
        }

        return cleaned
    }

    fun isAcceptedStoreCandidate(candidate: String, fullText: String): Boolean {
        val normalized = candidate.lowercase(Locale.ROOT)
        return normalized !in COMMON_WORDS && StoreCandidateValidator.isAcceptable(candidate, fullText)
    }

    private fun stripNumericCounterArtifact(candidate: String, fullText: String?): String {
        val matcher = STORE_COUNTER_ARTIFACT_PATTERN.matcher(candidate)
        if (!matcher.matches()) {
            return candidate
        }

        val prefix = matcher.group(1) ?: return candidate
        val suffix = matcher.group(2) ?: return candidate
        val hasDecimalCounter = suffix.contains('.') || suffix.contains(',')
        val prefixAppearsStandalone = fullText
            ?.let {
                Pattern.compile("\\b${Pattern.quote(prefix)}\\b", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                    .matcher(it)
                    .find()
            }
            ?: false

        return if (hasDecimalCounter || prefixAppearsStandalone) prefix else candidate
    }

    private companion object {
        private val COMMON_WORDS = setOf(
            "the", "and", "for", "with", "off", "use", "get", "code", "coupon",
            "offer", "valid", "till", "from", "upto", "free", "save", "discount",
            "multi", "product", "products", "kit", "combo", "pack", "value", "special",
            "now", "today", "details", "redeem", "claim", "activate", "shop", "buy",
            "view", "apply", "tap", "click", "pastm", "patm", "just", "expires",
            "expired", "cashback"
        )

        private val GENERIC_LEADING_STORE_TOKENS = setOf(
            "details", "detail", "coupon", "coupons", "voucher", "vouchers",
            "offer", "offers", "store", "brand", "merchant", "shop"
        )

        private val GENERIC_TRAILING_TOKENS = setOf(
            "ANNUAL",
            "PLAN",
            "PLANS",
            "OFFER",
            "OFFERS",
            "SALE",
            "DEAL",
            "DEALS",
            "REWARDS",
            "PROGRAM",
            "MEMBERSHIP",
            "SUBSCRIPTION",
            "CARD"
        )

        private val STORE_COUNTER_ARTIFACT_PATTERN =
            Pattern.compile("^([\\p{L}\\p{M}]{3,})(\\d+(?:[.,]\\d+)?)$", Pattern.UNICODE_CASE)
    }
}

package com.example.coupontracker.extraction.quality

import com.example.coupontracker.util.GenericFieldHeuristics
import java.util.Locale

object OfferTextQuality {
    private val offerIntentPattern = Regex(
        "(?i)\\b(buy|save|get|flat|up\\s*to|upto|off|cashback|discount|voucher|free|products?|orders?|above|minimum|min\\.?|at\\s+just|for\\s+(?:rs\\.?|₹)?\\s*\\d)\\b"
    )
    private val dateNoisePattern = Regex(
        "(?i)\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b|\\b(?:am|pm)\\b|\\b20\\d{2}\\b|\\bexpires?\\b|\\bvalid\\b"
    )
    private val contextNoisePattern = Regex(
        "(?i)\\b(about|scratch\\s+card|received|website|app|details|terms|copy|paytm|home|back|close)\\b"
    )
    private val weakTailPattern = Regex("(?i)\\b(?:o|vo|yo|5g|pm|am)\\b")

    fun isLikelyOfferText(value: String?): Boolean {
        if (!GenericFieldHeuristics.isMeaningfulDescription(value)) return false
        if (CouponFieldNoise.isExpiryBadgeOrFragment(value)) return false
        return score(value) >= 4
    }

    fun isLikelyDateOrContextNoise(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return true
        if (CouponFieldNoise.isExpiryBadgeOrFragment(text)) return true
        val lower = text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val hasOfferIntent = offerIntentPattern.containsMatchIn(text)
        val hasDateNoise = dateNoisePattern.containsMatchIn(text)
        val contextHits = contextNoisePattern.findAll(text).count()
        val weakTailHits = weakTailPattern.findAll(text).count()
        return !hasOfferIntent && (
            hasDateNoise ||
                contextHits >= 2 ||
                weakTailHits >= 2 ||
                (words.size <= 4 && contextHits >= 1)
            )
    }

    fun score(value: String?): Int {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return Int.MIN_VALUE
        if (CouponFieldNoise.isExpiryBadgeOrFragment(text)) return Int.MIN_VALUE
        var score = 0
        val lower = text.lowercase(Locale.ROOT)
        if (offerIntentPattern.containsMatchIn(text)) score += 4
        if (text.any { it.isDigit() }) score += 2
        if (text.contains('₹') || lower.contains("rs") || lower.contains("inr")) score += 2
        if (text.contains('%')) score += 2
        if (Regex("(?i)\\b(?:buy|get|save|flat|off|cashback|discount|free)\\b").containsMatchIn(text)) score += 2
        if (Regex("(?i)\\b(?:products?|orders?|above|website|app)\\b").containsMatchIn(text)) score += 1
        if (dateNoisePattern.containsMatchIn(text)) score -= 3
        score -= contextNoisePattern.findAll(text).count()
        score -= weakTailPattern.findAll(text).count()
        if (text.endsWith("...") || text.endsWith("..")) score -= 4
        return score
    }
}

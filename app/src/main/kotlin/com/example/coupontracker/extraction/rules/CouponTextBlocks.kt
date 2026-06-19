package com.example.coupontracker.extraction.rules

import java.util.regex.Pattern

object CouponTextBlocks {
    fun normalizeKey(value: String): String {
        return value.lowercase()
            .replace("[^\\p{L}\\p{N}]+".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun isCodeLine(line: String): Boolean {
        return Pattern.compile("(?i)\\b(?:code|coupon\\s+code|promo\\s+code)\\b\\s*[:\\-–—]?", Pattern.UNICODE_CASE)
            .matcher(line)
            .find()
    }

    fun isExpiryLine(line: String): Boolean {
        return Pattern.compile(
            "(?i)\\bexpires?\\s+in\\s+\\d+\\s+(?:days?|hours?)\\b|\\bexpires?\\b|\\bvalid\\s+(?:till|until|through)\\b",
            Pattern.UNICODE_CASE
        ).matcher(line).find()
    }

    fun isActionLine(line: String): Boolean {
        return Pattern.compile("(?i)^(details|offer\\s+details|redeem|redeem\\s+now|copy|copy\\s+code|tap\\s+to\\s+copy|view\\s+details)$")
            .matcher(line.trim())
            .find()
    }

    fun isChromeLine(line: String): Boolean {
        val normalized = normalizeKey(line)
        if (normalized in setOf("vouchers", "active", "lifetime")) return true
        if (isPhoneStatusBarNoise(line)) return true
        return Pattern.compile("(?i)\\b(vouchers?|active|lifetime)\\b.*\\d")
            .matcher(line)
            .find()
    }

    fun prepareFieldText(text: String): String {
        return trimLeadingPreviousCouponTail(text).lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot(::isPhoneStatusBarNoise)
            .joinToString("\n")
    }

    fun looksLikeSelectedCardOfferLine(line: String): Boolean {
        return Pattern.compile(
            "(?i)\\b(you\\s+won|get|save|flat|off|cashback|discount|bonus|reward|products?|membership|voucher)\\b|₹|rs\\.?",
            Pattern.UNICODE_CASE
        ).matcher(line).find()
    }

    fun isPhoneStatusBarNoise(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        val normalized = trimmed.replace("\\s+".toRegex(), " ")
        val lower = normalized.lowercase()
        if (Regex("^\\d{1,2}:\\d{2}$").matches(normalized)) return true
        if (Regex("(?i)^(?:yo|vo|volte)?\\s*5g\\s*\\d{0,3}%?\\s*[a-z]?$").matches(normalized)) return true
        if (Regex("(?i)\\b(?:vo|yo|volte)\\s*5g\\b").containsMatchIn(normalized) && normalized.length <= 16) return true
        if (Regex("^\\d{1,3}%$").matches(normalized)) return true
        if (lower in setOf("5g", "4g", "lte", "volte", "vo 5g", "yo 5g")) return true
        return false
    }

    fun trimLeadingPreviousCouponTail(text: String): String {
        val lines = text.lines().map { it.trim() }
        val meaningfulLines = lines.filter { it.isNotBlank() }
        if (meaningfulLines.size < 4) return text

        val firstExpiryIndex = meaningfulLines.indexOfFirst(::isExpiryLine)
        if (firstExpiryIndex <= 0) return text

        val hasPreviousCode = meaningfulLines.take(firstExpiryIndex).any(::isCodeLine)
        val hasSelectedOffer = meaningfulLines.drop(firstExpiryIndex + 1).take(8).any(::looksLikeSelectedCardOfferLine)
        if (!hasPreviousCode || !hasSelectedOffer) return text

        return meaningfulLines.drop(firstExpiryIndex).joinToString("\n")
    }
}

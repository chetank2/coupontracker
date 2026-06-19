package com.example.coupontracker.extraction.rules

import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.RedeemCodeSanitizer
import java.util.Locale
import java.util.regex.Pattern

object CouponCodeExtractor {
    fun extract(text: String): String? {
        val splitCodeLinePattern = Pattern.compile(
            "(?im)^\\s*code\\s*[-–—:]?\\s*$\\R\\s*([A-Za-z0-9][A-Za-z0-9_-]{4,})\\b"
        )
        val splitCodeLineMatcher = splitCodeLinePattern.matcher(text)
        if (splitCodeLineMatcher.find()) {
            val code = splitCodeLineMatcher.group(1)
            RedeemCodeSanitizer.sanitizePreserve(code)?.let { sanitized ->
                if (sanitized.length in 5..40) {
                    return sanitized
                }
            }
        }

        val codeLinePattern = Pattern.compile("(?im)^\\s*code\\s*[-–—:]?\\s*(.+)$")
        val codeLineMatcher = codeLinePattern.matcher(text)
        if (codeLineMatcher.find()) {
            val code = codeLineMatcher.group(1)
            RedeemCodeSanitizer.sanitizePreserve(code)?.let { sanitized ->
                if (sanitized.length in 5..40) {
                    return sanitized
                }
            }
        }

        val codePattern = Pattern.compile("(?i)code\\s*[-–—:]?\\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)")
        val codeMatcher = codePattern.matcher(text)
        if (codeMatcher.find()) {
            RedeemCodeSanitizer.sanitizePreserve(codeMatcher.group(1))?.let { return it }
        }

        val codeWithoutColonPattern = Pattern.compile("(?i)\\bcode\\b\\s*[-–—:]?\\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)")
        val codeWithoutColonMatcher = codeWithoutColonPattern.matcher(text)
        if (codeWithoutColonMatcher.find()) {
            RedeemCodeSanitizer.sanitizePreserve(codeWithoutColonMatcher.group(1))?.let { return it }
        }

        val allCapsDigitsPattern = Pattern.compile("\\b([A-Z0-9]{6,})\\b")
        val allCapsDigitsMatcher = allCapsDigitsPattern.matcher(text)
        while (allCapsDigitsMatcher.find()) {
            val potentialCode = allCapsDigitsMatcher.group(1)
            val codeLength = potentialCode?.length ?: 0
            val looksLikeCode = potentialCode?.any(Char::isDigit) == true || codeLength >= 8
            if (codeLength in 6..40 && looksLikeCode && !COMMON_WORDS.contains(potentialCode?.lowercase(Locale.ROOT) ?: "")) {
                RedeemCodeSanitizer.sanitize(potentialCode)
                    ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
                    ?.let { return it }
            }
        }

        val codeIndicators = listOf("use", "apply", "redeem", "coupon", "promocode", "promo code")
        for (indicator in codeIndicators) {
            val indicatorIndex = text.indexOf(indicator, ignoreCase = true)
            if (indicatorIndex != -1) {
                val afterIndicator = text.substring(indicatorIndex + indicator.length).trim()
                val potentialCode = afterIndicator.split(Pattern.compile("\\s+"))[0]

                if (potentialCode.length >= 5 && potentialCode.matches("[A-Za-z0-9]+".toRegex())) {
                    RedeemCodeSanitizer.sanitize(potentialCode)?.let { return it }
                }
            }
        }

        val lines = text.lines()
        for (index in lines.indices) {
            val line = lines[index]
            if (!line.contains("code", ignoreCase = true)) continue
            extractAtLine(lines, index)?.let { return it }
        }

        return null
    }

    fun findInLines(lines: List<String>, start: Int, endInclusive: Int): String? {
        if (lines.isEmpty()) return null
        val safeStart = start.coerceIn(lines.indices)
        val safeEnd = endInclusive.coerceIn(lines.indices)
        if (safeStart > safeEnd) return null
        for (index in safeStart..safeEnd) {
            if (!lines[index].contains("code", ignoreCase = true)) continue
            extractAtLine(lines, index)?.let { return it }
        }
        return null
    }

    fun extractAtLine(lines: List<String>, index: Int): String? {
        val line = lines.getOrNull(index) ?: return null
        val inline = Regex("(?i)\\bcode\\s*[:\\-–—]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{4,})\\b")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
        val candidate = inline ?: lines.getOrNull(index + 1)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull { token -> token.length >= 5 && token.all { it.isLetterOrDigit() } }
        return RedeemCodeSanitizer.sanitize(candidate)
            ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
    }

    private val COMMON_WORDS = setOf(
        "SAVE",
        "OFFER",
        "DISCOUNT",
        "COUPON",
        "REDEEM",
        "DETAILS",
        "EXPIRES",
        "VALID",
        "CASHBACK"
    ).map { it.lowercase(Locale.ROOT) }.toSet()
}

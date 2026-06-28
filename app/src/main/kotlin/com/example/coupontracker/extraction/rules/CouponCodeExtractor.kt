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
                if (sanitized.length in 5..40 && !GenericFieldHeuristics.isGenericOrMissingCode(sanitized)) {
                    return sanitized
                }
            }
        }

        val codeLinePattern = Pattern.compile("(?im)^\\s*code\\s*[-–—:]?\\s*(.+)$")
        val codeLineMatcher = codeLinePattern.matcher(text)
        while (codeLineMatcher.find()) {
            if (isNoCodeLine(codeLineMatcher.group(0).orEmpty())) continue
            val code = codeLineMatcher.group(1)
            RedeemCodeSanitizer.sanitizePreserve(code)?.let { sanitized ->
                if (sanitized.length in 5..40 && !GenericFieldHeuristics.isGenericOrMissingCode(sanitized)) {
                    return sanitized
                }
            }
        }

        val codePattern = Pattern.compile("(?i)code\\s*[-–—:]?\\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)")
        val codeMatcher = codePattern.matcher(text)
        while (codeMatcher.find()) {
            val prefix = text.substring(0, codeMatcher.start()).takeLast(16)
            if (isNoCodeLine(prefix + codeMatcher.group(0).orEmpty())) continue
            RedeemCodeSanitizer.sanitizePreserve(codeMatcher.group(1))
                ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
                ?.let { return it }
        }

        val codeWithoutColonPattern = Pattern.compile("(?i)\\bcode\\b\\s*[-–—:]?\\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)")
        val codeWithoutColonMatcher = codeWithoutColonPattern.matcher(text)
        while (codeWithoutColonMatcher.find()) {
            val prefix = text.substring(0, codeWithoutColonMatcher.start()).takeLast(16)
            if (isNoCodeLine(prefix + codeWithoutColonMatcher.group(0).orEmpty())) continue
            RedeemCodeSanitizer.sanitizePreserve(codeWithoutColonMatcher.group(1))
                ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
                ?.let { return it }
        }

        val allCapsDigitsPattern = Pattern.compile("\\b([A-Z0-9]{6,})\\b")
        val allCapsDigitsMatcher = allCapsDigitsPattern.matcher(text)
        val fallbackCodes = mutableListOf<String>()
        while (allCapsDigitsMatcher.find()) {
            if (isNoCodeLine(lineContaining(text, allCapsDigitsMatcher.start(), allCapsDigitsMatcher.end()))) {
                continue
            }
            val potentialCode = allCapsDigitsMatcher.group(1)
            val codeLength = potentialCode?.length ?: 0
            val looksLikeCode = potentialCode?.any(Char::isDigit) == true
            if (codeLength in 6..40 && looksLikeCode && !COMMON_WORDS.contains(potentialCode?.lowercase(Locale.ROOT) ?: "")) {
                RedeemCodeSanitizer.sanitize(potentialCode)
                    ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
                    ?.let { fallbackCodes += it }
            }
        }
        fallbackCodes.maxByOrNull { it.length }?.let { return it }

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
        if (isNoCodeLine(line)) return null
        val inline = Regex("(?i)\\bcode\\s*[:\\-–—]?\\s*([A-Za-z0-9][A-Za-z0-9_-]{4,})\\b")
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
        val candidate = inline ?: lines.getOrNull(index + 1)
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull { token -> token.length >= 5 && token.all { it.isLetterOrDigit() } }
        return RedeemCodeSanitizer.sanitizePreserve(candidate)
            ?.takeUnless(GenericFieldHeuristics::isGenericOrMissingCode)
    }

    private fun isNoCodeLine(line: String): Boolean {
        return Regex("(?i)\\bno\\s+code(?:\\s+needed|required)?\\b").containsMatchIn(line)
    }

    private fun lineContaining(text: String, start: Int, end: Int): String {
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(safeStart, text.length)
        val previousIndex = (safeStart - 1).coerceAtLeast(0)
        val lineStart = text.lastIndexOf('\n', previousIndex).let { if (it < 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', safeEnd).let { if (it < 0) text.length else it }
        return text.substring(lineStart, lineEnd)
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

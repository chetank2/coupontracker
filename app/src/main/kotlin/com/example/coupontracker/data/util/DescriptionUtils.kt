package com.example.coupontracker.data.util

import com.example.coupontracker.util.IndianCurrencyParser
import java.util.Locale

object DescriptionUtils {
    const val NEEDS_REVIEW_DESCRIPTION = "Needs review: offer details not extracted"

    private val helperTextPatterns = listOf(
        Regex("(?i)copy exact wording"),
        Regex("(?i)return null if none"),
        Regex("(?i)preserve provenance"),
        Regex("(?i)never promote cta"),
        Regex("(?i)output only the keys"),
        Regex("(?i)storeName must not be null")
    )
    private val descriptionPlaceholders = setOf(
        "coupon offer",
        "no description",
        "extracted via llm",
        "multi coupon detected",
        "scanned from qr code",
        "needs review offer details not extracted"
    )

    private val savingsPrefixRegex = Regex("^(cashback|discount|savings)\\s*:", RegexOption.IGNORE_CASE)
    private val standalonePercentRegex = Regex("""^\d{1,3}(?:\.\d+)?%$""")
    private val savingsContextRegex = Regex("""\b(cashback|discount|savings?|off|save|flat|upto|up\s*to)\b""", RegexOption.IGNORE_CASE)
    private val savingsWordRegex = Regex("""\b(cashback|discount|savings?)\b""", RegexOption.IGNORE_CASE)
    private val numericValueRegex = Regex("""\d+(?:[.,]\d+)?""")
    private val ocrStopWords = setOf(
        "code", "copy", "order", "now", "offer", "expires", "expiry", "valid", "about", "terms", "tnc"
    )

    fun appendDetails(base: String, vararg details: String?): String {
        val lines = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        fun normalizeKey(text: String): String {
            return text.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9%₹]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun addLine(raw: String?) {
            val sanitized = sanitizeLine(raw) ?: return
            val key = normalizeKey(sanitized)
            if (key.isNotEmpty() && seenKeys.add(key)) {
                lines += sanitized
            }
        }

        base.lines().forEach { addLine(it) }
        details.forEach { detail ->
            detail?.lines()?.forEach { addLine(it) }
        }

        return if (lines.isEmpty()) NEEDS_REVIEW_DESCRIPTION else lines.joinToString(separator = "\n")
    }

    private fun sanitizeLine(raw: String?): String? {
        val initial = raw?.trim() ?: return null
        if (initial.isEmpty()) return null
        if (helperTextPatterns.any { it.containsMatchIn(initial) }) {
            return null
        }

        val cleaned = initial.trimEnd('.', ' ').replace(Regex("\\s+"), " ")
        if (isPlaceholderDescription(cleaned)) return null
        if (cleaned.equals("copy exact wording", ignoreCase = true)) return null
        if (standalonePercentRegex.matches(cleaned)) return null
        if (isZeroSavingsLine(cleaned)) return null
        return cleaned.takeIf { it.isNotEmpty() }
    }

    fun formatCashbackDetail(
        amountValue: Double?,
        type: String? = null,
        currency: String? = null
    ): String? {
        val amount = amountValue ?: return null
        if (amount <= 0.0) return null

        val normalizedType = type?.lowercase(Locale.ROOT)
        return when (normalizedType) {
            "percent", "percentage" -> "Cashback: ${amount.toInt()}% off"
            "text" -> null
            else -> {
                val symbol = CurrencyUtils.resolveSymbol(currency)
                "Cashback: ${symbol}${amount.toInt()} off"
            }
        }
    }

    fun formatCashbackDetail(rawText: String?): String? {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val normalized = text.lowercase(Locale.ROOT)
        val label = determineSavingsLabel(normalized)
        if (standalonePercentRegex.matches(normalized)) {
            return null
        }
        if (isZeroSavingsLine(text)) {
            return null
        }

        if (normalized.contains('%') && savingsContextRegex.containsMatchIn(normalized)) {
            return "$label: $text"
        }

        if (normalized.contains("cashback", ignoreCase = true)) {
            val trimmed = text.trim()
            return if (trimmed.startsWith("cashback", ignoreCase = true)) {
                // Normalize casing of the Cashback prefix
                trimmed.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            } else {
                "$label: $trimmed"
            }
        }

        val detectedSymbol = CurrencyUtils.detectSymbol(text)
        if (detectedSymbol != null) {
            val parsedAmount = IndianCurrencyParser.parseAmount(text)
            if (parsedAmount != null && parsedAmount > 0) {
                val symbol = CurrencyUtils.resolveSymbol(detectedSymbol)
                return "$label: ${symbol}${parsedAmount.toInt()} off"
            }
        }

        val digitsOnly = normalized.matches(Regex("₹?\\d+"))
        if (digitsOnly) {
            val amountDigits = text.filter { it.isDigit() }
            val symbol = CurrencyUtils.resolveSymbol(null)
            return "$label: ${symbol}${amountDigits} off"
        }

        return "$label: $text".trim()
    }

    fun extractCashbackLine(description: String): String? {
        return description
            .lines()
            .map { it.trim() }
            .firstOrNull { line ->
                savingsPrefixRegex.containsMatchIn(line) && !isZeroSavingsLine(line)
            }
    }

    fun formatDisplayDescription(
        description: String,
        storeName: String? = null,
        redeemCode: String? = null
    ): String {
        val raw = description.trim()
        if (raw.isEmpty()) return NEEDS_REVIEW_DESCRIPTION

        val lines = raw
            .lines()
            .mapNotNull(::sanitizeDisplayLine)
            .filterNot { line ->
                val normalized = normalizeToken(line)
                normalized == "tm" ||
                    normalized == normalizeToken(redeemCode.orEmpty()) ||
                    normalized == normalizeToken(storeName.orEmpty())
            }

        if (lines.isEmpty()) return NEEDS_REVIEW_DESCRIPTION

        val meaningfulLines = lines.filterNot { isDisplayMetadataLine(it, storeName, redeemCode) }
        val multilineNoise = raw.lines().count { it.isNotBlank() } >= 6 ||
            meaningfulLines.count { it.length <= 12 } >= 4

        if (!multilineNoise) {
            return meaningfulLines
                .ifEmpty { lines }
                .joinToString(separator = "\n")
                .ifBlank { NEEDS_REVIEW_DESCRIPTION }
        }

        val offerTokens = collectOfferTokens(lines, storeName, redeemCode)
        val displayText = offerTokens
            .joinToString(separator = " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""(?i)\boff\*"""), "off")
            .replace(Regex("""(?i)\b(cashback|discount|savings)\s*:\s*"""), "")
            .trim(' ', '.', ',', '-')

        return displayText.takeIf { it.isNotBlank() }
            ?: meaningfulLines.firstOrNull()
            ?: NEEDS_REVIEW_DESCRIPTION
    }

    private fun collectOfferTokens(
        lines: List<String>,
        storeName: String?,
        redeemCode: String?
    ): List<String> {
        val startIndex = lines.indexOfFirst { line ->
            Regex("""(?i)\b(get|save|flat|upto|up\s*to|\d+%|₹\s*\d|rs\.?\s*\d)\b""").containsMatchIn(line)
        }.takeIf { it >= 0 } ?: 0

        val tokens = mutableListOf<String>()
        for (line in lines.drop(startIndex)) {
            if (isDisplayMetadataLine(line, storeName, redeemCode)) {
                val normalized = normalizeToken(line)
                if (normalized == "code" || normalized == normalizeToken(redeemCode.orEmpty())) break
                if (normalized in setOf("order", "now", "copy", "offer", "expires", "about")) break
                continue
            }
            tokens += line
        }
        return tokens
    }

    private fun sanitizeDisplayLine(raw: String): String? {
        val initial = raw.trim()
        if (initial.isEmpty()) return null
        if (helperTextPatterns.any { it.containsMatchIn(initial) }) return null
        val cleaned = initial
            .trimEnd('.', ' ')
            .replace(Regex("\\s+"), " ")
            .replace(Regex("""^[•*\-]+\s*"""), "")
            .trim()
        if (isPlaceholderDescription(cleaned)) return null
        if (isZeroSavingsLine(cleaned)) return null
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private fun isPlaceholderDescription(text: String): Boolean {
        return normalizeToken(text) in descriptionPlaceholders
    }

    private fun isZeroSavingsLine(text: String): Boolean {
        if (!savingsWordRegex.containsMatchIn(text)) return false

        val numbers = numericValueRegex.findAll(text)
            .mapNotNull { match -> match.value.replace(",", ".").toDoubleOrNull() }
            .toList()

        return numbers.isNotEmpty() && numbers.all { it == 0.0 }
    }

    private fun isDisplayMetadataLine(
        line: String,
        storeName: String?,
        redeemCode: String?
    ): Boolean {
        val normalized = normalizeToken(line)
        if (normalized.isEmpty()) return true
        if (normalized == normalizeToken(redeemCode.orEmpty())) return true
        if (normalized == normalizeToken(storeName.orEmpty())) return true
        if (normalized in ocrStopWords) return true
        if (normalized.startsWith("code ")) return true
        if (normalized.startsWith("expires ")) return true
        if (normalized.startsWith("valid ")) return true
        if (normalized.startsWith("about ")) return true
        return false
    }

    private fun normalizeToken(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun determineSavingsLabel(@Suppress("UNUSED_PARAMETER") normalized: String): String {
        return "Cashback"
    }
}

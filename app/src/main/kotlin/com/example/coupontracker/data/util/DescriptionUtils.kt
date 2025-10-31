package com.example.coupontracker.data.util

import com.example.coupontracker.util.IndianCurrencyParser
import java.util.Locale

object DescriptionUtils {

    private val helperTextPatterns = listOf(
        Regex("(?i)copy exact wording"),
        Regex("(?i)return null if none"),
        Regex("(?i)preserve provenance"),
        Regex("(?i)never promote cta"),
        Regex("(?i)output only the keys"),
        Regex("(?i)storeName must not be null")
    )

    private val savingsPrefixRegex = Regex("^(cashback|discount|savings)\\s*:", RegexOption.IGNORE_CASE)

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

        return if (lines.isEmpty()) {
            "Coupon offer"
        } else {
            lines.joinToString(separator = "\n")
        }
    }

    private fun sanitizeLine(raw: String?): String? {
        val initial = raw?.trim() ?: return null
        if (initial.isEmpty()) return null
        if (helperTextPatterns.any { it.containsMatchIn(initial) }) {
            return null
        }

        val cleaned = initial.trimEnd('.', ' ').replace(Regex("\\s+"), " ")
        if (cleaned.equals("copy exact wording", ignoreCase = true)) return null
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
        if (normalized.matches(Regex("\\d+%"))) {
            return "$label: ${text.uppercase(Locale.ROOT)} off"
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
            .firstOrNull { line -> savingsPrefixRegex.containsMatchIn(line) }
    }

    private fun determineSavingsLabel(normalized: String): String {
        return when {
            normalized.contains("cashback") || normalized.contains("cash back") -> "Cashback"
            normalized.contains("discount") || normalized.contains("off") || normalized.contains("save") -> "Discount"
            else -> "Savings"
        }
    }
}

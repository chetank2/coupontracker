package com.example.coupontracker.data.util

import com.example.coupontracker.util.IndianCurrencyParser
import java.util.Locale

object DescriptionUtils {

    fun appendDetails(base: String, vararg details: String?): String {
        val lines = mutableListOf<String>()
        val baseTrimmed = base.trim()
        if (baseTrimmed.isNotEmpty()) {
            lines += baseTrimmed
        }
        details.mapNotNull { it?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .forEach { detail ->
                if (detail !in lines) {
                    lines += detail
                }
            }
        return if (lines.isEmpty()) {
            "Coupon offer"
        } else {
            lines.joinToString(separator = "\n")
        }
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
        if (normalized.matches(Regex("\\d+%"))) {
            return "Cashback: ${text.uppercase(Locale.ROOT)} off"
        }

        val detectedSymbol = CurrencyUtils.detectSymbol(text)
        if (detectedSymbol != null) {
            val parsedAmount = IndianCurrencyParser.parseAmount(text)
            if (parsedAmount != null && parsedAmount > 0) {
                return formatCashbackDetail(parsedAmount, "amount", detectedSymbol)
            }
        }

        val digitsOnly = normalized.matches(Regex("₹?\\d+"))
        if (digitsOnly) {
            val amountDigits = text.filter { it.isDigit() }
            val symbol = CurrencyUtils.resolveSymbol(null)
            return "Cashback: ${symbol}${amountDigits} off"
        }

        if (normalized.contains("cashback", ignoreCase = true)) {
            return text.trim()
        }

        return "Cashback: $text".trim()
    }

    fun extractCashbackLine(description: String): String? {
        return description
            .lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Cashback:", ignoreCase = true) }
    }

}

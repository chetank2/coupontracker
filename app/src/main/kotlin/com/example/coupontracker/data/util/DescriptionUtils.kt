package com.example.coupontracker.data.util

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
            else -> "Cashback: ${currencySymbol(currency)}${amount.toInt()} off"
        }
    }

    fun formatCashbackDetail(rawText: String?): String? {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null
        val normalized = text.lowercase(Locale.ROOT)
        val formatted = when {
            normalized.matches(Regex("\\d+%")) -> "Cashback: ${text.uppercase(Locale.ROOT)} off"
            normalized.matches(Regex("₹?\\d+")) -> "Cashback: ₹${text.filter { it.isDigit() }} off"
            normalized.contains("cashback", ignoreCase = true) -> text
            else -> "Cashback: $text"
        }
        return formatted.trim()
    }

    fun extractCashbackLine(description: String): String? {
        return description
            .lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Cashback:", ignoreCase = true) }
    }

    private fun currencySymbol(currency: String?): String {
        return when (currency?.uppercase(Locale.ROOT)) {
            null, "", "INR", "₹", "RS", "RS." -> "₹"
            "USD", "$" -> "$"
            "EUR", "€" -> "€"
            "GBP", "£" -> "£"
            else -> "${currency.uppercase(Locale.ROOT)} "
        }
    }
}

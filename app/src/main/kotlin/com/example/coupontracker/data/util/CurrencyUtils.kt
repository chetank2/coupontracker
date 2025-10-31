package com.example.coupontracker.data.util

import java.util.Locale

/**
 * Utility helpers for working with currency tokens inside extracted coupon text.
 *
 * The extractor historically normalised everything to INR which caused offers
 * detected in USD/EUR to be displayed as ₹ amounts. These helpers retain the
 * detected symbol/code so the UI and persistence layers can surface the
 * original currency without guessing.
 */
object CurrencyUtils {

    private val CURRENCY_SYMBOLS = setOf("₹", "$", "€", "£")

    private val CODE_TO_SYMBOL = mapOf(
        "₹" to "₹",
        "INR" to "₹",
        "RS" to "₹",
        "RS." to "₹",
        "US$" to "$",
        "USD" to "$",
        "$" to "$",
        "EUR" to "€",
        "€" to "€",
        "GBP" to "£",
        "£" to "£"
    )

    private val CODE_REGEX = Regex("\\b(USD|EUR|GBP|INR|RS\\.?)\\b", RegexOption.IGNORE_CASE)

    /**
     * Resolve a human friendly currency symbol for display.
     */
    fun resolveSymbol(currency: String?): String {
        val trimmed = currency?.trim()?.takeIf { it.isNotEmpty() } ?: return "₹"
        CODE_TO_SYMBOL[trimmed.uppercase(Locale.ROOT)]?.let { return it }
        if (trimmed.length == 1 && CURRENCY_SYMBOLS.contains(trimmed)) {
            return trimmed
        }
        return trimmed
    }

    /**
     * Detect a currency symbol (₹, $, €, £) or common ISO/alias codes from raw text.
     * Returns the resolved display symbol when a match is found.
     */
    fun detectSymbol(raw: String?): String? {
        if (raw.isNullOrBlank()) return null

        CURRENCY_SYMBOLS.firstOrNull { raw.contains(it) }?.let { return it }

        if (raw.contains("US$", ignoreCase = true)) {
            return "$"
        }

        val codeMatch = CODE_REGEX.find(raw)
        return codeMatch?.let { resolveSymbol(it.value) }
    }
}


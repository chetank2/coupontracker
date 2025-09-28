package com.example.coupontracker.util

import java.util.Locale
import java.util.regex.Pattern

/**
 * Shared merchant catalog used by OCR components to normalize detected store names.
 */
object MerchantCatalog {
    private val POPULAR_MERCHANTS = listOf(
        "Myntra",
        "Amazon",
        "Flipkart",
        "Swiggy",
        "Zomato",
        "Uber",
        "Ola",
        "MakeMyTrip",
        "Paytm",
        "PhonePe",
        "Google",
        "Microsoft",
        "Apple",
        "Netflix",
        "Spotify"
    )

    private val MERCHANT_LOOKUP = POPULAR_MERCHANTS.associateBy { it.lowercase(Locale.ROOT) }

    val popularMerchantPattern: String by lazy {
        POPULAR_MERCHANTS.joinToString("|") { Pattern.quote(it) }
    }

    fun findBestMatch(candidate: String): String? {
        val normalized = candidate.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null

        MERCHANT_LOOKUP[normalized]?.let { return it }

        val tokens = normalized.split(Regex("\\s+"))
        for (token in tokens) {
            MERCHANT_LOOKUP[token]?.let { return it }
        }

        return null
    }
}

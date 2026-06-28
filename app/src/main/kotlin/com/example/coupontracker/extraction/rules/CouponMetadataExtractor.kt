package com.example.coupontracker.extraction.rules

import com.example.coupontracker.util.PlatformDetector
import java.util.Locale
import java.util.regex.Pattern

data class CouponMetadata(
    val category: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val discountType: String? = null,
    val minimumPurchase: Double? = null,
    val maximumDiscount: Double? = null,
    val paymentMethod: String? = null,
    val platformType: String? = null,
    val usageLimit: Int? = null
)

object CouponMetadataExtractor {
    fun extract(
        text: String,
        cashbackDetail: String?,
        logDebug: (String) -> Unit = {},
        logError: (String, Throwable) -> Unit = { _, _ -> }
    ): CouponMetadata {
        return CouponMetadata(
            category = extractCategory(text, logDebug),
            rating = extractRating(text, logDebug),
            status = extractStatus(text, logDebug),
            discountType = CouponAmountExtractor.inferDiscountType(cashbackDetail),
            minimumPurchase = CouponAmountExtractor.extractMinimumPurchase(text, logDebug, logError),
            maximumDiscount = CouponAmountExtractor.extractMaximumDiscount(text, logDebug, logError),
            paymentMethod = extractPaymentMethod(text, logDebug),
            platformType = extractPlatformType(text),
            usageLimit = extractUsageLimit(text, logDebug, logError)
        )
    }

    fun extractCategory(text: String, logDebug: (String) -> Unit = {}): String? {
        val lowerText = text.lowercase(Locale.ROOT)

        for (category in CATEGORIES) {
            if (lowerText.contains(category.lowercase(Locale.ROOT))) {
                logDebug("Found category from text: $category")
                return category
            }
        }

        for ((category, keywords) in CATEGORY_KEYWORDS) {
            if (keywords.any { lowerText.contains(it) }) {
                logDebug("Found category from keyword: $category")
                return category
            }
        }

        return null
    }

    fun extractRating(text: String, logDebug: (String) -> Unit = {}): String? {
        val ratingPattern = Pattern.compile("(?i)Rating:\\s*(.+?)(?=\\n|$)")
        val ratingMatcher = ratingPattern.matcher(text)
        if (ratingMatcher.find()) {
            val rating = ratingMatcher.group(1)?.trim()
            logDebug("Found rating from 'Rating:' pattern: $rating")
            return rating
        }

        val starRatingPattern = Pattern.compile("(⭐\\s*\\d+\\.\\d+)")
        val starRatingMatcher = starRatingPattern.matcher(text)
        if (starRatingMatcher.find()) {
            val rating = starRatingMatcher.group(1)
            logDebug("Found rating from star pattern: $rating")
            return rating
        }

        val numericRatingPattern = Pattern.compile("(\\d+\\.\\d+)\\s*/\\s*5")
        val numericRatingMatcher = numericRatingPattern.matcher(text)
        if (numericRatingMatcher.find()) {
            val rating = numericRatingMatcher.group(1)
            logDebug("Found rating from numeric pattern: $rating")
            return rating
        }

        return null
    }

    fun extractStatus(text: String, logDebug: (String) -> Unit = {}): String? {
        val statusPattern = Pattern.compile("(?i)Status:\\s*(.+?)(?=\\n|$)")
        val statusMatcher = statusPattern.matcher(text)
        if (statusMatcher.find()) {
            val status = statusMatcher.group(1)?.trim()
            logDebug("Found status from 'Status:' pattern: $status")
            return status
        }

        if (text.contains("Available to Redeem", ignoreCase = true)) {
            logDebug("Found 'Available to Redeem' status")
            return "Available to Redeem"
        }

        if (text.contains("Redeemed", ignoreCase = true)) {
            logDebug("Found 'Redeemed' status")
            return "Redeemed"
        }

        if (text.contains("Expired", ignoreCase = true)) {
            logDebug("Found 'Expired' status")
            return "Expired"
        }

        return null
    }

    fun extractPaymentMethod(text: String, logDebug: (String) -> Unit = {}): String? {
        val patterns = listOf(
            Pattern.compile("(?i)valid\\s+(?:only)?\\s+on\\s+(\\w+(?:\\s+\\w+)?)\\s+(?:payments|cards)"),
            Pattern.compile("(?i)(\\w+(?:\\s+\\w+)?)\\s+(?:payments|cards)\\s+only"),
            Pattern.compile("(?i)pay\\s+using\\s+(\\w+(?:\\s+\\w+)?)")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val method = matcher.group(1)
                logDebug("Found payment method: $method")
                return method
            }
        }

        val paymentMethods = listOf(
            "UPI", "Credit Card", "Debit Card", "Net Banking", "Wallet"
        )

        for (method in paymentMethods) {
            if (text.contains(method, ignoreCase = true)) {
                logDebug("Found payment method from common list: $method")
                return method
            }
        }

        return null
    }

    fun extractPlatformType(text: String): String? {
        return PlatformDetector.detectPlatformFromText(text)
    }

    fun extractUsageLimit(
        text: String,
        logDebug: (String) -> Unit = {},
        logError: (String, Throwable) -> Unit = { _, _ -> }
    ): Int? {
        val patterns = listOf(
            Pattern.compile("(?i)(?:can be used|valid)\\s+(\\d+)\\s+times?"),
            Pattern.compile("(?i)(\\d+)\\s+uses?\\s+(?:per|only)"),
            Pattern.compile("(?i)limit\\s+(\\d+)\\s+uses?")
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val limit = matcher.group(1)?.toIntOrNull()
                    if (limit != null) {
                        logDebug("Found usage limit: $limit")
                        return limit
                    }
                } catch (e: Exception) {
                    logError("Error parsing usage limit", e)
                }
            }
        }

        if (text.contains("single use", ignoreCase = true) ||
            text.contains("one time", ignoreCase = true) ||
            text.contains("once per", ignoreCase = true)
        ) {
            logDebug("Found single use limit")
            return 1
        }

        return null
    }

    private val CATEGORIES = listOf(
        "Food", "Travel", "Shopping", "Electronics", "Fashion", "Beauty",
        "Health", "Entertainment", "Education", "Services"
    )

    private val CATEGORY_KEYWORDS = listOf(
        "Fashion" to listOf(
            "apparel", "clothing", "shirt", "shirts", "t-shirt", "tshirts", "t shirts",
            "polo", "jeans", "dress", "kurta", "footwear", "shoes", "sneakers"
        ),
        "Travel" to listOf(
            "bus", "flight", "flights", "hotel", "hotels", "train", "cab", "ride",
            "ticket", "tickets", "booking", "trip"
        ),
        "Food" to listOf(
            "food", "restaurant", "restaurants", "meal", "pizza", "burger", "dining",
            "grocery", "groceries"
        ),
        "Electronics" to listOf(
            "electronics", "mobile", "phone", "laptop", "headphones", "earbuds",
            "speaker", "charger"
        ),
        "Beauty" to listOf(
            "beauty", "salon", "skin", "skincare", "makeup", "grooming", "hair"
        ),
        "Entertainment" to listOf(
            "movie", "movies", "ott", "stream", "streaming", "subscription", "concert"
        )
    )
}

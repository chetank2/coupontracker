package com.example.coupontracker.extraction.rules

import android.util.Log
import java.io.Serializable
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class to hold extracted coupon information
 */
data class CouponInfo(
    val storeName: String = "",
    val description: String = "",
    val expiryDate: Date? = null,
    val cashbackDetail: String? = null,
    val redeemCode: String? = null,
    val category: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val discountType: String? = null,

    // Provenance metadata
    val needsAttention: Boolean = false,
    val storeNameSource: String? = null,
    val storeNameEvidence: List<String> = emptyList(),

    // New fields
    val minimumPurchase: Double? = null,
    val maximumDiscount: Double? = null,
    val paymentMethod: String? = null,
    val platformType: String? = null,
    val usageLimit: Int? = null
) : Serializable {
    /**
     * Check if this coupon has enough valid information to be useful
     */
    fun isValid(): Boolean {
        // Must have a merchant name
        if (storeName.isBlank() || storeName == com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE) {
            return false
        }

        // Must have either a code or a descriptive body
        if (redeemCode.isNullOrBlank() && description.isBlank()) {
            return false
        }

        return true
    }

    override fun toString(): String {
        return "CouponInfo(storeName='$storeName', description='$description', " +
               "expiryDate=$expiryDate, cashbackDetail=$cashbackDetail, " +
               "redeemCode=$redeemCode, category=$category, discountType=$discountType, " +
               "rating=$rating, status=$status, minimumPurchase=$minimumPurchase, " +
               "maximumDiscount=$maximumDiscount, paymentMethod=$paymentMethod, " +
               "platformType=$platformType, usageLimit=$usageLimit)"
    }
}

/**
 * Utility class to extract coupon information from text
 */
class TextExtractor {
    private val TAG = "TextExtractor"
    private val infoExtractor = TextCouponInfoExtractor(
        logDebug = { message -> safeLogDebug(TAG) { message } },
        logError = { message, throwable -> safeLogError(TAG, message, throwable) }
    )

    private inline fun safeLogDebug(tag: String, message: () -> String) {
        try {
            Log.d(tag, message())
        } catch (_: Throwable) {
            // Ignore logging failures in unit tests
        }
    }

    private fun safeLogError(tag: String, message: String, throwable: Throwable) {
        try {
            Log.e(tag, message, throwable)
        } catch (_: Throwable) {
            // Ignore logging failures in unit tests
        }
    }

    /**
     * Extract all coupon information from text
     * @param text The text to extract information from
     * @param baseDate The base date to use for relative expiry calculations (defaults to current time)
     * @return CouponInfo object containing extracted information
     */
    suspend fun extractCouponInfo(text: String, baseDate: Date? = null): CouponInfo = withContext(Dispatchers.Default) {
        extractCouponInfoSync(text, baseDate)
    }

    /**
     * Synchronous version of extractCouponInfo
     * @param text The text to extract information from
     * @param baseDate The base date to use for relative expiry calculations (defaults to current time)
     * @return CouponInfo object containing extracted information
     */
    fun extractCouponInfoSync(text: String, baseDate: Date? = null): CouponInfo {
        return infoExtractor.extractCouponInfoSync(text, baseDate)
    }

    fun extractCouponBlockForStore(text: String, storeName: String): String? {
        return infoExtractor.extractCouponBlockForStore(text, storeName)
    }

    /**
     * Extract store name from text
     * @param text The text to extract from
     * @return The extracted store name or null if not found
     */
    fun extractStoreName(text: String): String? {
        return infoExtractor.extractStoreName(text)
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        return infoExtractor.extractDescription(text)
    }

    /**
     * Extract expiry date from text
     * @param text The text to extract from
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The extracted expiry date or null if not found
     */
    fun extractExpiryDate(text: String, baseDate: Date? = null): Date? {
        return infoExtractor.extractExpiryDate(text, baseDate)
    }

    /**
     * Parse expiry date from text
     * @param text The text to parse
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The parsed date or null if not found
     */
    fun parseExpiryDate(text: String, baseDate: Date? = null): Date? {
        return infoExtractor.parseExpiryDate(text, baseDate)
    }

    /**
     * Extract cashback amount from text
     * @param text The text to extract from
     * @return The extracted cashback amount or null if not found
     */
    fun extractCashbackDetail(text: String): String? {
        return infoExtractor.extractCashbackDetail(text)
    }

    /**
     * Extract redeem code from text
     * @param text The text to extract from
     * @return The extracted redeem code or null if not found
     */
    fun extractRedeemCode(text: String): String? {
        return infoExtractor.extractRedeemCode(text)
    }

    /**
     * Extract category from text
     * @param text The text to extract from
     * @return The extracted category or null if not found
     */
    fun extractCategory(text: String): String? {
        return infoExtractor.extractCategory(text)
    }

    /**
     * Extract rating from text
     * @param text The text to extract from
     * @return The extracted rating or null if not found
     */
    fun extractRating(text: String): String? {
        return infoExtractor.extractRating(text)
    }

    /**
     * Extract status from text
     * @param text The text to extract from
     * @return The extracted status or null if not found
     */
    fun extractStatus(text: String): String? {
        return infoExtractor.extractStatus(text)
    }

    /**
     * Extract minimum purchase amount from text
     * @param text The text to extract from
     * @return The extracted minimum purchase amount or null if not found
     */
    fun extractMinimumPurchase(text: String): Double? {
        return infoExtractor.extractMinimumPurchase(text)
    }

    /**
     * Extract maximum discount amount from text
     * @param text The text to extract from
     * @return The extracted maximum discount amount or null if not found
     */
    fun extractMaximumDiscount(text: String): Double? {
        return infoExtractor.extractMaximumDiscount(text)
    }

    /**
     * Extract payment method from text
     * @param text The text to extract from
     * @return The extracted payment method or null if not found
     */
    fun extractPaymentMethod(text: String): String? {
        return infoExtractor.extractPaymentMethod(text)
    }

    /**
     * Extract platform type from text
     * @param text The text to extract from
     * @return The extracted platform type or null if not found
     */
    fun extractPlatformType(text: String): String? {
        return infoExtractor.extractPlatformType(text)
    }

    /**
     * Extract usage limit from text
     * @param text The text to extract from
     * @return The extracted usage limit or null if not found
     */
    fun extractUsageLimit(text: String): Int? {
        return infoExtractor.extractUsageLimit(text)
    }

}

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
    private val storeNameExtractor = StoreNameExtractor { message ->
        safeLogDebug(TAG) { message }
    }
    private val descriptionExtractor = CouponDescriptionExtractor { message ->
        safeLogDebug(TAG) { message }
    }

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
        safeLogDebug(TAG) { "Extracting coupon info from text: ${text.take(100)}..." }

        val extractionText = prepareFieldExtractionText(text)
        val storeName = extractStoreName(extractionText) ?: extractStoreName(text)
        val scopedText = storeName
            ?.let { extractCouponBlockForStore(extractionText, it) }
            ?: extractionText
        val expiryDate = extractExpiryDate(scopedText, baseDate)
            ?: extractExpiryDate(extractionText, baseDate)
            ?: extractExpiryDate(text, baseDate)
        val cashbackDetail = extractCashbackDetail(scopedText)
        val redeemCode = RedeemCodeResolver.resolve(
            text = text,
            extractionText = extractionText,
            scopedText = scopedText,
            storeName = storeName
        )
        val description = extractDescription(scopedText, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractDescription(extractionText, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractDescription(text, storeName, redeemCode, text)?.takeIf { it.isNotBlank() }
            ?: extractRawOfferDescription(extractionText, redeemCode)
            ?: extractRawOfferDescription(text, redeemCode)
        val metadata = CouponMetadataExtractor.extract(
            text = scopedText,
            cashbackDetail = cashbackDetail,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )

        val result = CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackDetail = cashbackDetail,
            redeemCode = redeemCode,
            category = metadata.category,
            rating = metadata.rating,
            status = metadata.status,
            discountType = metadata.discountType,
            minimumPurchase = metadata.minimumPurchase,
            maximumDiscount = metadata.maximumDiscount,
            paymentMethod = metadata.paymentMethod,
            platformType = metadata.platformType,
            usageLimit = metadata.usageLimit
        )

        safeLogDebug(TAG) { "Extracted coupon info: $result" }
        return result
    }

    fun extractCouponBlockForStore(text: String, storeName: String): String? {
        return CouponBlockSelector.selectForStore(text, storeName)
    }

    private fun prepareFieldExtractionText(text: String): String {
        return CouponTextBlocks.prepareFieldText(text)
    }

    /**
     * Extract store name from text
     * @param text The text to extract from
     * @return The extracted store name or null if not found
     */
    fun extractStoreName(text: String): String? {
        return storeNameExtractor.extract(text)
    }

    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        return extractDescription(text, storeName = null, redeemCode = null, sourceText = text)
    }

    private fun extractDescription(
        text: String,
        storeName: String?,
        redeemCode: String?,
        sourceText: String? = text
    ): String? {
        return descriptionExtractor.extract(
            text = text,
            storeName = storeName,
            redeemCode = redeemCode,
            sourceText = sourceText
        )
    }

    private fun extractRawOfferDescription(text: String, redeemCode: String?): String? {
        return descriptionExtractor.extractRawOfferDescription(text, redeemCode)
    }

    /**
     * Extract expiry date from text
     * @param text The text to extract from
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The extracted expiry date or null if not found
     */
    fun extractExpiryDate(text: String, baseDate: Date? = null): Date? {
        return ExpiryDateExtractor.extract(
            text = text,
            baseDate = baseDate,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Parse expiry date from text
     * @param text The text to parse
     * @param baseDate The base date to use for relative calculations (defaults to current time)
     * @return The parsed date or null if not found
     */
    fun parseExpiryDate(text: String, baseDate: Date? = null): Date? {
        return ExpiryDateExtractor.parse(
            text = text,
            baseDate = baseDate,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract cashback amount from text
     * @param text The text to extract from
     * @return The extracted cashback amount or null if not found
     */
    fun extractCashbackDetail(text: String): String? {
        return CouponAmountExtractor.extractCashbackDetail(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract redeem code from text
     * @param text The text to extract from
     * @return The extracted redeem code or null if not found
     */
    fun extractRedeemCode(text: String): String? {
        return CouponCodeExtractor.extract(text)
    }

    /**
     * Extract category from text
     * @param text The text to extract from
     * @return The extracted category or null if not found
     */
    fun extractCategory(text: String): String? {
        return CouponMetadataExtractor.extractCategory(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract rating from text
     * @param text The text to extract from
     * @return The extracted rating or null if not found
     */
    fun extractRating(text: String): String? {
        return CouponMetadataExtractor.extractRating(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract status from text
     * @param text The text to extract from
     * @return The extracted status or null if not found
     */
    fun extractStatus(text: String): String? {
        return CouponMetadataExtractor.extractStatus(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract minimum purchase amount from text
     * @param text The text to extract from
     * @return The extracted minimum purchase amount or null if not found
     */
    fun extractMinimumPurchase(text: String): Double? {
        return CouponAmountExtractor.extractMinimumPurchase(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract maximum discount amount from text
     * @param text The text to extract from
     * @return The extracted maximum discount amount or null if not found
     */
    fun extractMaximumDiscount(text: String): Double? {
        return CouponAmountExtractor.extractMaximumDiscount(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

    /**
     * Extract payment method from text
     * @param text The text to extract from
     * @return The extracted payment method or null if not found
     */
    fun extractPaymentMethod(text: String): String? {
        return CouponMetadataExtractor.extractPaymentMethod(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } }
        )
    }

    /**
     * Extract platform type from text
     * @param text The text to extract from
     * @return The extracted platform type or null if not found
     */
    fun extractPlatformType(text: String): String? {
        return CouponMetadataExtractor.extractPlatformType(text)
    }

    /**
     * Extract usage limit from text
     * @param text The text to extract from
     * @return The extracted usage limit or null if not found
     */
    fun extractUsageLimit(text: String): Int? {
        return CouponMetadataExtractor.extractUsageLimit(
            text = text,
            logDebug = { message -> safeLogDebug(TAG) { message } },
            logError = { message, throwable -> safeLogError(TAG, message, throwable) }
        )
    }

}

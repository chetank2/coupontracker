package com.example.coupontracker.extraction.rules

import java.util.Date

internal class TextCouponInfoExtractor(
    private val logDebug: (String) -> Unit = {},
    private val logError: (String, Throwable) -> Unit = { _, _ -> }
) {
    private val storeNameExtractor = StoreNameExtractor(logDebug)
    private val descriptionExtractor = CouponDescriptionExtractor(logDebug)

    fun extractCouponInfoSync(text: String, baseDate: Date? = null): CouponInfo {
        logDebug("Extracting coupon info from text: ${text.take(100)}...")

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
            logDebug = logDebug,
            logError = logError
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

        logDebug("Extracted coupon info: $result")
        return result
    }

    fun extractCouponBlockForStore(text: String, storeName: String): String? {
        return CouponBlockSelector.selectForStore(text, storeName)
    }

    fun extractStoreName(text: String): String? {
        return storeNameExtractor.extract(text)
    }

    fun extractDescription(text: String): String? {
        return extractDescription(text, storeName = null, redeemCode = null, sourceText = text)
    }

    fun extractExpiryDate(text: String, baseDate: Date? = null): Date? {
        return ExpiryDateExtractor.extract(
            text = text,
            baseDate = baseDate,
            logDebug = logDebug,
            logError = logError
        )
    }

    fun parseExpiryDate(text: String, baseDate: Date? = null): Date? {
        return ExpiryDateExtractor.parse(
            text = text,
            baseDate = baseDate,
            logDebug = logDebug,
            logError = logError
        )
    }

    fun extractCashbackDetail(text: String): String? {
        return CouponAmountExtractor.extractCashbackDetail(
            text = text,
            logDebug = logDebug,
            logError = logError
        )
    }

    fun extractRedeemCode(text: String): String? {
        return CouponCodeExtractor.extract(text)
    }

    fun extractCategory(text: String): String? {
        return CouponMetadataExtractor.extractCategory(
            text = text,
            logDebug = logDebug
        )
    }

    fun extractRating(text: String): String? {
        return CouponMetadataExtractor.extractRating(
            text = text,
            logDebug = logDebug
        )
    }

    fun extractStatus(text: String): String? {
        return CouponMetadataExtractor.extractStatus(
            text = text,
            logDebug = logDebug
        )
    }

    fun extractMinimumPurchase(text: String): Double? {
        return CouponAmountExtractor.extractMinimumPurchase(
            text = text,
            logDebug = logDebug,
            logError = logError
        )
    }

    fun extractMaximumDiscount(text: String): Double? {
        return CouponAmountExtractor.extractMaximumDiscount(
            text = text,
            logDebug = logDebug,
            logError = logError
        )
    }

    fun extractPaymentMethod(text: String): String? {
        return CouponMetadataExtractor.extractPaymentMethod(
            text = text,
            logDebug = logDebug
        )
    }

    fun extractPlatformType(text: String): String? {
        return CouponMetadataExtractor.extractPlatformType(text)
    }

    fun extractUsageLimit(text: String): Int? {
        return CouponMetadataExtractor.extractUsageLimit(
            text = text,
            logDebug = logDebug,
            logError = logError
        )
    }

    private fun prepareFieldExtractionText(text: String): String {
        return CouponTextBlocks.prepareFieldText(text)
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
}

package com.example.coupontracker.extraction.deterministic

import com.example.coupontracker.data.model.Coupon
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * Applies guard rails and confidence scoring on deterministic extraction output.
 */
class SmartCouponSanitizer(
    private val storeCanon: StoreCanon,
    private val composer: DescriptionComposer
) {

    data class SanitizedResult(
        val coupon: Coupon,
        val confidence: Float,
        val needsReview: Boolean,
        val issues: List<String>
    )

    fun sanitize(
        fields: DeterministicCouponExtractor.Result,
        fallbackCoupon: Coupon?,
        imageUri: String?,
        captureTimestamp: Date?
    ): SanitizedResult {
        val issues = mutableListOf<String>()
        val resolvedStore = storeCanon.resolve(fields.storeCandidate, fields.flatText, fields.offer)
            ?: fallbackCoupon?.storeName?.takeIf { it.isNotBlank() }
        val canonicalStore = resolvedStore?.let { storeCanon.canonicalize(it) ?: it }

        val offerText = composer.normalizeOffer(fields.offer)
            ?: fallbackCoupon?.description?.takeIf { it.isNotBlank() }
        if (offerText == null) {
            issues.add("Missing offer text")
        }

        if (canonicalStore == null) {
            issues.add("Missing store name")
        } else if (storeCanon.isBadWord(canonicalStore)) {
            issues.add("Store matched stopword")
        }

        val code = fields.code ?: fallbackCoupon?.redeemCode?.takeIf { it.isNotBlank() }
        val sanitizedCode = code?.uppercase(Locale.ROOT)

        val expiryDate = fields.expiryDate
            ?: fallbackCoupon?.expiryDate?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
        val expiryDateConverted = expiryDate?.atStartOfDay(ZoneId.systemDefault())?.let { Date.from(it.toInstant()) }

        val description = composer.compose(offerText, canonicalStore)

        val createdAt = fallbackCoupon?.createdAt ?: captureTimestamp ?: Date()
        val baseCoupon = fallbackCoupon ?: Coupon(
            id = 0,
            storeName = canonicalStore ?: com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE,
            description = description,
            redeemCode = sanitizedCode,
            expiryDate = expiryDateConverted,
            imageUri = imageUri,
            status = com.example.coupontracker.data.model.Coupon.Status.ACTIVE,
            createdAt = createdAt,
            updatedAt = Date()
        )

        val finalCoupon = baseCoupon.copy(
            storeName = canonicalStore ?: baseCoupon.storeName,
            description = description,
            normalizedDescription = description.lowercase(Locale.ROOT),
            redeemCode = sanitizedCode,
            expiryDate = expiryDateConverted ?: baseCoupon.expiryDate,
            imageUri = imageUri ?: baseCoupon.imageUri,
            updatedAt = Date()
        )

        val confidence = computeConfidence(fields, canonicalStore, offerText, sanitizedCode)
        val needsReview = confidence < 0.5f || canonicalStore == null || offerText.isNullOrBlank()

        if (needsReview) {
            issues.add("Needs manual review")
        }

        return SanitizedResult(
            coupon = finalCoupon,
            confidence = confidence,
            needsReview = needsReview,
            issues = issues
        )
    }

    private fun computeConfidence(
        fields: DeterministicCouponExtractor.Result,
        canonicalStore: String?,
        offerText: String?,
        code: String?
    ): Float {
        var score = 0f
        if (!canonicalStore.isNullOrBlank() && !storeCanon.isBadWord(canonicalStore)) {
            score += 0.4f
        } else if (canonicalStore == null) {
            score -= 0.5f
        }

        if (!offerText.isNullOrBlank()) {
            score += if (fields.offerMatched) 0.3f else 0.15f
        }

        if (!code.isNullOrBlank()) {
            val codeMentioned = Regex("""(?i)code[:\s-]*${Regex.escape(code)}""").containsMatchIn(fields.flatText)
            score += if (codeMentioned) 0.2f else 0.1f
        }

        return score.coerceIn(0f, 1f)
    }
}

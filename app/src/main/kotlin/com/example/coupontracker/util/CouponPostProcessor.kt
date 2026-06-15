package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import java.util.Date

/**
 * Final pass fixer that enforces mandatory coupon fields before surfacing to UI.
 * Applies lightweight heuristics only where existing values are missing or generic.
 */
object CouponPostProcessor {

    private val textExtractor = TextExtractor()

    private val descriptionPlaceholders = setOf(
        "",
        "No description",
        "Coupon offer",
        "Extracted via LLM",
        "Multi-coupon detected",
        "Scanned from QR code"
    )

    fun refine(coupon: Coupon, context: CouponFixContext = CouponFixContext()): Coupon {
        val ocrText = context.ocrText?.takeIf { it.isNotBlank() }
        val hadUnsupportedStore = ocrText != null &&
            currentStoreNeedsReview(coupon.storeName, ocrText)

        val refinedStore = resolveStoreName(coupon.storeName, ocrText)
        val refinedCode = resolveRedeemCode(coupon.redeemCode, ocrText)
        val refinedExpiry = resolveExpiry(coupon.expiryDate, ocrText, context.captureTimestamp)
        val normalized = PostOcrCouponNormalizer.normalize(
            currentDescription = coupon.description,
            ocrText = ocrText,
            storeName = refinedStore,
            redeemCode = refinedCode,
        )
        val refinedDescription = normalized.description
            ?: resolveDescription(coupon.description, null)

        return coupon.copy(
            storeName = refinedStore,
            redeemCode = refinedCode,
            expiryDate = refinedExpiry,
            description = refinedDescription,
            needsAttention = coupon.needsAttention || normalized.needsAttention || hadUnsupportedStore,
        )
    }

    private fun currentStoreNeedsReview(current: String, ocrText: String): Boolean {
        return current.isNotBlank() &&
            !GenericFieldHeuristics.isGenericOrMissing(current) &&
            !StoreCandidateValidator.isAcceptable(current, ocrText)
    }

    private fun resolveStoreName(current: String, ocrText: String?): String {
        if (current.isNotBlank() &&
            !GenericFieldHeuristics.isGenericOrMissing(current) &&
            (ocrText.isNullOrBlank() || StoreCandidateValidator.isAcceptable(current, ocrText))
        ) {
            return current
        }

        if (!ocrText.isNullOrBlank()) {
            val candidate = textExtractor.extractStoreName(ocrText)
            if (StoreCandidateValidator.isAcceptable(candidate, ocrText)) {
                return candidate.orEmpty()
            }
        }

        return current.ifBlank { com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE }
    }

    private fun resolveRedeemCode(current: String?, ocrText: String?): String? {
        val explicitCode = extractExplicitCode(ocrText)
        val normalized = current?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        if (!explicitCode.isNullOrBlank() && explicitCode != normalized) {
            return explicitCode
        }
        if (normalized != null) {
            return normalized
        }

        if (!ocrText.isNullOrBlank()) {
            val extracted = textExtractor.extractRedeemCode(ocrText)
            if (!GenericFieldHeuristics.isGenericOrMissingCode(extracted)) {
                return extracted
            }
        }

        return null
    }

    private fun extractExplicitCode(ocrText: String?): String? {
        if (ocrText.isNullOrBlank()) return null
        val pattern = Regex(
            pattern = "(?im)\\bcode\\s*[-–—:]?\\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)"
        )
        return pattern.find(ocrText)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(RedeemCodeSanitizer::sanitizePreserve)
    }

    private fun resolveExpiry(current: Date?, ocrText: String?, captureTimestamp: Date?): Date? {
        if (current != null) {
            return current
        }

        if (!ocrText.isNullOrBlank()) {
            return textExtractor.extractExpiryDate(ocrText, captureTimestamp)
        }

        return null
    }

    private fun resolveDescription(current: String, ocrText: String?): String {
        if (current.isNotBlank() && current !in descriptionPlaceholders) {
            return current
        }

        if (!ocrText.isNullOrBlank()) {
            val extracted = textExtractor.extractDescription(ocrText)
            val cleaned = LocalLlmOcrService.cleanDescription(extracted)
            if (cleaned.isNotBlank() && cleaned !in descriptionPlaceholders) {
                return cleaned
            }

        }

        return current.ifBlank { "Coupon offer" }
    }
}

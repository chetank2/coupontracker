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

        val refinedStore = resolveStoreName(coupon.storeName, ocrText)
        val refinedCode = resolveRedeemCode(coupon.redeemCode, ocrText)
        val refinedExpiry = resolveExpiry(coupon.expiryDate, ocrText, context.captureTimestamp)
        val refinedDescription = resolveDescription(coupon.description, ocrText)

        return coupon.copy(
            storeName = refinedStore,
            redeemCode = refinedCode,
            expiryDate = refinedExpiry,
            description = refinedDescription
        )
    }

    private fun resolveStoreName(current: String, ocrText: String?): String {
        if (current.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(current)) {
            return current
        }

        if (!ocrText.isNullOrBlank()) {
            val candidate = textExtractor.extractStoreName(ocrText)
            if (!candidate.isNullOrBlank() && !GenericFieldHeuristics.isGenericOrMissing(candidate)) {
                return candidate
            }
        }

        return current.ifBlank { "Unknown Store" }
    }

    private fun resolveRedeemCode(current: String?, ocrText: String?): String? {
        val normalized = current?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
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

            val fallback = LocalLlmOcrService.cleanDescription(ocrText)
                .takeIf { it.isNotBlank() }
                ?.let { it.take(240) }
            if (!fallback.isNullOrBlank() && fallback !in descriptionPlaceholders) {
                return fallback
            }
        }

        return current.ifBlank { "Coupon offer" }
    }
}


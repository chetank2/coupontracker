package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import kotlin.math.roundToInt

enum class ExtractionConfidenceBand {
    HIGH,
    MEDIUM,
    LOW
}

enum class ExtractionRecommendation {
    SAVE_DIRECTLY,
    VERIFY_WITH_VISION,
    MANUAL_REVIEW
}

data class CouponExtractionConfidence(
    val score: Int,
    val band: ExtractionConfidenceBand,
    val recommendation: ExtractionRecommendation,
    val fieldConfidences: Map<String, Float>,
    val issues: List<String>
)

/**
 * Scores whether OCR + deterministic rules are enough, or whether the saved
 * screenshot should be checked by an image-aware verifier.
 */
object CouponExtractionConfidenceScorer {
    private val explicitCodeRegex = Regex(
        pattern = """(?i)\b(?:code|coupon\s+code|promo\s+code)\b\s*[:\-–—]?\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)"""
    )
    private val walletChromeRegex = Regex("""(?i)\b(vouchers?|active|lifetime)\b""")

    fun score(coupon: Coupon, rawOcrText: String?): CouponExtractionConfidence {
        val ocr = rawOcrText.orEmpty()
        val issues = mutableListOf<String>()

        val storeConfidence = scoreStore(coupon, ocr, issues)
        val descriptionConfidence = scoreDescription(coupon, ocr, issues)
        val codeConfidence = scoreCode(coupon, ocr, issues)
        val expiryConfidence = scoreExpiry(coupon, issues)
        val ocrConfidence = scoreOcr(ocr, issues)

        val explicitCodes = explicitCodeRegex.findAll(ocr).map { it.groupValues[1] }.distinct().toList()
        if (explicitCodes.size > 1) {
            issues += "multiple_coupon_codes_in_ocr"
        }
        if (walletChromeRegex.containsMatchIn(ocr) && explicitCodes.size > 1) {
            issues += "multi_coupon_wallet_screen"
        }

        val fields = mapOf(
            "storeName" to storeConfidence,
            "description" to descriptionConfidence,
            "redeemCode" to codeConfidence,
            "expiryDate" to expiryConfidence,
            "ocr" to ocrConfidence
        )

        val weightedScore = (
            storeConfidence * 25f +
                descriptionConfidence * 25f +
                codeConfidence * 20f +
                expiryConfidence * 15f +
                ocrConfidence * 15f
            ).roundToInt()

        val highRisk = issues.any {
            it == "multiple_coupon_codes_in_ocr" ||
                it == "multi_coupon_wallet_screen" ||
                it == "unsupported_coupon_code" ||
                it == "unsupported_store_name"
        }
        val missingCoreFields = storeConfidence < 0.4f && descriptionConfidence < 0.4f
        val score = weightedScore.coerceIn(0, 100)
        val band = when {
            score >= 90 && !highRisk -> ExtractionConfidenceBand.HIGH
            score >= 65 -> ExtractionConfidenceBand.MEDIUM
            else -> ExtractionConfidenceBand.LOW
        }
        val recommendation = when {
            missingCoreFields -> ExtractionRecommendation.MANUAL_REVIEW
            band == ExtractionConfidenceBand.HIGH -> ExtractionRecommendation.SAVE_DIRECTLY
            else -> ExtractionRecommendation.VERIFY_WITH_VISION
        }

        return CouponExtractionConfidence(
            score = score,
            band = band,
            recommendation = recommendation,
            fieldConfidences = fields,
            issues = issues.distinct()
        )
    }

    private fun scoreStore(
        coupon: Coupon,
        ocr: String,
        issues: MutableList<String>
    ): Float {
        val store = coupon.storeName.trim()
        if (store.isBlank() || store == Coupon.Defaults.UNKNOWN_STORE || GenericFieldHeuristics.isGenericOrMissing(store)) {
            issues += "missing_store_name"
            return 0f
        }
        if (ocr.isBlank()) return 0.65f
        if (!OcrEvidenceValidator.isPhraseSupported(store, ocr)) {
            issues += "unsupported_store_name"
            return 0.35f
        }
        return 1f
    }

    private fun scoreDescription(
        coupon: Coupon,
        ocr: String,
        issues: MutableList<String>
    ): Float {
        val description = coupon.description.trim()
        if (!GenericFieldHeuristics.isMeaningfulDescription(description)) {
            issues += "missing_offer_description"
            return 0f
        }
        if (description.lineSequence().filter { it.isNotBlank() }.count() > 5) {
            issues += "description_looks_like_raw_ocr"
            return 0.45f
        }
        if (ocr.isBlank()) return 0.65f
        return if (OcrEvidenceValidator.isPhraseSupported(description, ocr)) 1f else 0.75f
    }

    private fun scoreCode(
        coupon: Coupon,
        ocr: String,
        issues: MutableList<String>
    ): Float {
        val code = coupon.redeemCode?.trim().orEmpty()
        if (code.isBlank() || GenericFieldHeuristics.isGenericOrMissingCode(code)) {
            issues += "missing_coupon_code"
            return 0.55f
        }
        if (ocr.isBlank()) return 0.7f
        if (!OcrEvidenceValidator.isPhraseSupported(code, ocr)) {
            issues += "unsupported_coupon_code"
            return 0.2f
        }
        return 1f
    }

    private fun scoreExpiry(coupon: Coupon, issues: MutableList<String>): Float {
        return if (coupon.expiryDate != null) {
            1f
        } else {
            issues += "missing_expiry_date"
            0.5f
        }
    }

    private fun scoreOcr(ocr: String, issues: MutableList<String>): Float {
        if (ocr.isBlank()) {
            issues += "missing_raw_ocr"
            return 0f
        }
        val usefulLines = ocr.lineSequence().map { it.trim() }.filter { it.length >= 3 }.count()
        return when {
            usefulLines >= 6 -> 1f
            usefulLines >= 3 -> 0.75f
            else -> {
                issues += "thin_raw_ocr"
                0.45f
            }
        }
    }
}

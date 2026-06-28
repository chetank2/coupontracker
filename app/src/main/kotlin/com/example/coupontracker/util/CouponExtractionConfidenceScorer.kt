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
        pattern = """(?i)\b(?:code|coupon\s+code|promo\s+code)\b\s*[:\-–—]\s*([A-Z0-9][A-Z0-9_-]{4,}(?:[-–—][A-Z0-9][A-Z0-9_-]{2,})*)"""
    )
    private val walletChromeRegex = Regex("""(?i)\b(vouchers?|active|lifetime)\b""")

    fun score(coupon: Coupon, rawOcrText: String?): CouponExtractionConfidence {
        val ocr = rawOcrText.orEmpty()
        val issues = mutableListOf<String>()

        val layoutConfidence = scoreLayout(coupon, issues)
        val storeConfidence = scoreStore(coupon, ocr, issues)
        val descriptionConfidence = scoreDescription(coupon, ocr, issues)
        val codeConfidence = scoreCodeOrState(coupon, ocr, issues)
        val expiryConfidence = scoreExpiryOrState(coupon, issues)
        val contradictionConfidence = scoreOcrContradictions(coupon, ocr, issues)
        val rawOcrConfidence = scoreRawOcr(ocr, issues)

        val fields = mapOf(
            "layout" to layoutConfidence,
            "storeName" to storeConfidence,
            "description" to descriptionConfidence,
            "redeemCode" to codeConfidence,
            "codeState" to codeConfidence,
            "expiryDate" to expiryConfidence,
            "expiryState" to expiryConfidence,
            "ocrContradiction" to contradictionConfidence,
            "ocr" to rawOcrConfidence
        )

        val weightedScore = (
            layoutConfidence * 25f +
                storeConfidence * 20f +
                descriptionConfidence * 20f +
                codeConfidence * 20f +
                expiryConfidence * 10f +
                contradictionConfidence * 5f
            ).roundToInt()

        val highRisk = issues.any {
            it == "multiple_coupon_codes_in_ocr" ||
                it == "multi_coupon_wallet_screen" ||
                it == "unsupported_coupon_code" ||
                it == "unsupported_store_name" ||
                it == "layout_partial" ||
                it == "layout_multi_card" ||
                (it == "layout_low_confidence" && !isVerifiedExtraction(coupon)) ||
                it == "ocr_contradiction"
        }
        val missingCoreFields = storeConfidence < 0.4f && descriptionConfidence < 0.4f
        val maxScore = confidenceScoreCap(coupon, descriptionConfidence)
        val score = weightedScore.coerceIn(0, maxScore)
        val band = when {
            score >= 90 && !highRisk -> ExtractionConfidenceBand.HIGH
            score >= 65 -> ExtractionConfidenceBand.MEDIUM
            else -> ExtractionConfidenceBand.LOW
        }
        val recommendation = when {
            missingCoreFields -> ExtractionRecommendation.MANUAL_REVIEW
            highRisk -> ExtractionRecommendation.VERIFY_WITH_VISION
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

    private fun scoreLayout(coupon: Coupon, issues: MutableList<String>): Float {
        return when (coupon.layoutState) {
            Coupon.LayoutState.COMPLETE,
            Coupon.LayoutState.MODAL_FOREGROUND -> 1f
            Coupon.LayoutState.PARTIAL -> {
                issues += "layout_partial"
                0.45f
            }
            Coupon.LayoutState.MULTI_CARD -> {
                issues += "layout_multi_card"
                0.35f
            }
            Coupon.LayoutState.LOW_CONFIDENCE -> {
                issues += "layout_low_confidence"
                if (isVerifiedExtraction(coupon)) 0.8f else 0.55f
            }
            else -> {
                issues += "layout_unknown"
                0.55f
            }
        }
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
        if (coupon.needsAttention) {
            issues += "description_needs_attention"
            return 0.45f
        }
        if (!GenericFieldHeuristics.isMeaningfulDescription(description)) {
            issues += "missing_offer_description"
            return 0f
        }
        if (description.lineSequence().filter { it.isNotBlank() }.count() > 5) {
            issues += "description_looks_like_raw_ocr"
            return 0.45f
        }
        if (ocr.isBlank()) {
            issues += "weak_description_evidence"
            return 0.65f
        }
        return if (OcrEvidenceValidator.isPhraseSupported(description, ocr)) {
            1f
        } else {
            issues += "weak_description_evidence"
            0.75f
        }
    }

    private fun confidenceScoreCap(coupon: Coupon, descriptionConfidence: Float): Int {
        var cap = 100
        if (coupon.layoutState == Coupon.LayoutState.LOW_CONFIDENCE && !isVerifiedExtraction(coupon)) {
            cap = minOf(cap, 84)
        }
        if (coupon.needsAttention || descriptionConfidence < 0.8f) {
            cap = minOf(cap, 79)
        }
        return cap
    }

    private fun scoreCodeOrState(
        coupon: Coupon,
        ocr: String,
        issues: MutableList<String>
    ): Float {
        val code = coupon.redeemCode?.trim().orEmpty()
        if (coupon.codeState == Coupon.CodeState.NO_CODE_NEEDED) {
            if (code.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(code)) {
                issues += "code_present_when_no_code_needed"
                return 0.7f
            }
            return 1f
        }
        if (coupon.codeState == Coupon.CodeState.NOT_VISIBLE) {
            if (code.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(code)) {
                issues += "code_present_when_not_visible"
                return 0.45f
            }
            return 0.85f
        }

        if (code.isBlank() || GenericFieldHeuristics.isGenericOrMissingCode(code)) {
            issues += "missing_coupon_code"
            return if (coupon.codeState == Coupon.CodeState.UNKNOWN) 0.45f else 0f
        }
        if (coupon.codeState == Coupon.CodeState.PRESENT &&
            !isSupportedByVisibleEvidence(code, ocr, coupon.debugVisionEvidence)
        ) {
            issues += "code_state_present_without_visible_code"
            issues += "unsupported_coupon_code"
            return 0f
        }
        if (ocr.isBlank()) {
            issues += "code_without_ocr_evidence"
            return 0.6f
        }
        if (!isSupportedByVisibleEvidence(code, ocr, coupon.debugVisionEvidence)) {
            issues += "unsupported_coupon_code"
            return 0.2f
        }
        return 1f
    }

    private fun scoreExpiryOrState(coupon: Coupon, issues: MutableList<String>): Float {
        return when {
            coupon.expiryDate != null -> 1f
            coupon.expiryState == Coupon.ExpiryState.NOT_VISIBLE -> 1f
            coupon.expiryState == Coupon.ExpiryState.PRESENT -> {
                issues += "expiry_state_present_but_date_missing"
                0.35f
            }
            else -> {
                issues += "missing_expiry_date"
                0.55f
            }
        }
    }

    private fun scoreOcrContradictions(
        coupon: Coupon,
        ocr: String,
        issues: MutableList<String>
    ): Float {
        if (ocr.isBlank()) return 0.6f
        val explicitCodes = explicitCodeRegex.findAll(ocr).map { it.groupValues[1] }.distinct().toList()
        val code = coupon.redeemCode?.trim().orEmpty()
        if (explicitCodes.size > 1) {
            issues += "multiple_coupon_codes_in_ocr"
        }
        if (walletChromeRegex.containsMatchIn(ocr) && explicitCodes.size > 1) {
            issues += "multi_coupon_wallet_screen"
        }
        if (coupon.codeState == Coupon.CodeState.NO_CODE_NEEDED && explicitCodes.isNotEmpty()) {
            issues += "ocr_contradiction"
            return 0f
        }
        if (coupon.codeState == Coupon.CodeState.PRESENT &&
            code.isNotBlank() &&
            !GenericFieldHeuristics.isGenericOrMissingCode(code) &&
            !isSupportedByVisibleEvidence(code, ocr, coupon.debugVisionEvidence)
        ) {
            issues += "ocr_contradiction"
            return 0f
        }
        if (code.isNotBlank() &&
            !GenericFieldHeuristics.isGenericOrMissingCode(code) &&
            explicitCodes.isNotEmpty() &&
            explicitCodes.none { it.equals(code, ignoreCase = true) } &&
            !isSupportedByVisibleEvidence(code, ocr, coupon.debugVisionEvidence)
        ) {
            issues += "unsupported_coupon_code"
            issues += "ocr_contradiction"
            return 0f
        }
        return 1f
    }

    private fun scoreRawOcr(ocr: String, issues: MutableList<String>): Float {
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

    private fun isSupportedByVisibleEvidence(
        candidate: String,
        ocr: String,
        debugVisionEvidence: String?
    ): Boolean {
        return OcrEvidenceValidator.isPhraseSupported(candidate, ocr) ||
            OcrEvidenceValidator.isPhraseSupported(candidate, debugVisionEvidence)
    }

    private fun isVerifiedExtraction(coupon: Coupon): Boolean {
        return coupon.extractionSource == Coupon.ExtractionSource.OCR_VERIFIED ||
            coupon.extractionSource == Coupon.ExtractionSource.VISION_VERIFIED ||
            coupon.extractionSource == Coupon.ExtractionSource.USER_EDITED
    }
}

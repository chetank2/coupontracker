package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.StoreCandidateValidator
import org.json.JSONObject

class VisionOcrMergePolicy {

    fun merge(base: Coupon, vision: VisionFieldExtraction?, rawOcrText: String?): Coupon {
        val card = vision?.activeCard ?: return base
        val ocr = rawOcrText.orEmpty()
        val codeFromOcr = base.redeemCode
            ?.trim()
            ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val visionCode = card.redeemCode?.trim()?.takeIf { it.isNotBlank() }
        val supportedVisionCode = visionCode?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, ocr) }
        val codeState = inferCodeState(card, codeFromOcr, supportedVisionCode)
        val expiryState = inferExpiryState(card, base)
        val layoutState = card.layoutState

        val merged = base.copy(
            storeName = selectVisionText(card.storeName, base.storeName, ocr),
            description = selectVisionDescription(card.description, base.description, ocr),
            redeemCode = when {
                codeFromOcr != null -> codeFromOcr
                codeState == Coupon.CodeState.NO_CODE_NEEDED -> null
                else -> supportedVisionCode
            },
            codeState = codeState,
            expiryState = expiryState,
            layoutState = layoutState,
            debugVisionEvidence = buildEvidence(vision, card, visionCode, supportedVisionCode),
            extractionSource = Coupon.ExtractionSource.VISION_VERIFIED
        )

        val reviewRequired = requiresReview(merged)
        return merged.copy(
            needsAttention = merged.needsAttention || reviewRequired,
            cleanupStatus = when {
                reviewRequired -> Coupon.CleanupStatus.FAILED
                merged.cleanupStatus == Coupon.CleanupStatus.FAILED -> Coupon.CleanupStatus.NONE
                else -> merged.cleanupStatus
            },
            cleanupError = if (reviewRequired) {
                merged.cleanupError ?: "vision_field_state_requires_review"
            } else {
                null
            },
            extractionSource = if (reviewRequired) base.extractionSource else Coupon.ExtractionSource.VISION_VERIFIED
        )
    }

    private fun inferCodeState(card: VisionCouponCard, ocrCode: String?, supportedVisionCode: String?): String {
        if (ocrCode != null || supportedVisionCode != null) return Coupon.CodeState.PRESENT
        if (card.codeState == Coupon.CodeState.NO_CODE_NEEDED) return Coupon.CodeState.NO_CODE_NEEDED
        if (card.codeState == Coupon.CodeState.PRESENT) return Coupon.CodeState.UNKNOWN
        return card.codeState.takeIf { it in VALID_CODE_STATES } ?: Coupon.CodeState.UNKNOWN
    }

    private fun inferExpiryState(card: VisionCouponCard, base: Coupon): String {
        if (base.expiryDate != null) return Coupon.ExpiryState.PRESENT
        return card.expiryState.takeIf { it in VALID_EXPIRY_STATES } ?: Coupon.ExpiryState.UNKNOWN
    }

    private fun selectVisionText(
        candidate: String?,
        current: String,
        ocr: String
    ): String {
        val trimmed = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return current
        if (GenericFieldHeuristics.isGenericOrMissing(trimmed)) return current
        if (!StoreCandidateValidator.isAcceptable(trimmed, ocr)) return current
        if (ocr.isNotBlank() && !OcrEvidenceValidator.isPhraseSupported(trimmed, ocr)) return current
        return trimmed
    }

    private fun selectVisionDescription(candidate: String?, current: String, ocr: String): String {
        val trimmed = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return current
        if (!GenericFieldHeuristics.isMeaningfulDescription(trimmed)) return current
        if (!OfferTextQuality.isLikelyOfferText(trimmed)) return current
        if (OfferTextQuality.isLikelyDateOrContextNoise(trimmed)) return current
        val lower = trimmed.lowercase()
        if (LEGAL_BOILERPLATE.any { lower.contains(it) }) return current
        if (ocr.isNotBlank() && !OcrEvidenceValidator.isPhraseSupported(trimmed, ocr)) return current
        return trimmed
    }

    private fun requiresReview(coupon: Coupon): Boolean {
        if (coupon.layoutState == Coupon.LayoutState.LOW_CONFIDENCE) return true
        if (coupon.codeState == Coupon.CodeState.UNKNOWN && coupon.redeemCode.isNullOrBlank()) return true
        if (coupon.expiryState == Coupon.ExpiryState.UNKNOWN && coupon.expiryDate == null) return true
        return false
    }

    private fun buildEvidence(
        vision: VisionFieldExtraction,
        card: VisionCouponCard,
        visionCode: String?,
        supportedVisionCode: String?
    ): String {
        return JSONObject()
            .put("confidence", vision.confidence.toDouble())
            .put("cardConfidence", card.confidence.toDouble())
            .put("codeState", card.codeState)
            .put("expiryState", card.expiryState)
            .put("layoutState", card.layoutState)
            .put("evidence", card.evidence)
            .put("vlmCodeRejected", visionCode != null && supportedVisionCode == null)
            .toString()
    }

    private companion object {
        private val LEGAL_BOILERPLATE = listOf(
            "not a sponsor",
            "not sponsored",
            "apple or google",
            "apple and google",
            "google is not a sponsor"
        )
    }
}

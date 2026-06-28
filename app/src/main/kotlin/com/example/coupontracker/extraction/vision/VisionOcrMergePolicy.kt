package com.example.coupontracker.extraction.vision

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.IndianDateParser
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.StoreCandidateValidator
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

open class VisionOcrMergePolicy {

    fun merge(base: Coupon, vision: VisionFieldExtraction?, rawOcrText: String?): Coupon {
        val card = vision?.activeCard ?: return base
        val ocr = rawOcrText.orEmpty()
        val evidence = LabeledEvidence.from(card.evidence)
        val codeFromOcr = base.redeemCode
            ?.trim()
            ?.takeIf { isOcrCodeSupported(it, ocr) }
        val visionCode = card.redeemCode?.trim()?.takeIf { it.isNotBlank() }
        val supportedVisionCode = visionCode?.takeIf { isVisionCodeSupported(it, ocr, evidence) }
        val codeState = inferCodeState(card, codeFromOcr, supportedVisionCode)
        val expiryDate = resolveExpiryDate(base, card, evidence, ocr)
        val expiryState = inferExpiryState(card, expiryDate)
        val layoutState = card.layoutState
        val storeResolution = selectVisionStore(card.storeName, base.storeName, evidence, ocr)
        val description = selectEvidenceDescription(card.description, base.description, evidence, ocr)

        val merged = base.copy(
            storeName = storeResolution.value,
            description = description,
            expiryDate = expiryDate,
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

        val reviewRequired = requiresReview(merged) || storeResolution.contradiction || codeContradiction(card, codeFromOcr, supportedVisionCode)
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

    private fun inferExpiryState(card: VisionCouponCard, expiryDate: Date?): String {
        if (expiryDate != null) return Coupon.ExpiryState.PRESENT
        return card.expiryState.takeIf { it in VALID_EXPIRY_STATES } ?: Coupon.ExpiryState.UNKNOWN
    }

    private fun selectVisionStore(
        candidate: String?,
        current: String,
        evidence: LabeledEvidence,
        ocr: String
    ): StoreResolution {
        val trimmed = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return StoreResolution(current)
        if (GenericFieldHeuristics.isGenericOrMissing(trimmed)) return StoreResolution(current)
        if (!StoreCandidateValidator.isAcceptable(trimmed)) return StoreResolution(current)

        val currentLooksSupported = !GenericFieldHeuristics.isGenericOrMissing(current) &&
            OcrEvidenceValidator.isPhraseSupported(current, ocr)
        val visualLooksSupported = OcrEvidenceValidator.isPhraseSupported(trimmed, ocr) ||
            evidence.values(STORE_LABELS).any { it.equals(trimmed, ignoreCase = true) }
        val contradictsOcr = currentLooksSupported &&
            !current.equals(trimmed, ignoreCase = true) &&
            !visualLooksSupported

        return if (contradictsOcr) StoreResolution(current, contradiction = true) else StoreResolution(trimmed)
    }

    private fun selectEvidenceDescription(
        candidate: String?,
        current: String,
        evidence: LabeledEvidence,
        ocr: String
    ): String {
        val candidates = evidence.values(DESCRIPTION_LABELS) + listOfNotNull(candidate)
        val selected = candidates
            .mapNotNull { it.trim().takeIf(String::isNotBlank) }
            .firstOrNull { isSupportedOfferDescription(it, ocr) }
        return selected ?: current
    }

    private fun isSupportedOfferDescription(trimmed: String, ocr: String): Boolean {
        if (!GenericFieldHeuristics.isMeaningfulDescription(trimmed)) return false
        if (!OfferTextQuality.isLikelyOfferText(trimmed)) return false
        if (OfferTextQuality.isLikelyDateOrContextNoise(trimmed)) return false
        val lower = trimmed.lowercase()
        if (LEGAL_BOILERPLATE.any { lower.contains(it) }) return false
        return ocr.isBlank() || OcrEvidenceValidator.isPhraseSupported(trimmed, ocr)
    }

    private fun requiresReview(coupon: Coupon): Boolean {
        if (coupon.layoutState == Coupon.LayoutState.LOW_CONFIDENCE) return true
        if (coupon.codeState == Coupon.CodeState.UNKNOWN && coupon.redeemCode.isNullOrBlank()) return true
        if (coupon.expiryState == Coupon.ExpiryState.UNKNOWN && coupon.expiryDate == null) return true
        return false
    }

    private fun resolveExpiryDate(
        base: Coupon,
        card: VisionCouponCard,
        evidence: LabeledEvidence,
        ocr: String
    ): Date? {
        val zone = ZoneId.systemDefault()
        val baseDate = base.extractionTimestamp
            ?.toInstant()
            ?.atZone(zone)
            ?.toLocalDate()
            ?: LocalDate.now(zone)
        val candidates = evidence.values(EXPIRY_LABELS) + listOfNotNull(card.expiryText)
        for (candidate in candidates.map { it.trim() }.filter { it.isNotBlank() }) {
            if (ocr.isNotBlank() && !OcrEvidenceValidator.isPhraseSupported(candidate, ocr)) continue
            val parsed = IndianDateParser.extractExpiryFromText(candidate, baseDate).date
                ?: IndianDateParser.parseExpiryIST(candidate, baseDate).date
            if (parsed != null) return Date.from(parsed.atStartOfDay(zone).toInstant())
        }
        return base.expiryDate
    }

    private fun isOcrCodeSupported(
        code: String,
        ocr: String
    ): Boolean {
        val trimmed = code.trim()
        if (GenericFieldHeuristics.isGenericOrMissingCode(trimmed)) return false
        return ocr.isNotBlank() && OcrEvidenceValidator.isPhraseSupported(trimmed, ocr)
    }

    private fun isVisionCodeSupported(
        code: String,
        ocr: String,
        evidence: LabeledEvidence
    ): Boolean {
        val trimmed = code.trim()
        if (GenericFieldHeuristics.isGenericOrMissingCode(trimmed)) return false
        if (ocr.isBlank() || !OcrEvidenceValidator.isPhraseSupported(trimmed, ocr)) return false
        val labeledCode = evidence.values(CODE_LABELS).any { it.equals(trimmed, ignoreCase = true) }
        return if (evidence.hasRawEvidence) {
            labeledCode || OcrEvidenceValidator.isPhraseSupported(trimmed, evidence.visibleText)
        } else {
            true
        }
    }

    private fun codeContradiction(
        card: VisionCouponCard,
        codeFromOcr: String?,
        supportedVisionCode: String?
    ): Boolean {
        return card.codeState == Coupon.CodeState.PRESENT && codeFromOcr == null && supportedVisionCode == null
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
        private val STORE_LABELS = setOf("store", "storename", "merchant", "brand")
        private val DESCRIPTION_LABELS = setOf("description", "offer", "benefit")
        private val CODE_LABELS = setOf("code", "redeemcode", "couponcode", "promocode")
        private val EXPIRY_LABELS = setOf("expiry", "expirytext", "expires", "validuntil")
    }

    private data class StoreResolution(
        val value: String,
        val contradiction: Boolean = false
    )

    private data class LabeledEvidence(
        val labeled: Map<String, List<String>>,
        val visibleText: String,
        val hasRawEvidence: Boolean
    ) {
        fun values(labels: Set<String>): List<String> {
            return labels.flatMap { labeled[it].orEmpty() }
        }

        fun isEmpty(): Boolean = labeled.isEmpty() && visibleText.isBlank()

        companion object {
            fun from(raw: String?): LabeledEvidence {
                if (raw.isNullOrBlank()) return LabeledEvidence(emptyMap(), "", hasRawEvidence = false)

                val values = linkedMapOf<String, MutableList<String>>()
                val visibleLines = mutableListOf<String>()
                raw.lineSequence()
                    .flatMap { it.split(';').asSequence() }
                    .map { it.cleanEvidenceLine() }
                    .filter { it.isNotBlank() }
                    .forEach { line ->
                        val parts = line.split(':', limit = 2)
                        if (parts.size == 2) {
                            val label = parts[0].normalizeLabel()
                            val value = parts[1].trim()
                            if (value.isBlank()) return@forEach
                            if (label in NOISE_LABELS) return@forEach
                            values.getOrPut(label) { mutableListOf() } += value
                            visibleLines += value
                        } else {
                            visibleLines += line
                        }
                    }
                return LabeledEvidence(values, visibleLines.joinToString("\n"), hasRawEvidence = true)
            }

            private val NOISE_LABELS = setOf("noise", "previous", "previouscard", "chrome", "statusbar", "action")

            private fun String.cleanEvidenceLine(): String {
                return trim()
                    .removePrefix("-")
                    .removePrefix("*")
                    .trim()
            }

            private fun String.normalizeLabel(): String {
                return trim()
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]"), "")
            }
        }
    }
}

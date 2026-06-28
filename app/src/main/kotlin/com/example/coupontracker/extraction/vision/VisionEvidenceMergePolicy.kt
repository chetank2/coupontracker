package com.example.coupontracker.extraction.vision

import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.ModelExpiryNormalizer
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.StoreCandidateValidator
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * Merge policy entry point for two-pass vision evidence.
 */
class VisionEvidenceMergePolicy : VisionOcrMergePolicy() {

    fun mergeFieldLabels(
        current: Coupon,
        vision: VisionFieldExtraction,
        rawOcr: String?,
        visionInput: VisionFieldMergeInput,
        captureTimestamp: Date
    ): Coupon {
        val card = vision.activeCard
        if (card == null) {
            Log.w(TAG, "Gemma field-label pass returned no active card")
            return current.copy(
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                cleanupFinishedAt = Date(),
                cleanupError = "Vision verification needs review",
                lastCleanedBy = null,
                extractionSource = current.extractionSource.withoutTrustedModelSource(),
                needsAttention = true,
                updatedAt = Date()
            )
        }

        val hasCropEvidence = !rawOcr.isNullOrBlank()
        val supportedVisionStore = card.storeName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeIf {
                hasCropEvidence &&
                    StoreCandidateValidator.isAcceptable(it, rawOcr) &&
                    (
                        OcrEvidenceValidator.isPhraseSupported(it, rawOcr) ||
                            hasStoreTokenEvidence(it, rawOcr)
                        )
            }
        val supportedCurrentStore = current.storeName
            .trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf {
                hasCropEvidence &&
                    StoreCandidateValidator.isAcceptable(it, rawOcr) &&
                    (
                        OcrEvidenceValidator.isPhraseSupported(it, rawOcr) ||
                            hasStoreTokenEvidence(it, rawOcr)
                        )
            }
        val supportedVisionDescription = card.description
            ?.trim()
            ?.takeIf { GenericFieldHeuristics.isMeaningfulDescription(it) }
            ?.takeIf { hasCropEvidence && OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val supportedCurrentDescription = current.description
            .trim()
            .takeIf { GenericFieldHeuristics.isMeaningfulDescription(it) }
            ?.takeIf { hasCropEvidence && OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedStore = supportedVisionStore ?: supportedCurrentStore ?: current.storeName
        val selectedDescription = supportedVisionDescription ?: supportedCurrentDescription ?: current.description
        val currentStoreUnsupportedByCrop = supportedVisionStore == null && supportedCurrentStore == null
        val currentDescriptionUnsupportedByCrop = supportedVisionDescription == null && supportedCurrentDescription == null
        val currentSupportedCode = current.redeemCode
            ?.trim()
            ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { hasCropEvidence && OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val visionSupportedCode = card.redeemCode
            ?.trim()
            ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { hasCropEvidence && OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val exactCode = currentSupportedCode ?: visionSupportedCode
        val noCodeRequired = exactCode == null &&
            card.codeState == Coupon.CodeState.NO_CODE_NEEDED &&
            (hasNoCodeEvidence(rawOcr) || hasNoCodeEvidence(card.evidence))
        val presentCodeContradictedByNoCodeEvidence = exactCode == null &&
            card.codeState == Coupon.CodeState.PRESENT &&
            hasNoCodeEvidence(rawOcr)
        val currentHasUnsupportedCode = current.redeemCode
            ?.trim()
            ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.let { exactCode == null && !noCodeRequired }
            ?: false
        val codeState = when {
            exactCode != null -> Coupon.CodeState.PRESENT
            noCodeRequired -> Coupon.CodeState.NO_CODE_NEEDED
            card.codeState == Coupon.CodeState.PRESENT -> Coupon.CodeState.UNKNOWN
            card.codeState in VALID_CODE_STATES_FOR_FIELD_LABELS &&
                card.codeState != Coupon.CodeState.NO_CODE_NEEDED -> card.codeState
            else -> Coupon.CodeState.UNKNOWN
        }
        val visionExpirySupported = !card.expiryText.isNullOrBlank() &&
            hasCropEvidence &&
            OcrEvidenceValidator.isPhraseSupported(card.expiryText, rawOcr)
        val parsedVisionExpiry = if (card.expiryState == Coupon.ExpiryState.PRESENT && visionExpirySupported) {
            ModelExpiryNormalizer.parse(card.expiryText, captureTimestamp)
        } else {
            null
        }
        val parsedCropExpiry = if (
            parsedVisionExpiry == null &&
            hasCropEvidence &&
            card.expiryState != Coupon.ExpiryState.NOT_VISIBLE
        ) {
            ModelExpiryNormalizer.parse(rawOcr, captureTimestamp)
        } else {
            null
        }
        val visionPresentExpiryUnverified = card.expiryState == Coupon.ExpiryState.PRESENT &&
            (card.expiryText.isNullOrBlank() || !visionExpirySupported || parsedVisionExpiry == null)
        val preservesCurrentExpiry = parsedVisionExpiry == null &&
            parsedCropExpiry == null &&
            card.expiryState != Coupon.ExpiryState.NOT_VISIBLE &&
            current.expiryDate != null
        val selectedExpiry = parsedVisionExpiry
            ?: parsedCropExpiry
            ?: current.expiryDate.takeIf { preservesCurrentExpiry }
        val currentExpiryContradictedByNotVisible = current.expiryDate != null &&
            selectedExpiry == null &&
            card.expiryState == Coupon.ExpiryState.NOT_VISIBLE
        val expiryState = when {
            selectedExpiry != null -> Coupon.ExpiryState.PRESENT
            card.expiryState in VALID_EXPIRY_STATES_FOR_FIELD_LABELS -> card.expiryState
            else -> Coupon.ExpiryState.UNKNOWN
        }
        val layoutState = when {
            card.layoutState != Coupon.LayoutState.LOW_CONFIDENCE -> card.layoutState
            visionInput.layoutState != null -> visionInput.layoutState
            else -> Coupon.LayoutState.LOW_CONFIDENCE
        }
        val reviewRequired = !visionInput.usedTargetedCrop ||
            !hasCropEvidence ||
            vision.confidence < MIN_FIELD_LABEL_CONFIDENCE ||
            card.confidence < MIN_FIELD_LABEL_CONFIDENCE ||
            layoutState == Coupon.LayoutState.LOW_CONFIDENCE ||
            currentStoreUnsupportedByCrop ||
            currentDescriptionUnsupportedByCrop ||
            (codeState == Coupon.CodeState.UNKNOWN && current.redeemCode.isNullOrBlank()) ||
            presentCodeContradictedByNoCodeEvidence ||
            currentHasUnsupportedCode ||
            currentExpiryContradictedByNotVisible ||
            visionPresentExpiryUnverified ||
            (expiryState == Coupon.ExpiryState.UNKNOWN && selectedExpiry == null) ||
            (expiryState == Coupon.ExpiryState.PRESENT && selectedExpiry == null)

        val runPath = buildFieldSourceRunPath(
            stage = if (visionInput.source == "layout") "vision_layout_crop_field_label" else "vision_ocr_crop_field_label",
            storeSource = when {
                supportedVisionStore != null -> FIELD_SOURCE_VISION
                supportedCurrentStore != null -> FIELD_SOURCE_PRESERVED
                else -> FIELD_SOURCE_MISSING
            },
            descriptionSource = when {
                supportedVisionDescription != null -> FIELD_SOURCE_VISION
                supportedCurrentDescription != null -> FIELD_SOURCE_PRESERVED
                else -> FIELD_SOURCE_MISSING
            },
            codeSource = when {
                exactCode != null -> FIELD_SOURCE_OCR_RULE
                noCodeRequired -> FIELD_SOURCE_VISION
                current.redeemCode != null -> FIELD_SOURCE_MISSING
                else -> FIELD_SOURCE_MISSING
            },
            expirySource = when {
                parsedVisionExpiry != null -> FIELD_SOURCE_VISION
                parsedCropExpiry != null -> FIELD_SOURCE_OCR_RULE
                preservesCurrentExpiry -> FIELD_SOURCE_PRESERVED
                card.expiryState == Coupon.ExpiryState.NOT_VISIBLE -> FIELD_SOURCE_VISION
                expiryState != Coupon.ExpiryState.UNKNOWN -> FIELD_SOURCE_MISSING
                else -> FIELD_SOURCE_MISSING
            }
        )
        val extractionSource = when {
            current.extractionSource == Coupon.ExtractionSource.USER_EDITED -> Coupon.ExtractionSource.USER_EDITED
            reviewRequired -> current.extractionSource.withoutTrustedModelSource() ?: Coupon.ExtractionSource.OCR_VERIFIED
            else -> Coupon.ExtractionSource.VISION_VERIFIED
        }
        Log.i(
            TAG,
            "${if (reviewRequired) "MERGE_REJECTED" else "MERGE_ACCEPTED"} " +
                "source=${visionInput.source} layoutState=$layoutState codeState=$codeState expiryState=$expiryState " +
                "hasCropEvidence=$hasCropEvidence selectedExpiry=${selectedExpiry != null} " +
                "exactCode=${exactCode != null} noCodeRequired=$noCodeRequired"
        )
        return current.copy(
            storeName = selectedStore,
            description = selectedDescription,
            redeemCode = exactCode,
            expiryDate = selectedExpiry,
            codeState = codeState,
            expiryState = expiryState,
            layoutState = layoutState,
            debugVisionEvidence = buildVisionFieldEvidence(vision, visionInput),
            cleanupStatus = if (reviewRequired) Coupon.CleanupStatus.FAILED else Coupon.CleanupStatus.CLEANED,
            cleanupFinishedAt = Date(),
            cleanupError = if (reviewRequired) "Vision verification needs review" else null,
            lastCleanedBy = if (reviewRequired) null else ModelCatalog.GEMMA_VISION_READER_NAME,
            extractionSource = extractionSource,
            extractionRunPath = runPath,
            normalizedDescription = CouponDedupUtils.normalizeDescription(selectedDescription),
            needsAttention = current.needsAttention || reviewRequired,
            updatedAt = Date()
        )
    }

    private fun buildVisionFieldEvidence(
        vision: VisionFieldExtraction,
        visionInput: VisionFieldMergeInput
    ): String {
        val card = vision.activeCard
        val crop = visionInput.pixelCrop
        return JSONObject()
            .put("stage", "two_pass_gemma_crop_label")
            .put("source", visionInput.source)
            .put("confidence", vision.confidence.toDouble())
            .put("cardConfidence", card?.confidence?.toDouble())
            .put("codeState", card?.codeState)
            .put("expiryState", card?.expiryState)
            .put("layoutState", card?.layoutState ?: visionInput.layoutState)
            .put("evidence", card?.evidence)
            .put("normalizedBounds", visionInput.normalizedBoundsJson?.let(::JSONObject))
            .put("layoutAttempt", visionInput.debugEvidence?.let(::JSONObject))
            .put("pixelCrop", crop?.let { rect ->
                JSONObject()
                    .put("left", rect.left)
                    .put("top", rect.top)
                    .put("right", rect.right)
                    .put("bottom", rect.bottom)
            })
            .toString()
    }

    fun buildFailureEvidence(
        stage: String,
        visionInput: VisionFieldMergeInput?,
        error: Throwable,
        rawVisionJson: String?
    ): String {
        val crop = visionInput?.pixelCrop
        return JSONObject()
            .put("stage", stage)
            .put("source", visionInput?.source)
            .put("errorType", error::class.java.simpleName)
            .put("errorMessage", error.message)
            .put("rawVisionJson", rawVisionJson)
            .put("normalizedBounds", visionInput?.normalizedBoundsJson?.let(::JSONObject))
            .put("layoutState", visionInput?.layoutState)
            .put("layoutAttempt", visionInput?.debugEvidence?.let(::JSONObject))
            .put("pixelCrop", crop?.let { rect ->
                JSONObject()
                    .put("left", rect.left)
                    .put("top", rect.top)
                    .put("right", rect.right)
                    .put("bottom", rect.bottom)
            })
            .toString()
    }

    private fun String?.withoutTrustedModelSource(): String? {
        return when (this) {
            Coupon.ExtractionSource.VISION_VERIFIED,
            Coupon.ExtractionSource.QWEN_CLEANED -> null
            else -> this
        }
    }

    private fun buildFieldSourceRunPath(
        stage: String,
        storeSource: String,
        descriptionSource: String,
        codeSource: String,
        expirySource: String
    ): String {
        return JSONObject()
            .put("stage", stage)
            .put("storeName", storeSource)
            .put("description", descriptionSource)
            .put("redeemCode", codeSource)
            .put("expiryDate", expirySource)
            .toString()
    }

    private fun hasStoreTokenEvidence(storeName: String, rawOcr: String?): Boolean {
        if (rawOcr.isNullOrBlank()) return false
        val ocrTokens = rawOcr.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
        val storeTokens = storeName.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
        if (storeTokens.isEmpty()) return false
        val supported = storeTokens.count { it in ocrTokens }
        val hasDistinctiveAcronym = storeTokens.any { token ->
            token.length >= 4 && token.all(Char::isLetter) && token in ocrTokens
        }
        return supported >= 2 || (storeTokens.size <= 2 && supported == storeTokens.size) || hasDistinctiveAcronym
    }

    private fun hasNoCodeEvidence(rawOcr: String?): Boolean {
        val normalized = rawOcr.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        return Regex("\\bno\\s+code(?:\\s+needed|required)?\\b").containsMatchIn(normalized) ||
            normalized.contains("nocodeneeded") ||
            normalized.contains("no code needed")
    }

    private companion object {
        private const val TAG = "VisionEvidenceMergePolicy"
        private const val MIN_FIELD_LABEL_CONFIDENCE = 0.5f
        private const val FIELD_SOURCE_OCR_RULE = "OCR_RULE"
        private const val FIELD_SOURCE_VISION = "VISION"
        private const val FIELD_SOURCE_MISSING = "MISSING"
        private const val FIELD_SOURCE_PRESERVED = "PRESERVED"
        private val VALID_CODE_STATES_FOR_FIELD_LABELS = setOf(
            Coupon.CodeState.PRESENT,
            Coupon.CodeState.NO_CODE_NEEDED,
            Coupon.CodeState.NOT_VISIBLE,
            Coupon.CodeState.UNKNOWN
        )
        private val VALID_EXPIRY_STATES_FOR_FIELD_LABELS = setOf(
            Coupon.ExpiryState.PRESENT,
            Coupon.ExpiryState.NOT_VISIBLE,
            Coupon.ExpiryState.UNKNOWN
        )
    }
}

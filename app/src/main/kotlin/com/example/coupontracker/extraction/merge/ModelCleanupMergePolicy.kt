package com.example.coupontracker.extraction.merge

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.OcrEvidenceValidator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ModelCleanupMergeResult(
    val coupon: Coupon,
    val runPath: String,
    val regressedFields: List<String>
)

/**
 * Guardrails for text-model cleanup. Cleanup can improve display text, but it
 * must not replace OCR/rule anchored fields with weaker model guesses.
 */
object ModelCleanupMergePolicy {
    private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private const val CLEANUP_REJECTED_ERROR = "Model cleanup rejected by evidence policy"

    fun mergeQwenTextCleanup(
        current: Coupon,
        modelJson: JSONObject,
        cleanupInputText: String?,
        cleanedBy: String,
        now: Date = Date()
    ): ModelCleanupMergeResult {
        val ocrText = cleanupInputText.orEmpty()
        val regressed = mutableListOf<String>()
        val fieldDecisions = JSONObject()

        val descriptionSelection = selectDescription(current, modelJson, ocrText, fieldDecisions, regressed)

        evaluateStoreName(current, modelJson, ocrText, fieldDecisions, regressed)
        evaluateRedeemCode(current, modelJson, ocrText, fieldDecisions, regressed)
        evaluateExpiryDate(current, modelJson, fieldDecisions, regressed)

        val modelEvidenceWeak = hasWeakModelEvidence(modelJson)
        val modelNeedsAttention = modelJson.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false)
        val needsAttention = current.needsAttention ||
            modelNeedsAttention ||
            modelEvidenceWeak ||
            regressed.isNotEmpty()
        val acceptedModelChange = descriptionSelection.acceptedModelChange
        val trustedCleanup = acceptedModelChange && !needsAttention

        val runPath = JSONObject()
            .put("stage", "qwen_text_cleanup")
            .put(
                "cleanupInput",
                if (ocrText.isBlank()) "EMPTY" else if (current.rawOcrText?.trim() == ocrText.trim()) "RAW_OCR" else "DESCRIPTION"
            )
            .put("description", fieldDecisions.getJSONObject(CouponSchemaKeys.DESCRIPTION))
            .put("storeName", fieldDecisions.getJSONObject(CouponSchemaKeys.STORE_NAME))
            .put("redeemCode", fieldDecisions.getJSONObject(CouponSchemaKeys.REDEEM_CODE))
            .put("expiryDate", fieldDecisions.getJSONObject(CouponSchemaKeys.EXPIRY_DATE))
            .put("modelEvidence", if (modelEvidenceWeak) "WEAK_OR_EMPTY" else "PRESENT")
            .put("regressedFields", JSONArray(regressed))
            .put("acceptedModelChange", acceptedModelChange)
            .put("cleanupDecision", if (trustedCleanup) "accepted" else "rejected_preserved")
            .toString()

        val updated = current.copy(
            description = descriptionSelection.value,
            normalizedDescription = CouponDedupUtils.normalizeDescription(descriptionSelection.value),
            cleanupStatus = if (trustedCleanup) Coupon.CleanupStatus.CLEANED else Coupon.CleanupStatus.FAILED,
            cleanupStartedAt = current.cleanupStartedAt,
            cleanupFinishedAt = now,
            cleanupError = if (trustedCleanup) null else CLEANUP_REJECTED_ERROR,
            lastCleanedBy = if (trustedCleanup) cleanedBy else null,
            extractionRunPath = runPath,
            extractionSource = if (trustedCleanup) {
                Coupon.ExtractionSource.QWEN_CLEANED
            } else {
                current.extractionSource
            },
            needsAttention = needsAttention,
            updatedAt = now
        )
        return ModelCleanupMergeResult(updated, runPath, regressed)
    }

    private data class DescriptionSelection(
        val value: String,
        val acceptedModelChange: Boolean
    )

    private fun selectDescription(
        current: Coupon,
        modelJson: JSONObject,
        ocrText: String,
        fieldDecisions: JSONObject,
        regressed: MutableList<String>
    ): DescriptionSelection {
        val candidate = modelJson.optNullableString("offer")
            ?: modelJson.optNullableString(CouponSchemaKeys.DESCRIPTION)
        val accepted = candidate
            ?.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
            ?.takeIf(OfferTextQuality::isLikelyOfferText)
            ?.takeUnless(OfferTextQuality::isLikelyDateOrContextNoise)
            ?.takeIf { it.length <= 220 }
            ?.takeIf { isDescriptionSupported(it, ocrText, current.description) }
            ?.takeIf { hasSupportedNumbers(it, ocrText, current.description) }
            ?.takeIf { OfferTextQuality.score(it) >= OfferTextQuality.score(current.description) }
        val selected = accepted ?: current.description
        if (candidate != null && accepted == null) {
            regressed += CouponSchemaKeys.DESCRIPTION
        }
        val acceptedModelChange = accepted != null && !sameText(accepted, current.description)
        fieldDecisions.put(
            CouponSchemaKeys.DESCRIPTION,
            JSONObject()
                .put("source", if (accepted != null) "QWEN_TEXT" else "PRESERVED")
                .put("model", candidate ?: "missing")
                .put("modelScore", OfferTextQuality.score(candidate))
                .put("currentScore", OfferTextQuality.score(current.description))
                .put("numbersSupported", candidate?.let { hasSupportedNumbers(it, ocrText, current.description) } ?: false)
                .put("decision", if (accepted != null) "accepted" else "rejected_or_missing")
        )
        return DescriptionSelection(selected, acceptedModelChange)
    }

    private fun evaluateStoreName(
        current: Coupon,
        modelJson: JSONObject,
        ocrText: String,
        fieldDecisions: JSONObject,
        regressed: MutableList<String>
    ) {
        val currentStrong = !GenericFieldHeuristics.isGenericOrMissing(current.storeName)
        val candidate = modelJson.optNullableString(CouponSchemaKeys.STORE_NAME)
        val candidateStrong = !GenericFieldHeuristics.isGenericOrMissing(candidate)
        val supported = candidateStrong && OcrEvidenceValidator.isPhraseSupported(candidate, ocrText)
        val regressedField = currentStrong && (!candidateStrong || !sameText(current.storeName, candidate) || !supported)
        if (regressedField) regressed += CouponSchemaKeys.STORE_NAME
        fieldDecisions.put(
            CouponSchemaKeys.STORE_NAME,
            JSONObject()
                .put("source", "PRESERVED")
                .put("model", candidate ?: "missing")
                .put("modelSupportedByOcr", supported)
                .put("decision", if (regressedField) "rejected_model_regression" else "preserved")
        )
    }

    private fun evaluateRedeemCode(
        current: Coupon,
        modelJson: JSONObject,
        ocrText: String,
        fieldDecisions: JSONObject,
        regressed: MutableList<String>
    ) {
        val currentCode = current.redeemCode?.trim()
        val currentStrong = !GenericFieldHeuristics.isGenericOrMissingCode(currentCode)
        val candidate = modelJson.optNullableString(CouponSchemaKeys.REDEEM_CODE)
        val candidateStrong = !GenericFieldHeuristics.isGenericOrMissingCode(candidate)
        val supported = candidateStrong && OcrEvidenceValidator.isPhraseSupported(candidate, ocrText)
        val regressedField = currentStrong && (!candidateStrong || !sameText(currentCode, candidate) || !supported)
        if (regressedField) regressed += CouponSchemaKeys.REDEEM_CODE
        fieldDecisions.put(
            CouponSchemaKeys.REDEEM_CODE,
            JSONObject()
                .put("source", "PRESERVED")
                .put("model", candidate ?: "missing")
                .put("modelSupportedByOcr", supported)
                .put("decision", if (regressedField) "rejected_model_regression" else "preserved")
        )
    }

    private fun evaluateExpiryDate(
        current: Coupon,
        modelJson: JSONObject,
        fieldDecisions: JSONObject,
        regressed: MutableList<String>
    ) {
        val candidate = modelJson.optNullableString(CouponSchemaKeys.EXPIRY_DATE)
        val candidateIso = candidate?.takeIf(::isIsoDate)
        val invalidModelExpiry = candidate != null && !isUnknown(candidate) && candidateIso == null
        val regressedField = current.expiryDate != null && (invalidModelExpiry || candidateIso == null)
        if (regressedField || invalidModelExpiry) regressed += CouponSchemaKeys.EXPIRY_DATE
        fieldDecisions.put(
            CouponSchemaKeys.EXPIRY_DATE,
            JSONObject()
                .put("source", "PRESERVED")
                .put("model", candidate ?: "missing")
                .put("modelIsoValid", candidateIso != null)
                .put("decision", if (invalidModelExpiry) "rejected_invalid_non_iso_model_expiry" else "preserved")
        )
    }

    private fun hasWeakModelEvidence(modelJson: JSONObject): Boolean {
        val store = modelJson.optNullableString(CouponSchemaKeys.STORE_NAME)
        val source = modelJson.optNullableString(CouponSchemaKeys.STORE_NAME_SOURCE)
        val evidence = modelJson.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE)
        return GenericFieldHeuristics.isGenericOrMissing(store) ||
            source.isNullOrBlank() ||
            source.equals("unknown", ignoreCase = true) ||
            evidence == null ||
            evidence.length() == 0
    }

    private fun isDescriptionSupported(candidate: String, ocrText: String, currentDescription: String): Boolean {
        val evidenceText = listOf(ocrText, currentDescription)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (evidenceText.isBlank()) return true
        if (OcrEvidenceValidator.isPhraseSupported(candidate, evidenceText)) return true
        val evidenceTokens = evidenceText.lowercase(Locale.ROOT)
        val supportedTokenCount = candidate
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim(',', '.', '*', ':', ';', '(', ')') }
            .filter { it.length >= 3 }
            .count { token -> evidenceTokens.contains(token) }
        return supportedTokenCount >= 3
    }

    private fun hasSupportedNumbers(candidate: String, ocrText: String, currentDescription: String): Boolean {
        val candidateNumbers = numericTokens(candidate)
        if (candidateNumbers.isEmpty()) return true
        val evidenceNumbers = numericTokens("$ocrText\n$currentDescription").toSet()
        if (evidenceNumbers.isEmpty()) return false
        return candidateNumbers.all { it in evidenceNumbers }
    }

    private fun numericTokens(value: String): List<String> {
        return Regex("""\d[\d,.\s]*\d|\d""")
            .findAll(value)
            .map { match -> match.value.filter(Char::isDigit) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun isIsoDate(value: String): Boolean {
        if (!ISO_DATE.matches(value)) return false
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                isLenient = false
            }.parse(value) != null
        }.getOrDefault(false)
    }

    private fun isUnknown(value: String?): Boolean =
        value.isNullOrBlank() || value.trim().equals("unknown", ignoreCase = true)

    private fun sameText(left: String?, right: String?): Boolean =
        left?.trim()?.equals(right?.trim(), ignoreCase = true) == true
}

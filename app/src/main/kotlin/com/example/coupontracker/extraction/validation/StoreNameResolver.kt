package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.OcrEvidenceValidator

data class StoreNameResolution(
    val value: String?,
    val issue: FieldValidationIssue?,
    val source: String?,
    val evidence: List<String>,
    val needsAttention: Boolean,
    val violations: List<String>,
    val confidence: Float?
)

/**
 * Resolves store name candidates using the validator and exposes provenance metadata.
 */
internal class StoreNameResolver(
    private val validator: StoreNameValidator
) {

    fun resolve(
        current: String?,
        description: String?,
        redeemCode: String?,
        structuredCandidates: List<FieldCandidate>,
        rawOcrText: String? = null
    ): StoreNameResolution {
        val llmAssessment = validator.assessCandidate(current, description, redeemCode, source = "llm")
        val llmHasOcrEvidence = isSupportedByOcr(llmAssessment.canonical ?: llmAssessment.original, rawOcrText)
        if (llmAssessment.isAccepted && llmHasOcrEvidence) {
            val value = llmAssessment.canonical ?: llmAssessment.original
            return StoreNameResolution(
                value = value,
                issue = null,
                source = "llm",
                evidence = llmAssessment.signals.map { it.detail },
                needsAttention = llmAssessment.needsAttention,
                violations = llmAssessment.issues,
                confidence = null
            )
        }

        val structuredBest = structuredCandidates
            .filter { candidate -> isSupportedByOcr(candidate.value, rawOcrText) }
            .map { candidate ->
                candidate to validator.assessCandidate(
                    value = candidate.value,
                    description = description,
                    redeemCode = redeemCode,
                    source = candidate.source
                )
            }
            .filter { (_, assessment) -> assessment.isAccepted }
            .maxByOrNull { (_, assessment) -> assessment.highConfidenceCount }

        if (structuredBest != null) {
            val (candidate, assessment) = structuredBest
            val canonical = assessment.canonical ?: candidate.value
            val issue = FieldValidationIssue(
                field = FieldType.STORE_NAME,
                message = "Replaced invalid store name '${llmAssessment.original.orEmpty()}' with '${canonical}'",
                severity = FieldValidationSeverity.ERROR,
                replacementSource = candidate.source
            )
            return StoreNameResolution(
                value = canonical,
                issue = issue,
                source = candidate.source,
                evidence = assessment.signals.map { it.detail },
                needsAttention = assessment.needsAttention,
                violations = llmAssessment.issues + assessment.issues,
                confidence = candidate.confidence
            )
        }

        val fallbackValue = (llmAssessment.canonical ?: llmAssessment.original)
            ?.takeIf { llmHasOcrEvidence }
        val evidence = llmAssessment.signals.map { it.detail }
        val evidenceIssue = if (!llmHasOcrEvidence && !llmAssessment.original.isNullOrBlank()) {
            "ocr_evidence_missing"
        } else {
            null
        }
        val issue = FieldValidationIssue(
            field = FieldType.STORE_NAME,
            message = "Store name '${fallbackValue.orEmpty()}' failed validation and needs manual review",
            severity = FieldValidationSeverity.ERROR,
            replacementSource = null
        )

        val sanitized = fallbackValue?.takeUnless { GenericFieldHeuristics.isGenericOrMissing(it) }
        return StoreNameResolution(
            value = sanitized,
            issue = issue,
            source = "unresolved",
            evidence = evidence,
            needsAttention = true,
            violations = llmAssessment.issues + listOfNotNull(evidenceIssue),
            confidence = null
        )
    }

    private fun isSupportedByOcr(candidate: String?, rawOcrText: String?): Boolean {
        if (rawOcrText.isNullOrBlank()) return true
        return OcrEvidenceValidator.isPhraseSupported(candidate, rawOcrText)
    }
}

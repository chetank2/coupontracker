package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.util.GenericFieldHeuristics

/**
 * Resolves store name candidates using the validator and exposes provenance metadata.
 */
class StoreNameResolver(
    private val validator: StoreNameValidator
) {
    data class Resolution(
        val value: String?,
        val issue: FieldValidationIssue?,
        val source: String?,
        val evidence: List<String>,
        val needsAttention: Boolean
    )

    fun resolve(
        current: String?,
        description: String?,
        redeemCode: String?,
        structuredCandidates: List<FieldCandidate>,
        fallbackStore: String?
    ): Resolution {
        val llmAssessment = validator.assessCandidate(current, description, redeemCode, source = "llm")
        if (llmAssessment.isAccepted) {
            val value = llmAssessment.canonical ?: llmAssessment.original
            return Resolution(
                value = value,
                issue = null,
                source = "llm",
                evidence = llmAssessment.signals.map { it.detail },
                needsAttention = llmAssessment.needsAttention
            )
        }

        val structuredBest = structuredCandidates
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
            return Resolution(
                value = canonical,
                issue = issue,
                source = candidate.source,
                evidence = assessment.signals.map { it.detail },
                needsAttention = assessment.needsAttention
            )
        }

        val fallbackAssessment = validator.assessCandidate(
            value = fallbackStore,
            description = description,
            redeemCode = redeemCode,
            source = "text_extractor"
        )

        if (fallbackAssessment.isAccepted) {
            val canonical = fallbackAssessment.canonical ?: fallbackStore?.trim()
            val issue = FieldValidationIssue(
                field = FieldType.STORE_NAME,
                message = "Replaced invalid store name '${llmAssessment.original.orEmpty()}' with fallback '${canonical.orEmpty()}'",
                severity = FieldValidationSeverity.ERROR,
                replacementSource = "text_extractor"
            )
            return Resolution(
                value = canonical,
                issue = issue,
                source = "text_extractor",
                evidence = fallbackAssessment.signals.map { it.detail },
                needsAttention = fallbackAssessment.needsAttention
            )
        }

        val fallbackValue = llmAssessment.canonical ?: llmAssessment.original
        val evidence = (llmAssessment.signals.takeIf { it.isNotEmpty() }
            ?: fallbackAssessment.signals)
            .map { it.detail }
        val issue = FieldValidationIssue(
            field = FieldType.STORE_NAME,
            message = "Store name '${fallbackValue.orEmpty()}' failed validation and needs manual review",
            severity = FieldValidationSeverity.ERROR,
            replacementSource = null
        )

        val sanitized = fallbackValue?.takeUnless { GenericFieldHeuristics.isGenericOrMissing(it) }
        return Resolution(
            value = sanitized,
            issue = issue,
            source = "unresolved",
            evidence = evidence,
            needsAttention = true
        )
    }
}


package com.example.coupontracker.feedback

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.repository.FeedbackDatasetRepository
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.validation.FieldValidationIssue
import com.example.coupontracker.extraction.validation.FieldValidationSummary
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.universal.UniversalExtractionResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ValidatorFeedbackRecorder @Inject constructor(
    private val feedbackDatasetRepository: FeedbackDatasetRepository
) {
    fun recordValidatorOverride(
        rawOcrText: String?,
        initialBundle: FieldValueBundle,
        summary: FieldValidationSummary,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>,
        metadata: Map<String, Any?>
    ) {
        val outcomes = buildOverrideOutcomes(initialBundle, summary, structuredCandidates)
        if (outcomes.isEmpty()) {
            return
        }

        val rationale = mapOf(
            "issueCount" to summary.issues.size,
            "storeNeedsAttention" to summary.storeResolution.needsAttention,
            "storeViolations" to summary.storeResolution.violations,
            "storeEvidence" to summary.storeResolution.evidence
        )

        val event = ValidatorFeedbackEvent(
            eventType = ValidatorFeedbackEvent.EventType.VALIDATOR_OVERRIDE,
            ocrText = rawOcrText,
            fieldOutcomes = outcomes,
            rationale = rationale,
            metadata = metadata
        )

        feedbackDatasetRepository.recordEvent(event)
    }

    fun recordUserCorrection(
        rawOcrText: String?,
        extractionResult: UniversalExtractionResult,
        correctedCoupon: Coupon,
        metadata: Map<String, Any?>
    ) {
        val outcomes = buildCorrectionOutcomes(extractionResult, correctedCoupon)
        if (outcomes.isEmpty()) {
            return
        }

        val rationale = mapOf(
            "correctedFields" to outcomes.map { it.field.name },
            "confidence" to extractionResult.confidence
        )

        val event = ValidatorFeedbackEvent(
            eventType = ValidatorFeedbackEvent.EventType.USER_CORRECTION,
            ocrText = rawOcrText,
            fieldOutcomes = outcomes,
            rationale = rationale,
            metadata = metadata
        )

        feedbackDatasetRepository.recordEvent(event)
    }

    private fun buildOverrideOutcomes(
        initial: FieldValueBundle,
        summary: FieldValidationSummary,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>
    ): List<ValidatorFeedbackEvent.FieldOutcome> {
        val issuesByField = summary.issues.groupBy { it.field }
        val outcomes = mutableListOf<ValidatorFeedbackEvent.FieldOutcome>()

        fun resolveStatus(field: FieldType, before: String?, after: String?, issue: FieldValidationIssue?): String {
            return when {
                issue != null && before != after -> "replaced"
                issue != null -> "needs_review"
                else -> "unchanged"
            }
        }

        val storeIssue = issuesByField[FieldType.STORE_NAME]?.firstOrNull()
        outcomes += ValidatorFeedbackEvent.FieldOutcome(
            field = FieldType.STORE_NAME,
            originalValue = initial.storeName,
            resolvedValue = summary.fields.storeName,
            confidence = summary.storeResolution.confidence,
            status = resolveStatus(FieldType.STORE_NAME, initial.storeName, summary.fields.storeName, storeIssue),
            ruleViolations = summary.storeResolution.violations,
            evidence = summary.storeResolution.evidence,
            replacementSource = storeIssue?.replacementSource
        )

        val descriptionIssue = issuesByField[FieldType.DESCRIPTION]?.firstOrNull()
        if (initial.description != summary.fields.description || descriptionIssue != null) {
            outcomes += ValidatorFeedbackEvent.FieldOutcome(
                field = FieldType.DESCRIPTION,
                originalValue = initial.description,
                resolvedValue = summary.fields.description,
                confidence = null,
                status = resolveStatus(FieldType.DESCRIPTION, initial.description, summary.fields.description, descriptionIssue),
                ruleViolations = descriptionIssue?.let { listOf(it.message) } ?: emptyList(),
                evidence = emptyList(),
                replacementSource = descriptionIssue?.replacementSource
            )
        }

        val expiryIssue = issuesByField[FieldType.EXPIRY_DATE]?.firstOrNull()
        if (initial.expiryDateText != summary.fields.expiryDateText || expiryIssue != null) {
            val structuredConfidence = structuredCandidates[FieldType.EXPIRY_DATE]
                ?.firstOrNull { it.value == summary.fields.expiryDateText }
                ?.confidence
            outcomes += ValidatorFeedbackEvent.FieldOutcome(
                field = FieldType.EXPIRY_DATE,
                originalValue = initial.expiryDateText,
                resolvedValue = summary.fields.expiryDateText,
                confidence = structuredConfidence,
                status = resolveStatus(FieldType.EXPIRY_DATE, initial.expiryDateText, summary.fields.expiryDateText, expiryIssue),
                ruleViolations = expiryIssue?.let { listOf(it.message) } ?: emptyList(),
                evidence = emptyList(),
                replacementSource = expiryIssue?.replacementSource
            )
        }

        return outcomes.filterNot { outcome ->
            outcome.originalValue == outcome.resolvedValue && outcome.ruleViolations.isEmpty()
        }
    }

    private fun buildCorrectionOutcomes(
        extractionResult: UniversalExtractionResult,
        correctedCoupon: Coupon
    ): List<ValidatorFeedbackEvent.FieldOutcome> {
        val outcomes = mutableListOf<ValidatorFeedbackEvent.FieldOutcome>()
        val extractedFields = extractionResult.extractedFields

        fun addIfChanged(fieldType: FieldType, original: String?, corrected: String?) {
            if ((original ?: "") == (corrected ?: "")) return
            val confidence = extractedFields[fieldType]?.confidence
            outcomes += ValidatorFeedbackEvent.FieldOutcome(
                field = fieldType,
                originalValue = original,
                resolvedValue = corrected,
                confidence = confidence,
                status = "user_corrected",
                ruleViolations = listOf("user_override"),
                evidence = emptyList(),
                replacementSource = "user"
            )
        }

        addIfChanged(FieldType.STORE_NAME, extractionResult.coupon.storeName, correctedCoupon.storeName)
        addIfChanged(FieldType.COUPON_CODE, extractionResult.coupon.redeemCode, correctedCoupon.redeemCode)
        addIfChanged(
            FieldType.EXPIRY_DATE,
            extractionResult.coupon.expiryDate?.toString(),
            correctedCoupon.expiryDate?.toString()
        )
        addIfChanged(
            FieldType.DESCRIPTION,
            extractionResult.coupon.description,
            correctedCoupon.description
        )

        return outcomes
    }
}

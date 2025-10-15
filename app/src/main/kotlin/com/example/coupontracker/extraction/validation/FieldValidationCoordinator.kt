package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.TextExtractor
import java.util.Date

/**
 * Summary of field validation containing the normalized fields and any issues raised.
 */
data class FieldValidationSummary(
    val fields: FieldValueBundle,
    val issues: List<FieldValidationIssue>
)

/**
 * Container for the string-based coupon fields that we validate and repair.
 */
data class FieldValueBundle(
    val storeName: String?,
    val description: String?,
    val redeemCode: String?,
    val expiryDateText: String?
)

/**
 * Severity of a field validation issue.
 */
enum class FieldValidationSeverity {
    WARNING,
    ERROR
}

/**
 * Details about a validation issue, including which field was affected and how it was repaired.
 */
data class FieldValidationIssue(
    val field: FieldType,
    val message: String,
    val severity: FieldValidationSeverity,
    val replacementSource: String?
)

/**
 * Coordinates validation of the core coupon fields returned by the LLM.
 * Uses deterministic heuristics and structured extraction fallbacks to keep
 * store, description and expiry decisions isolated from one another.
 */
class FieldValidationCoordinator(
    private val textExtractor: TextExtractor,
    private val storeNameValidator: StoreNameValidator = StoreNameValidator(),
    private val descriptionValidator: DescriptionValidator = DescriptionValidator(),
    private val expiryDateValidator: ExpiryDateValidator = ExpiryDateValidator()
) {
    fun refine(
        initial: FieldValueBundle,
        rawOcrText: String?,
        captureTimestamp: Date?,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>
    ): FieldValidationSummary {
        val issues = mutableListOf<FieldValidationIssue>()
        val structuredStoreCandidates = structuredCandidates[FieldType.STORE_NAME].orEmpty()
        val structuredExpiryCandidates = structuredCandidates[FieldType.EXPIRY_DATE].orEmpty()

        val fallbackInfo = runCatching {
            if (rawOcrText.isNullOrBlank()) {
                null
            } else {
                textExtractor.extractCouponInfoSync(rawOcrText, captureTimestamp)
            }
        }.getOrNull()

        var bundle = initial

        val storeDecision = storeNameValidator.repair(
            current = bundle.storeName,
            description = bundle.description,
            redeemCode = bundle.redeemCode,
            structuredCandidates = structuredStoreCandidates,
            fallbackStore = fallbackInfo?.storeName?.takeUnless { GenericFieldHeuristics.isGenericOrMissing(it) }
        )
        bundle = bundle.copy(storeName = storeDecision.value)
        storeDecision.issue?.let { issues += it }

        val descriptionDecision = descriptionValidator.repair(
            current = bundle.description,
            storeName = bundle.storeName,
            redeemCode = bundle.redeemCode,
            fallbackDescription = fallbackInfo?.description?.takeUnless { GenericFieldHeuristics.isGenericOrMissing(it) }
        )
        bundle = bundle.copy(description = descriptionDecision.value)
        descriptionDecision.issue?.let { issues += it }

        val expiryDecision = expiryDateValidator.repair(
            current = bundle.expiryDateText,
            structuredCandidates = structuredExpiryCandidates,
            fallbackDate = fallbackInfo?.expiryDate
        )
        bundle = bundle.copy(expiryDateText = expiryDecision.value)
        expiryDecision.issue?.let { issues += it }

        return FieldValidationSummary(bundle, issues)
    }
}

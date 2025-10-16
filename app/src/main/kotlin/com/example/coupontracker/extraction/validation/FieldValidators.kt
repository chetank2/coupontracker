package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.util.DateParser
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.IndianDateParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class FieldRepairDecision(
    val value: String?,
    val issue: FieldValidationIssue? = null
)

class StoreNameValidator {
    fun repair(
        current: String?,
        description: String?,
        redeemCode: String?,
        structuredCandidates: List<FieldCandidate>,
        fallbackStore: String?
    ): FieldRepairDecision {
        val normalized = current?.trim()
        if (isValid(normalized, description, redeemCode)) {
            return FieldRepairDecision(normalized)
        }

        val structured = structuredCandidates.firstOrNull { candidate ->
            isValid(candidate.value, description, redeemCode)
        }
        if (structured != null) {
            return FieldRepairDecision(
                value = structured.value,
                issue = FieldValidationIssue(
                    field = FieldType.STORE_NAME,
                    message = "Replaced invalid store name '${normalized.orEmpty()}' with '${structured.value}'",
                    severity = FieldValidationSeverity.ERROR,
                    replacementSource = structured.source
                )
            )
        }

        val fallback = fallbackStore?.trim()
        if (isValid(fallback, description, redeemCode)) {
            return FieldRepairDecision(
                value = fallback,
                issue = FieldValidationIssue(
                    field = FieldType.STORE_NAME,
                    message = "Replaced invalid store name '${normalized.orEmpty()}' with fallback '$fallback'",
                    severity = FieldValidationSeverity.ERROR,
                    replacementSource = "text_extractor"
                )
            )
        }

        return FieldRepairDecision(
            value = normalized,
            issue = FieldValidationIssue(
                field = FieldType.STORE_NAME,
                message = "Store name '${normalized.orEmpty()}' failed validation and no fallback was available",
                severity = FieldValidationSeverity.ERROR,
                replacementSource = null
            )
        )
    }

    private fun isValid(value: String?, description: String?, redeemCode: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.trim()
        if (normalized.equals("unknown", ignoreCase = true)) return false
        if (normalized.equals("unknown store", ignoreCase = true)) return false
        if (normalized.length < 3) return false
        if (!normalized.any { it.isLetter() }) return false
        if (GenericFieldHeuristics.isGenericOrMissing(normalized)) return false
        if (GenericFieldHeuristics.areDuplicateFields(normalized, redeemCode)) return false
        if (GenericFieldHeuristics.areDuplicateFields(normalized, description)) return false
        return true
    }
}

class DescriptionValidator {
    fun repair(
        current: String?,
        storeName: String?,
        redeemCode: String?,
        fallbackDescription: String?
    ): FieldRepairDecision {
        val normalized = current?.trim()
        if (isValid(normalized, storeName, redeemCode)) {
            return FieldRepairDecision(normalized)
        }

        val fallback = fallbackDescription?.trim()
        if (isValid(fallback, storeName, redeemCode)) {
            return FieldRepairDecision(
                value = fallback,
                issue = FieldValidationIssue(
                    field = FieldType.DESCRIPTION,
                    message = "Replaced invalid description with fallback text",
                    severity = FieldValidationSeverity.WARNING,
                    replacementSource = "text_extractor"
                )
            )
        }

        return FieldRepairDecision(
            value = normalized,
            issue = FieldValidationIssue(
                field = FieldType.DESCRIPTION,
                message = "Description validation failed and fallback was unavailable",
                severity = FieldValidationSeverity.WARNING,
                replacementSource = null
            )
        )
    }

    private fun isValid(value: String?, storeName: String?, redeemCode: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.trim()
        if (normalized.length < 4) return false
        if (GenericFieldHeuristics.isGenericOrMissing(normalized)) return false
        if (GenericFieldHeuristics.areDuplicateFields(normalized, storeName)) return false
        if (GenericFieldHeuristics.areDuplicateFields(normalized, redeemCode)) return false
        return true
    }
}

class ExpiryDateValidator {
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val relativePattern = Regex("(?i)expires?\\s+in\\s+\\d+\\s+(days?|weeks?|months?|hrs?|hours?)")

    fun repair(
        current: String?,
        structuredCandidates: List<FieldCandidate>,
        fallbackDate: Date?
    ): FieldRepairDecision {
        val normalized = current?.trim()
        if (isValid(normalized)) {
            return FieldRepairDecision(normalized)
        }

        val structured = structuredCandidates.firstOrNull { candidate ->
            isValid(candidate.value)
        }
        if (structured != null) {
            return FieldRepairDecision(
                value = structured.value,
                issue = FieldValidationIssue(
                    field = FieldType.EXPIRY_DATE,
                    message = "Replaced invalid expiry '${normalized.orEmpty()}' with structured candidate '${structured.value}'",
                    severity = FieldValidationSeverity.WARNING,
                    replacementSource = structured.source
                )
            )
        }

        val fallback = fallbackDate?.let { isoFormatter.format(it) }
        if (isValid(fallback)) {
            return FieldRepairDecision(
                value = fallback,
                issue = FieldValidationIssue(
                    field = FieldType.EXPIRY_DATE,
                    message = "Replaced invalid expiry '${normalized.orEmpty()}' with fallback date '$fallback'",
                    severity = FieldValidationSeverity.WARNING,
                    replacementSource = "text_extractor"
                )
            )
        }

        return FieldRepairDecision(
            value = normalized,
            issue = FieldValidationIssue(
                field = FieldType.EXPIRY_DATE,
                message = "Expiry date validation failed and no reliable fallback was available",
                severity = FieldValidationSeverity.WARNING,
                replacementSource = null
            )
        )
    }

    private fun isValid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        val normalized = value.trim()
        if (relativePattern.containsMatchIn(normalized)) return true
        if (IndianDateParser.parseExpiryIST(normalized).date != null) return true
        if (IndianDateParser.extractExpiryFromText(normalized).date != null) return true
        if (DateParser.parseDate(normalized) != null) return true
        return false
    }
}

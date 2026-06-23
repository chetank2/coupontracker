package com.example.coupontracker.extraction.validation

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.IndianDateParser
import java.util.Locale

/**
 * Final generic gate for coupon field trust.
 *
 * This validator is intentionally field/evidence based. It must not contain
 * merchant-specific patches. Brand failures should become evidence rules such
 * as label proximity, token shape, region membership, or field quality.
 */
class CouponFieldBundleValidator(
    private val spatialValidator: SpatialFieldConsistencyValidator = SpatialFieldConsistencyValidator()
) {
    data class Result(
        val trusted: Boolean,
        val issues: List<Issue>,
        val spatialResult: SpatialFieldConsistencyValidator.Result
    ) {
        val needsAttention: Boolean
            get() = issues.isNotEmpty() || !spatialResult.consistent

        val reason: String?
            get() = buildList {
                spatialResult.reason?.let(::add)
                addAll(issues.map { "${it.field.name}: ${it.message}" })
            }.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    data class Issue(
        val field: FieldType,
        val message: String,
        val severity: Severity
    )

    enum class Severity {
        WARNING,
        ERROR
    }

    fun validate(
        bundle: FieldValueBundle,
        fields: Map<FieldType, FieldCandidate>,
        rawOcrText: String?,
        ocrBlocks: List<TextBlock>,
        imageHeight: Int
    ): Result {
        val issues = mutableListOf<Issue>()
        val evidenceText = rawOcrText.orEmpty()

        validateStore(bundle, issues)
        validateDescription(bundle, issues)
        validateSingleCouponEvidence(evidenceText, issues)
        validateCode(bundle, fields[FieldType.COUPON_CODE], evidenceText, issues)
        validateExpiry(bundle, issues)

        val spatialResult = spatialValidator.validate(
            fields = fields.filterKeys { it in PRIMARY_FIELDS },
            ocrBlocks = ocrBlocks,
            imageHeight = imageHeight
        )

        val trusted = spatialResult.consistent && issues.none { it.severity == Severity.ERROR }
        return Result(
            trusted = trusted,
            issues = issues,
            spatialResult = spatialResult
        )
    }

    private fun validateStore(bundle: FieldValueBundle, issues: MutableList<Issue>) {
        val store = bundle.storeName?.trim()
        if (GenericFieldHeuristics.isGenericOrMissing(store)) {
            issues += Issue(FieldType.STORE_NAME, "missing_or_generic_store", Severity.WARNING)
            return
        }

        if (GenericFieldHeuristics.areDuplicateFields(store, bundle.redeemCode)) {
            issues += Issue(FieldType.STORE_NAME, "store_duplicates_code", Severity.ERROR)
        }

        if (store != null && looksLikeStandaloneCode(store)) {
            issues += Issue(FieldType.STORE_NAME, "store_looks_like_code", Severity.WARNING)
        }
    }

    private fun validateDescription(bundle: FieldValueBundle, issues: MutableList<Issue>) {
        val description = bundle.description?.trim()
        if (!GenericFieldHeuristics.isMeaningfulDescription(description)) {
            issues += Issue(FieldType.DESCRIPTION, "weak_or_generic_description", Severity.WARNING)
            return
        }

        if (GenericFieldHeuristics.areDuplicateFields(description, bundle.redeemCode)) {
            issues += Issue(FieldType.DESCRIPTION, "description_duplicates_code", Severity.ERROR)
        }
    }

    private fun validateSingleCouponEvidence(rawOcrText: String, issues: MutableList<Issue>) {
        if (rawOcrText.isBlank()) return

        val expiryCount = Regex("""(?i)\b(?:expires?|expiring|valid)\s+(?:in|on|till|until|within)\b""")
            .findAll(rawOcrText)
            .count()
        val redeemActionCount = Regex("""(?i)\b(?:redeem\s+now|details|copy|tap\s+to\s+copy)\b""")
            .findAll(rawOcrText)
            .count()
        val labeledCodeCount = Regex("""(?i)\b(?:code|coupon\s*code|promo\s*code)\s*[:#]?\s*[A-Z0-9_-]{4,}\b""")
            .findAll(rawOcrText)
            .count()

        val repeatedCouponSignals = listOf(
            expiryCount >= 2,
            redeemActionCount >= 2,
            labeledCodeCount >= 2
        ).count { it }

        if (repeatedCouponSignals >= 2) {
            issues += Issue(
                FieldType.DESCRIPTION,
                "multiple_coupon_sections_in_single_region",
                Severity.ERROR
            )
        }
    }

    private fun validateCode(
        bundle: FieldValueBundle,
        @Suppress("UNUSED_PARAMETER") candidate: FieldCandidate?,
        rawOcrText: String,
        issues: MutableList<Issue>
    ) {
        val code = bundle.redeemCode?.trim()?.takeIf { it.isNotBlank() } ?: return

        if (GenericFieldHeuristics.isGenericOrMissingCode(code)) {
            issues += Issue(FieldType.COUPON_CODE, "invalid_or_placeholder_code", Severity.WARNING)
            return
        }

        if (GenericFieldHeuristics.areDuplicateFields(code, bundle.storeName)) {
            issues += Issue(FieldType.COUPON_CODE, "code_duplicates_store", Severity.ERROR)
        }

        val hasExplicitEvidence = hasCodeLabelEvidence(code, rawOcrText)

        if (isAlphaOnlyCodeLikeToken(code) && !hasExplicitEvidence) {
            issues += Issue(
                FieldType.COUPON_CODE,
                "alpha_only_code_without_label_evidence",
                Severity.ERROR
            )
        } else if (!hasExplicitEvidence && !looksLikeStandaloneCode(code)) {
            issues += Issue(FieldType.COUPON_CODE, "weak_code_evidence", Severity.WARNING)
        }
    }

    private fun validateExpiry(bundle: FieldValueBundle, issues: MutableList<Issue>) {
        val expiry = bundle.expiryDateText?.trim()?.takeIf { it.isNotBlank() } ?: return
        val parsed = IndianDateParser.parseExpiryIST(expiry).date
            ?: IndianDateParser.extractExpiryFromText(expiry).date
        if (parsed == null && !ISO_DATE.matches(expiry)) {
            issues += Issue(FieldType.EXPIRY_DATE, "expiry_not_normalized_or_parseable", Severity.WARNING)
        }
    }

    private fun hasCodeLabelEvidence(code: String, rawOcrText: String): Boolean {
        if (rawOcrText.isBlank()) return false
        val escapedCode = Regex.escape(code)
        return Regex(
                "(?is)\\b(code|coupon\\s*code|promo\\s*code|apply|use|redeem)\\b.{0,40}\\b$escapedCode\\b"
            ).containsMatchIn(rawOcrText) ||
            Regex(
                "(?is)\\b$escapedCode\\b.{0,40}\\b(copy|copied|apply|use|redeem)\\b"
            ).containsMatchIn(rawOcrText)
    }

    private fun looksLikeStandaloneCode(value: String): Boolean {
        val normalized = value.trim().uppercase(Locale.ROOT)
        if (normalized.length !in 5..40) return false
        if (!normalized.matches(Regex("^[A-Z0-9_-]+$"))) return false
        return normalized.any(Char::isDigit) || normalized.contains('_') || normalized.contains('-')
    }

    private fun isAlphaOnlyCodeLikeToken(value: String): Boolean {
        val normalized = value.trim().uppercase(Locale.ROOT)
        return normalized.length >= 5 && normalized.matches(Regex("^[A-Z]+$"))
    }

    private companion object {
        private val PRIMARY_FIELDS = setOf(
            FieldType.STORE_NAME,
            FieldType.DESCRIPTION,
            FieldType.COUPON_CODE,
            FieldType.EXPIRY_DATE
        )
        private val ISO_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
    }
}

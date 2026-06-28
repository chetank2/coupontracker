package com.example.coupontracker.extraction.validation

import android.util.Log
import com.example.coupontracker.analytics.StoreNameMetricsTracker
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

internal class StoreNameValidator(
    private val brandLexicon: BrandLexicon = BrandLexicon.empty(),
    private val sourceLogger: (StoreSourceLog) -> Unit = {}
) {
    data class Signal(
        val category: String,
        val detail: String,
        val tier: Int
    )

    data class Assessment(
        val original: String?,
        val canonical: String?,
        val normalized: String?,
        val signals: List<Signal>,
        val issues: List<String>,
        val highConfidenceCount: Int,
        val isAccepted: Boolean,
        val needsAttention: Boolean
    )

    data class StoreSourceLog(
        val source: String,
        val candidate: String?,
        val canonical: String?,
        val signals: List<Signal>,
        val issues: List<String>
    )

    private val ctaStopwords: Set<String> =
        if (brandLexicon.ctaStopwords.isEmpty()) DEFAULT_CTA_STOPWORDS else brandLexicon.ctaStopwords

    private val layoutStopwords = setOf(
        "minimum",
        "order",
        "value",
        "validity",
        "details",
        "flat",
        "save",
        "discount",
        "claim",
        "faq",
        "faqs"
    )

    fun assessCandidate(
        value: String?,
        description: String?,
        redeemCode: String?,
        source: String
    ): Assessment {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        val signals = mutableListOf<Signal>()
        val issues = mutableListOf<String>()
        var canonical: String? = trimmed
        var normalized: String? = trimmed?.lowercase(Locale.ROOT)

        if (trimmed == null) {
            val assessment = Assessment(null, null, null, emptyList(), listOf("empty"), 0, false, true)
            logAssessment(source, assessment)
            return assessment
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        if (ctaStopwords.any { lower.contains(it) }) {
            issues += "cta_stopword"
        }

        if (layoutStopwords.any { lower.contains(it) }) {
            issues += "layout_token"
        }

        if (trimmed.length < 3) issues += "too_short"
        if (!trimmed.any { it.isLetter() }) issues += "no_letters"
        if (trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("unknown store", ignoreCase = true)) {
            issues += "unknown_token"
        }

        if (GenericFieldHeuristics.isGenericOrMissing(trimmed)) {
            issues += "generic"
        } else {
            signals += Signal("heuristic", "non_generic", tier = 3)
        }

        if (GenericFieldHeuristics.areDuplicateFields(trimmed, redeemCode)) {
            issues += "duplicate_code"
        }
        if (GenericFieldHeuristics.areDuplicateFields(trimmed, description)) {
            issues += "duplicate_description"
        }

        val brandMatch = brandLexicon.match(trimmed)
        if (brandMatch != null) {
            canonical = brandMatch.entry.canonical
            normalized = brandMatch.alias
            signals += Signal("lexicon", "alias:${brandMatch.alias}", tier = brandMatch.priority)
        }

        if (issues.isEmpty()) {
            signals += Signal("source", "${source.lowercase()}", tier = 3)
            if (trimmed.any { it.isUpperCase() }) {
                signals += Signal("shape", "capitalized", tier = 4)
            }
        }

        val uniqueCategories = signals.map { it.category }.toSet()
        val highConfidenceCount = signals.count { it.tier <= 2 }
        val meetsSignalThreshold = uniqueCategories.size >= 2
        val isAccepted = issues.isEmpty() && meetsSignalThreshold
        // @critical-invariant: require at least two independent signal categories before accepting store name.
        val needsAttention = !isAccepted || highConfidenceCount == 0

        val assessment = Assessment(
            original = trimmed,
            canonical = canonical,
            normalized = normalized,
            signals = signals,
            issues = issues,
            highConfidenceCount = highConfidenceCount,
            isAccepted = isAccepted,
            needsAttention = needsAttention
        )
        StoreNameMetricsTracker.recordAssessment(source, assessment)
        logAssessment(source, assessment)
        return assessment
    }

    private fun logAssessment(source: String, assessment: Assessment) {
        val log = StoreSourceLog(
            source = source,
            candidate = assessment.original,
            canonical = assessment.canonical,
            signals = assessment.signals,
            issues = assessment.issues
        )
        sourceLogger(log)
        if (LOGGING_ENABLED) {
            runCatching {
                Log.d(
                    TAG,
                    "store-source=${log.source} candidate='${log.candidate}' issues=${log.issues} signals=${log.signals}"
                )
            }
        }
    }

    companion object {
        private const val TAG = "StoreNameValidator"
        private val DEFAULT_CTA_STOPWORDS = setOf(
            "apply now",
            "claim now",
            "tap to claim",
            "use coupon",
            "copy code",
            "redeem now"
        )
        private const val LOGGING_ENABLED = true
    }
}

internal class DescriptionValidator {
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

internal class ExpiryDateValidator {
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

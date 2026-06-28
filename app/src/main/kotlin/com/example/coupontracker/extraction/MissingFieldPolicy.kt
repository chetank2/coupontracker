package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.OcrTextCleaner
import java.util.Locale

/**
 * Centralizes review-safe defaults for fields the OCR-first pipeline could not prove.
 */
object MissingFieldPolicy {
    const val REVIEW_DESCRIPTION_NOT_VISIBLE = "Needs review: description not visible"
    const val EXPLICIT_NO_CODE_SOURCE = "explicit_no_code_evidence"

    private val placeholderValues = setOf(
        "UNKNOWN",
        "NA",
        "N/A",
        "NONE",
        "NULL",
        "NO CODE",
        Coupon.CodeState.NO_CODE_NEEDED,
        "NO CODE NEEDED",
        "NOCO",
        "TBD",
        "-",
        "--"
    )

    fun unknownStoreName(): String = Coupon.Defaults.UNKNOWN_STORE

    fun reviewDescription(): String = REVIEW_DESCRIPTION_NOT_VISIBLE

    fun explicitNoCodeValue(): String = Coupon.CodeState.NO_CODE_NEEDED

    fun hasExplicitNoCodeEvidence(text: String): Boolean {
        val normalized = text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return listOf(
            Regex("\\bno\\s+code\\s+(?:needed|required)\\b"),
            Regex("\\bno\\s+coupon\\s+code\\s+(?:needed|required)\\b"),
            Regex("\\bcode\\s+(?:not\\s+)?required\\b"),
            Regex("\\bwithout\\s+(?:a\\s+)?(?:coupon\\s+)?code\\b"),
            Regex("\\bauto(?:matically)?\\s+applied\\b")
        ).any { it.containsMatchIn(normalized) }
    }

    fun lowConfidenceDescriptionFromOcr(ocrText: String): String? {
        val cleaned = OcrTextCleaner.cleanOcrText(ocrText).take(200).trim()
        if (isReviewSafeDescription(cleaned)) {
            return cleaned
        }

        val raw = ocrText.take(200).trim()
        return raw.takeIf(::isReviewSafeDescription)
    }

    fun isReviewSafeDescription(description: String): Boolean {
        val normalized = description.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return false
        if (!GenericFieldHeuristics.isMeaningfulDescription(description)) return false
        val disqualifiers = listOf(
            "tap to view", "swipe", "screenshot", "copy code", "details", "scan to pay",
            "shop now", "click here", "open app", "android", "ios", "claim now", "apply now",
            "download", "loyalty", "profile", "limited time", "verify", "coupon offer"
        )
        return disqualifiers.none { normalized.contains(it) }
    }

    fun isPlaceholderValue(value: String?): Boolean {
        val normalized = value?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return normalized.isEmpty() || normalized in placeholderValues
    }

    fun isPlaceholderCandidate(
        fieldType: FieldType,
        candidate: FieldCandidate?,
        validStoreName: (String) -> Boolean
    ): Boolean {
        val value = candidate?.value?.trim().orEmpty()
        if (isPlaceholderValue(value)) return true

        return when (fieldType) {
            FieldType.STORE_NAME -> !validStoreName(value)
            FieldType.DESCRIPTION -> !GenericFieldHeuristics.isMeaningfulDescription(value)
            FieldType.COUPON_CODE -> GenericFieldHeuristics.isGenericOrMissingCode(value) || value.length < 4
            FieldType.EXPIRY_DATE -> isPlaceholderValue(value) || value.length < 4
            else -> false
        }
    }
}

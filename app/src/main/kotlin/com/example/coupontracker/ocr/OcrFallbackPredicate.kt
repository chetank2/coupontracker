package com.example.coupontracker.ocr

import java.util.regex.Pattern

/**
 * Given an MLKit OCR result, returns the reason Tesseract should run as
 * fallback, or `NONE` if MLKit was sufficient.
 */
fun interface OcrFallbackPredicate {
    fun evaluate(primary: OcrResult): OcrFallbackReason
}

data class OcrResult(
    val text: String,
    val spans: List<OcrTextSpan>,
    val meanConfidence: Float
)

object OcrFallbackPredicates {

    const val MIN_TEXT_CHARS = 20
    const val MIN_CONFIDENCE = 0.55f
    private val CODE_LIKE = Pattern.compile("\\b[A-Z0-9]{4,}\\b")
    private val DATE_LIKE = Pattern.compile(
        "\\b\\d{1,2}[/\\-\\s](?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|\\d{1,2})[/\\-\\s]\\d{2,4}\\b",
        Pattern.CASE_INSENSITIVE
    )

    val TOO_LITTLE_TEXT = OcrFallbackPredicate { r ->
        if (r.text.trim().length < MIN_TEXT_CHARS) OcrFallbackReason.TOO_LITTLE_TEXT
        else OcrFallbackReason.NONE
    }

    val NO_CODE_REGION = OcrFallbackPredicate { r ->
        if (!CODE_LIKE.matcher(r.text).find()) OcrFallbackReason.NO_CODE_REGION
        else OcrFallbackReason.NONE
    }

    val NO_DATE_REGION = OcrFallbackPredicate { r ->
        if (!DATE_LIKE.matcher(r.text).find()) OcrFallbackReason.NO_DATE_REGION
        else OcrFallbackReason.NONE
    }

    val LOW_CONFIDENCE = OcrFallbackPredicate { r ->
        if (r.meanConfidence < MIN_CONFIDENCE) OcrFallbackReason.LOW_CONFIDENCE
        else OcrFallbackReason.NONE
    }

    /** In order of severity — first non-NONE wins. */
    val DEFAULT_CHAIN: List<OcrFallbackPredicate> = listOf(
        TOO_LITTLE_TEXT,
        LOW_CONFIDENCE,
        NO_CODE_REGION,
        NO_DATE_REGION
    )
}

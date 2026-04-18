package com.example.coupontracker.ocr

enum class OcrFallbackReason {
    /** MLKit returned fewer than MIN_TEXT_CHARS useful characters. */
    TOO_LITTLE_TEXT,
    /** No token looks like a coupon code (uppercase alnum run ≥ 4 chars). */
    NO_CODE_REGION,
    /** No token parses as a date or date-ish phrase. */
    NO_DATE_REGION,
    /** Aggregate recognition confidence below threshold. */
    LOW_CONFIDENCE,
    /** Never fires; sentinel for "no fallback needed". */
    NONE
}

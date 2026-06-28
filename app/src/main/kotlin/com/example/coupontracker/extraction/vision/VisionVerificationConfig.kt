package com.example.coupontracker.extraction.vision

internal object VisionVerificationConfig {
    const val FIELD_LABEL_TIMEOUT_MS = 90_000L
    const val LAYOUT_TIMEOUT_MS = 45_000L

    const val MIN_OCR_CROP_ANCHORS = 2
    const val MIN_OCR_CROP_PADDING_PX = 120
    const val OCR_CROP_VERTICAL_PADDING_RATIO = 0.08f
    const val OCR_CROP_HORIZONTAL_PADDING_RATIO = 0.04f
    const val MAX_OCR_CROP_HEIGHT_RATIO = 0.52f

    const val MIN_LAYOUT_CONFIDENCE = 0.5f
    const val LAYOUT_CROP_PADDING_RATIO = 0.07f
    const val MAX_LAYOUT_CROP_WIDTH_RATIO = 0.92f
    const val MAX_LAYOUT_CROP_HEIGHT_RATIO = 0.68f
    const val MAX_LAYOUT_CROP_AREA_RATIO = 0.62f
}

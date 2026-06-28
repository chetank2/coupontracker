package com.example.coupontracker.extraction.capture

import com.example.coupontracker.ml.ScreenshotClassifier

data class FullImageFallbackDecision(
    val allowDirectOcr: Boolean,
    val reason: String
)

fun decideFullImageFallback(
    classification: ScreenshotClassifier.ClassificationResult,
    rawOcrText: String,
    detectedRegionCount: Int = 0,
    classifier: ScreenshotClassifier = ScreenshotClassifier(),
    policy: CropIsolationPolicy = CropIsolationPolicy()
): FullImageFallbackDecision {
    val decision = policy.decide(
        CropIsolationInput(
            detectedRegionCount = detectedRegionCount,
            candidateRegionType = CandidateRegionType.FULL_IMAGE_FALLBACK,
            screenshotType = classification.type.toCaptureScreenshotType(),
            rawOcrText = rawOcrText,
            likelySingleCoupon = rawOcrText.isNotBlank() && classifier.isLikelySingleCoupon(rawOcrText)
        )
    )
    return FullImageFallbackDecision(
        allowDirectOcr = decision.mode == CropIsolationMode.GUARDED_FULL_IMAGE_OCR,
        reason = decision.reason.toLegacyFallbackReason()
    )
}

private fun CropIsolationReason.toLegacyFallbackReason(): String {
    return when (this) {
        CropIsolationReason.MULTIPLE_REGIONS_DETECTED -> "multiple_regions_detected"
        CropIsolationReason.CLASSIFIED_MULTI_COUPON -> "classified_multi_coupon"
        CropIsolationReason.BLANK_OCR -> "blank_ocr_classification"
        CropIsolationReason.LIKELY_SINGLE_FULL_IMAGE_FALLBACK -> "likely_single_coupon"
        CropIsolationReason.NO_ISOLATED_CROP -> "not_likely_single_coupon"
        CropIsolationReason.ISOLATED_CROP_AVAILABLE -> "isolated_crop_available"
    }
}

private fun ScreenshotClassifier.ScreenshotType.toCaptureScreenshotType(): CaptureScreenshotType {
    return when (this) {
        ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP -> CaptureScreenshotType.MULTI_COUPON_APP
        ScreenshotClassifier.ScreenshotType.CAMERA_CAPTURE -> CaptureScreenshotType.CAMERA_CAPTURE
        ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT -> CaptureScreenshotType.SINGLE_SCREENSHOT
    }
}

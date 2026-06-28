package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.util.MultiEngineOCR
import javax.inject.Inject

data class FullImageFallbackDecision(
    val allowDirectOcr: Boolean,
    val reason: String
)

data class FullImageFallbackProbeResult(
    val rawOcrText: String,
    val decision: FullImageFallbackDecision,
    val ocrErrorMessage: String? = null
)

class FullImageFallbackProbe @Inject constructor() {
    private val classifier = ScreenshotClassifier()

    suspend fun evaluate(
        bitmap: Bitmap,
        processImage: suspend (Bitmap) -> MultiEngineOCR.OCRResult
    ): FullImageFallbackProbeResult {
        val ocrResult = runCatching { processImage(bitmap) }.getOrElse { error ->
            return FullImageFallbackProbeResult(
                rawOcrText = "",
                decision = decideForRawText(bitmap, ""),
                ocrErrorMessage = error.message ?: "Full-image fallback OCR failed"
            )
        }

        val rawOcrText = when (ocrResult) {
            is MultiEngineOCR.OCRResult.Success -> ocrResult.text.ifBlank {
                ocrResult.extractedInfo.values.joinToString("\n")
            }
            is MultiEngineOCR.OCRResult.Error -> ""
        }

        return FullImageFallbackProbeResult(
            rawOcrText = rawOcrText,
            decision = decideForRawText(bitmap, rawOcrText),
            ocrErrorMessage = (ocrResult as? MultiEngineOCR.OCRResult.Error)?.message
        )
    }

    private fun decideForRawText(
        bitmap: Bitmap,
        rawOcrText: String
    ): FullImageFallbackDecision {
        val classification = if (rawOcrText.isNotBlank()) {
            classifier.classify(bitmap, rawOcrText)
        } else {
            ScreenshotClassifier.ClassificationResult(
                type = ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT,
                confidence = 0f,
                indicators = emptyMap()
            )
        }
        return decideFullImageFallback(
            classification = classification,
            rawOcrText = rawOcrText,
            classifier = classifier
        )
    }
}

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

fun shouldBlockFullImageFallback(
    multiResult: MultiCouponExtractionService.MultiCouponResult?
): Boolean {
    if (multiResult == null) return false
    return multiResult.screenshotType == ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP ||
        multiResult.totalDetected > 1
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

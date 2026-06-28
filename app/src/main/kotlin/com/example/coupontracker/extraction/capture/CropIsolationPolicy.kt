package com.example.coupontracker.extraction.capture

/**
 * Pure routing policy for deciding how much of a scan is safe to send into
 * field extraction. It intentionally avoids Android UI and bitmap types.
 */
class CropIsolationPolicy {

    fun decide(input: CropIsolationInput): CropIsolationDecision {
        require(input.detectedRegionCount >= 0) {
            "detectedRegionCount must be non-negative"
        }

        if (input.detectedRegionCount > 1) {
            return CropIsolationDecision.reviewOnly(
                reason = CropIsolationReason.MULTIPLE_REGIONS_DETECTED,
                reviewTarget = ReviewTarget.MULTI_SELECTION
            )
        }

        if (input.rawOcrText.isBlank()) {
            return CropIsolationDecision.reviewOnly(
                reason = CropIsolationReason.BLANK_OCR,
                reviewTarget = ReviewTarget.SINGLE_SCAN
            )
        }

        if (
            input.detectedRegionCount == 1 &&
            input.candidateRegionType == CandidateRegionType.ISOLATED_CROP
        ) {
            return CropIsolationDecision(
                mode = CropIsolationMode.ISOLATED_CROP,
                reason = CropIsolationReason.ISOLATED_CROP_AVAILABLE,
                reviewTarget = ReviewTarget.NONE,
                provisional = false
            )
        }

        if (input.screenshotType == CaptureScreenshotType.MULTI_COUPON_APP) {
            return CropIsolationDecision.reviewOnly(
                reason = CropIsolationReason.CLASSIFIED_MULTI_COUPON,
                reviewTarget = ReviewTarget.MULTI_SELECTION
            )
        }

        if (
            input.candidateRegionType == CandidateRegionType.FULL_IMAGE_FALLBACK &&
            input.likelySingleCoupon
        ) {
            return CropIsolationDecision(
                mode = CropIsolationMode.GUARDED_FULL_IMAGE_OCR,
                reason = CropIsolationReason.LIKELY_SINGLE_FULL_IMAGE_FALLBACK,
                reviewTarget = ReviewTarget.NONE,
                provisional = true
            )
        }

        return CropIsolationDecision.reviewOnly(
            reason = CropIsolationReason.NO_ISOLATED_CROP,
            reviewTarget = ReviewTarget.SINGLE_SCAN
        )
    }
}

data class CropIsolationInput(
    val detectedRegionCount: Int,
    val candidateRegionType: CandidateRegionType,
    val screenshotType: CaptureScreenshotType,
    val rawOcrText: String,
    val likelySingleCoupon: Boolean,
    val layoutConfidence: Float? = null,
    val layoutSource: LayoutSignalSource? = null
)

data class CropIsolationDecision(
    val mode: CropIsolationMode,
    val reason: CropIsolationReason,
    val reviewTarget: ReviewTarget,
    val provisional: Boolean
) {
    companion object {
        fun reviewOnly(
            reason: CropIsolationReason,
            reviewTarget: ReviewTarget
        ): CropIsolationDecision {
            return CropIsolationDecision(
                mode = CropIsolationMode.REVIEW_ONLY,
                reason = reason,
                reviewTarget = reviewTarget,
                provisional = false
            )
        }
    }
}

enum class CropIsolationMode {
    ISOLATED_CROP,
    GUARDED_FULL_IMAGE_OCR,
    REVIEW_ONLY
}

enum class CropIsolationReason {
    ISOLATED_CROP_AVAILABLE,
    MULTIPLE_REGIONS_DETECTED,
    CLASSIFIED_MULTI_COUPON,
    BLANK_OCR,
    LIKELY_SINGLE_FULL_IMAGE_FALLBACK,
    NO_ISOLATED_CROP
}

enum class ReviewTarget {
    NONE,
    SINGLE_SCAN,
    MULTI_SELECTION
}

enum class CandidateRegionType {
    NONE,
    ISOLATED_CROP,
    FULL_IMAGE_FALLBACK
}

enum class CaptureScreenshotType {
    SINGLE_SCREENSHOT,
    MULTI_COUPON_APP,
    CAMERA_CAPTURE,
    UNKNOWN
}

enum class LayoutSignalSource {
    VLM,
    HEURISTIC,
    HYBRID_DETECTOR,
    FALLBACK,
    UNKNOWN
}

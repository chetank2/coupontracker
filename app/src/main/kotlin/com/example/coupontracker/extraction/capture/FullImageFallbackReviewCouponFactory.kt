package com.example.coupontracker.extraction.capture

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.createMultiCouponImportReviewCoupon
import java.util.Date
import javax.inject.Inject

class FullImageFallbackReviewCouponFactory @Inject constructor() {

    fun create(
        imageUri: String,
        rawOcrText: String,
        reason: String,
        captureTimestamp: Date?
    ): Coupon {
        return createMultiCouponImportReviewCoupon(
            reason = reason,
            rawOcrText = rawOcrText,
            captureTimestamp = captureTimestamp
        ).copy(
            imageUri = imageUri,
            extractionRunPath = RUN_PATH,
            debugVisionEvidence = "full_image_fallback_guard; reason=$reason"
        )
    }

    companion object {
        const val RUN_PATH = "scanner_view_model -> full_image_fallback_guard"
    }
}

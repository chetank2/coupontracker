package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import java.util.Date

private const val CROP_ISOLATION_FAILED_ERROR =
    "Crop isolation failed; full-image OCR was not saved as a clean coupon."

fun isFallbackOrFullImageRegion(
    bitmap: Bitmap,
    region: HybridCouponDetector.CouponRegion,
    policy: CropIsolationPolicy = CropIsolationPolicy()
): Boolean {
    val imageArea = bitmap.width.toLong() * bitmap.height.toLong()
    if (imageArea <= 0L) return true

    val left = region.boundingBox.left.coerceIn(0, bitmap.width)
    val top = region.boundingBox.top.coerceIn(0, bitmap.height)
    val right = region.boundingBox.right.coerceIn(left, bitmap.width)
    val bottom = region.boundingBox.bottom.coerceIn(top, bitmap.height)
    val regionArea = (right - left).toLong() * (bottom - top).toLong()
    val coversMostWidth = (right - left) >= bitmap.width * 95 / 100
    val coversMostHeight = (bottom - top) >= bitmap.height * 95 / 100
    val fullImageLike = regionArea >= (imageArea * 95L / 100L) || (coversMostWidth && coversMostHeight)
    val candidateRegionType =
        if (region.source == HybridCouponDetector.DetectionSource.FALLBACK || fullImageLike) {
            CandidateRegionType.FULL_IMAGE_FALLBACK
        } else {
            CandidateRegionType.ISOLATED_CROP
        }

    val decision = policy.decide(
        CropIsolationInput(
            detectedRegionCount = 1,
            candidateRegionType = candidateRegionType,
            screenshotType = CaptureScreenshotType.UNKNOWN,
            rawOcrText = region.ocrText,
            likelySingleCoupon = false
        )
    )
    return decision.mode != CropIsolationMode.ISOLATED_CROP
}

fun createCropIsolationFailedCoupon(
    uri: Uri,
    reason: String
): Coupon {
    return Coupon(
        storeName = Coupon.Defaults.UNKNOWN_STORE,
        description = "Needs review: crop isolation failed",
        redeemCode = null,
        imageUri = uri.toString(),
        status = Coupon.Status.ACTIVE,
        needsAttention = true,
        storeNameSource = "batch_crop_isolation",
        storeNameEvidence = emptyList(),
        extractionQualityScore = 0,
        extractionConfidenceBreakdown = mapOf("layout" to 0f),
        extractionStage = "BATCH_CROP_ISOLATION_FAILED",
        extractionRunPath = "batch_region_detection -> review",
        extractionTimestamp = Date(),
        cleanupStatus = Coupon.CleanupStatus.FAILED,
        cleanupError = "$CROP_ISOLATION_FAILED_ERROR reason=$reason",
        extractionSource = "BATCH_CROP_ISOLATION_FAILED",
        codeState = Coupon.CodeState.UNKNOWN,
        expiryState = Coupon.ExpiryState.UNKNOWN,
        layoutState = Coupon.LayoutState.LOW_CONFIDENCE
    )
}

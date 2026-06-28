package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.util.MultiEngineOCR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Coordinates batch region detection and review-safe isolation decisions.
 *
 * This keeps the ViewModel out of crop ownership policy: only isolated coupon
 * regions are sent into field extraction, while fallback/full-image regions
 * produce a review coupon instead of a trusted save.
 */
class BatchRegionIsolationCoordinator @Inject constructor() {

    suspend fun extract(
        uri: Uri,
        bitmap: Bitmap,
        runOcr: suspend (Bitmap) -> MultiEngineOCR.OCRResult,
        detectRegions: suspend (Bitmap, MultiEngineOCR.OCRResult.Success) -> List<HybridCouponDetector.CouponRegion>,
        extractIsolatedRegions: suspend (List<HybridCouponDetector.CouponRegion>) -> List<Coupon>
    ): List<Coupon> = withContext(Dispatchers.Default) {
        Log.d(TAG, "Detecting multiple coupons in image...")

        try {
            val ocrResult = runOcr(bitmap)
            if (ocrResult !is MultiEngineOCR.OCRResult.Success) {
                Log.w(TAG, "OCR failed before region isolation; returning review coupon")
                return@withContext listOf(createCropIsolationFailedCoupon(uri, "ocr_failed_before_region_detection"))
            }

            val couponRegions = detectRegions(bitmap, ocrResult)
            Log.d(TAG, "Hybrid detector found ${couponRegions.size} coupon region(s)")

            val isolatedRegions = couponRegions.filterNot { isFallbackOrFullImageRegion(bitmap, it) }
            if (isolatedRegions.isEmpty()) {
                Log.w(TAG, "Region isolation failed; returning review coupon")
                return@withContext listOf(createCropIsolationFailedCoupon(uri, "no_isolated_coupon_regions"))
            }

            if (isolatedRegions.size < couponRegions.size) {
                Log.w(
                    TAG,
                    "Ignoring ${couponRegions.size - isolatedRegions.size} fallback/full-image region(s)"
                )
            }

            val extractedCoupons = extractIsolatedRegions(isolatedRegions)
            if (extractedCoupons.isEmpty()) {
                Log.w(TAG, "No coupons extracted from isolated regions; returning review coupon")
                return@withContext listOf(createCropIsolationFailedCoupon(uri, "isolated_region_extraction_failed"))
            }

            extractedCoupons
        } catch (e: Exception) {
            Log.e(TAG, "Error in multi-coupon detection", e)
            listOf(createCropIsolationFailedCoupon(uri, "region_detection_exception"))
        }
    }

    private companion object {
        private const val TAG = "BatchRegionIsolationCoordinator"
    }
}

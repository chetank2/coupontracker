package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import javax.inject.Inject

/**
 * Owns crop-first extraction across already-isolated batch regions.
 *
 * Region detection and isolation decisions stay upstream; this class only
 * crops accepted regions and routes those crops through the enabled extractor.
 */
class BatchRegionExtractionRunner @Inject constructor() {

    suspend fun extract(
        bitmap: Bitmap,
        couponRegions: List<HybridCouponDetector.CouponRegion>,
        usePipeline: Boolean,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit,
        extractPipeline: suspend (List<Bitmap>) -> List<Coupon>,
        extractSingleRegion: suspend (Bitmap) -> Coupon
    ): List<Coupon> {
        if (usePipeline) {
            val viaPipeline = extractViaPipeline(
                bitmap = bitmap,
                couponRegions = couponRegions,
                trackBitmap = trackBitmap,
                releaseBitmap = releaseBitmap,
                extractPipeline = extractPipeline
            )
            if (viaPipeline.isNotEmpty()) {
                Log.d(TAG, "Pipeline extraction yielded ${viaPipeline.size} coupon(s)")
                return viaPipeline
            }
            Log.w(TAG, "Pipeline yielded zero coupons; falling back to per-region loop")
        }

        return extractViaPerRegionLoop(
            bitmap = bitmap,
            couponRegions = couponRegions,
            trackBitmap = trackBitmap,
            releaseBitmap = releaseBitmap,
            extractSingleRegion = extractSingleRegion
        )
    }

    private suspend fun extractViaPipeline(
        bitmap: Bitmap,
        couponRegions: List<HybridCouponDetector.CouponRegion>,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit,
        extractPipeline: suspend (List<Bitmap>) -> List<Coupon>
    ): List<Coupon> {
        val crops = mutableListOf<Bitmap>()
        for (region in couponRegions) {
            val crop = cropBitmapToRegion(bitmap, region.boundingBox) ?: continue
            trackBitmap(crop)
            crops += crop
        }
        if (crops.isEmpty()) return emptyList()

        return try {
            extractPipeline(crops)
        } finally {
            crops.forEach { releaseBitmap(it) }
        }
    }

    private suspend fun extractViaPerRegionLoop(
        bitmap: Bitmap,
        couponRegions: List<HybridCouponDetector.CouponRegion>,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit,
        extractSingleRegion: suspend (Bitmap) -> Coupon
    ): List<Coupon> {
        val extractedCoupons = mutableListOf<Coupon>()
        for ((regionIndex, region) in couponRegions.withIndex()) {
            try {
                Log.d(TAG, "Extracting coupon region ${regionIndex + 1}/${couponRegions.size}")
                val regionBitmap = cropBitmapToRegion(bitmap, region.boundingBox)
                if (regionBitmap == null) {
                    Log.w(TAG, "Failed to crop region ${regionIndex + 1}, skipping")
                    continue
                }
                trackBitmap(regionBitmap)
                try {
                    val coupon = extractSingleRegion(regionBitmap)
                    extractedCoupons.add(coupon)
                    Log.d(TAG, "Successfully extracted coupon ${regionIndex + 1}: store='${coupon.storeName}', code='${coupon.redeemCode}'")
                } finally {
                    releaseBitmap(regionBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting region ${regionIndex + 1}", e)
            }
        }
        return extractedCoupons
    }

    private fun cropBitmapToRegion(bitmap: Bitmap, region: Rect): Bitmap? {
        return try {
            val left = region.left.coerceIn(0, bitmap.width)
            val top = region.top.coerceIn(0, bitmap.height)
            val right = region.right.coerceIn(left, bitmap.width)
            val bottom = region.bottom.coerceIn(top, bitmap.height)

            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid crop region: width=$width, height=$height")
                return null
            }

            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e)
            null
        }
    }

    private companion object {
        private const val TAG = "BatchRegionExtractionRunner"
    }
}

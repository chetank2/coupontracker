package com.example.coupontracker.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.HybridCouponDetector
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchScannerViewModelFallbackTest {

    @Test
    fun `fallback detector region is not safe for clean extraction`() {
        val bitmap = bitmap(width = 1000, height = 2000)
        val region = region(
            boundingBox = Rect(0, 0, 1000, 2000),
            source = HybridCouponDetector.DetectionSource.FALLBACK
        )

        assertTrue(isFallbackOrFullImageRegion(bitmap, region))
    }

    @Test
    fun `full image non fallback region is not safe for clean extraction`() {
        val bitmap = bitmap(width = 1000, height = 2000)
        val region = region(
            boundingBox = Rect(0, 0, 1000, 2000),
            source = HybridCouponDetector.DetectionSource.OCR_ANCHOR_ONLY
        )

        assertTrue(isFallbackOrFullImageRegion(bitmap, region))
    }

    @Test
    fun `near full image region is review only even when detector source is not fallback`() {
        val bitmap = bitmap(width = 1000, height = 2000)
        val region = region(
            boundingBox = Rect(10, 20, 970, 1930),
            source = HybridCouponDetector.DetectionSource.OCR_ANCHOR_ONLY
        )

        assertTrue(isFallbackOrFullImageRegion(bitmap, region))

        val coupon = createCropIsolationFailedCoupon(
            uri("content://batch/full-image-fallback.png"),
            reason = "no_isolated_coupon_regions"
        )
        assertNull(coupon.redeemCode)
        assertTrue(coupon.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, coupon.cleanupStatus)
        assertEquals("BATCH_CROP_ISOLATION_FAILED", coupon.extractionSource)
        assertEquals("batch_region_detection -> review", coupon.extractionRunPath)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, coupon.layoutState)
        assertEquals(Coupon.CodeState.UNKNOWN, coupon.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, coupon.expiryState)
    }

    @Test
    fun `real cropped region remains safe for per region extraction`() {
        val bitmap = bitmap(width = 1000, height = 2000)
        val region = region(
            boundingBox = Rect(80, 120, 900, 900),
            source = HybridCouponDetector.DetectionSource.OCR_ANCHOR_ONLY
        )

        assertFalse(isFallbackOrFullImageRegion(bitmap, region))
    }

    @Test
    fun `blank ocr cropped region is not safe for clean extraction`() {
        val bitmap = bitmap(width = 1000, height = 2000)
        val region = region(
            boundingBox = Rect(80, 120, 900, 900),
            source = HybridCouponDetector.DetectionSource.OCR_ANCHOR_ONLY,
            ocrText = "   "
        )

        assertTrue(isFallbackOrFullImageRegion(bitmap, region))
    }

    @Test
    fun `crop isolation fallback coupon is review only`() {
        val uri = uri("content://batch/coupon.png")

        val coupon = createCropIsolationFailedCoupon(uri, reason = "no_isolated_coupon_regions")

        assertEquals(Coupon.Defaults.UNKNOWN_STORE, coupon.storeName)
        assertEquals("Needs review: crop isolation failed", coupon.description)
        assertNull(coupon.redeemCode)
        assertEquals("content://batch/coupon.png", coupon.imageUri)
        assertTrue(coupon.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, coupon.cleanupStatus)
        assertTrue(coupon.cleanupError.orEmpty().contains("Crop isolation failed"))
        assertTrue(coupon.cleanupError.orEmpty().contains("no_isolated_coupon_regions"))
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, coupon.layoutState)
        assertEquals(Coupon.CodeState.UNKNOWN, coupon.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, coupon.expiryState)
        assertEquals("BATCH_CROP_ISOLATION_FAILED", coupon.extractionStage)
        assertEquals("BATCH_CROP_ISOLATION_FAILED", coupon.extractionSource)
        assertEquals("batch_region_detection -> review", coupon.extractionRunPath)
    }

    private fun bitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private fun uri(value: String): Uri {
        return mockk<Uri>().also {
            every { it.toString() } returns value
        }
    }

    private fun region(
        boundingBox: Rect,
        source: HybridCouponDetector.DetectionSource,
        ocrText: String = "coupon text"
    ): HybridCouponDetector.CouponRegion {
        return HybridCouponDetector.CouponRegion(
            boundingBox = boundingBox,
            ocrText = ocrText,
            confidence = 0.5f,
            source = source
        )
    }
}

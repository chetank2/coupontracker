package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.CouponStatus
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.RunPath
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DetectedCropCouponBuilderTest {
    private val builder = DetectedCropCouponBuilder()

    @Test
    fun `buildProvisionalCoupon creates validated pending coupon from detected crop OCR fields`() {
        val coupon = builder.buildProvisionalCoupon(
            couponInstance = couponInstance(CouponStatus.COMPLETE),
            extraction = DetectedCropFieldExtraction(
                fields = mapOf(
                    "storeName" to "AJIO",
                    "description" to "Flat 50% off on fashion",
                    "code" to "SAVE50",
                    "expiryDate" to "31/12/24"
                ),
                runPath = RunPath(strategy = "OCR", tried = mutableListOf("OCR"), final = "OCR"),
                qualityScore = 55,
                fieldConfidences = mapOf(
                    "storeName" to 0.8f,
                    "description" to 0.8f,
                    "code" to 0.8f,
                    "expiryDate" to 0.8f
                ),
                sourceStage = ExtractionStage.MLKIT,
                fullOcrText = "AJIO Flat 50% off on fashion use SAVE50 expires 31/12/24",
                imageHeight = 1200
            ),
            imageUri = "file://crop.jpg",
            captureTimestamp = null
        )

        assertEquals("AJIO", coupon.storeName)
        assertEquals("SAVE50", coupon.redeemCode)
        assertEquals("file://crop.jpg", coupon.imageUri)
        assertEquals("Fashion", coupon.category)
        assertEquals("ACTIVE", coupon.status)
        assertEquals(Coupon.CleanupStatus.PENDING, coupon.cleanupStatus)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, coupon.layoutState)
        assertEquals(Coupon.ExtractionSource.OCR_VERIFIED, coupon.extractionSource)
        assertEquals("AJIO Flat 50% off on fashion use SAVE50 expires 31/12/24", coupon.rawOcrText)
        assertNotNull(coupon.expiryDate)
        assertTrue(coupon.needsAttention)
        assertTrue(coupon.debugVisionEvidence.orEmpty().contains("source=single_detected_crop_ocr_only"))
        assertTrue(coupon.extractionRunPath.orEmpty().contains("detected_coupon_instance"))
    }

    @Test
    fun `parseExpiryDate handles two digit year with trailing time`() {
        val parsed = DetectedCropCouponBuilder.parseExpiryDate("31/12/24 at 11:59 PM IST", Locale.UK)

        assertNotNull(parsed)
    }

    private fun couponInstance(status: CouponStatus): CouponInstance {
        return CouponInstance(
            id = "crop-1",
            boundingBox = RectF(0f, 0f, 500f, 900f),
            status = status,
            confidence = 0.91f,
            fields = emptyList(),
            cropBitmap = mockk<Bitmap>(relaxed = true)
        )
    }
}

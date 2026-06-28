package com.example.coupontracker.extraction.capture

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class FullImageFallbackReviewCouponFactoryTest {

    private val factory = FullImageFallbackReviewCouponFactory()

    @Test
    fun `creates review coupon with fallback guard evidence`() {
        val timestamp = Date(1_735_689_600_000L)

        val coupon = factory.create(
            imageUri = "content://persisted/coupon",
            rawOcrText = "Background card text",
            reason = "no_coupon_crop_detected_classified_multi_coupon",
            captureTimestamp = timestamp
        )

        assertEquals("content://persisted/coupon", coupon.imageUri)
        assertEquals(Coupon.Defaults.UNKNOWN_STORE, coupon.storeName)
        assertEquals("Needs review: multiple coupons could not be isolated", coupon.description)
        assertEquals(null, coupon.redeemCode)
        assertEquals(Coupon.CleanupStatus.FAILED, coupon.cleanupStatus)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, coupon.layoutState)
        assertEquals(Coupon.CodeState.UNKNOWN, coupon.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, coupon.expiryState)
        assertEquals(FullImageFallbackReviewCouponFactory.RUN_PATH, coupon.extractionRunPath)
        assertEquals(timestamp, coupon.extractionTimestamp)
        assertTrue(
            coupon.debugVisionEvidence.orEmpty()
                .contains("full_image_fallback_guard; reason=no_coupon_crop_detected_classified_multi_coupon")
        )
    }
}

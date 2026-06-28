package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CouponInputManagerTest {

    @Test
    fun `multi coupon import review coupon is not trusted full image extraction`() {
        val captureTimestamp = Date(1_700_000_000_000)

        val coupon = createMultiCouponImportReviewCoupon(
            reason = "multi_extraction_returned_no_coupons",
            rawOcrText = "Store A\nCode A1\nStore B\nCode B2",
            captureTimestamp = captureTimestamp
        )

        assertEquals(Coupon.Defaults.UNKNOWN_STORE, coupon.storeName)
        assertEquals("Needs review: multiple coupons could not be isolated", coupon.description)
        assertNull(coupon.redeemCode)
        assertTrue(coupon.needsAttention)
        assertEquals(Coupon.CleanupStatus.FAILED, coupon.cleanupStatus)
        assertEquals(Coupon.LayoutState.LOW_CONFIDENCE, coupon.layoutState)
        assertEquals("MULTI_COUPON_IMPORT_REVIEW", coupon.extractionStage)
        assertEquals("MULTI_COUPON_IMPORT_REVIEW", coupon.extractionSource)
        assertEquals(Coupon.CodeState.UNKNOWN, coupon.codeState)
        assertEquals(Coupon.ExpiryState.UNKNOWN, coupon.expiryState)
        assertTrue(coupon.cleanupError.orEmpty().contains("multi_extraction_returned_no_coupons"))
        assertEquals("Store A\nCode A1\nStore B\nCode B2", coupon.rawOcrText)
        assertEquals(captureTimestamp, coupon.extractionTimestamp)
    }
}

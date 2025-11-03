package com.example.coupontracker.ml

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenshotClassifierTest {

    private val classifier = ScreenshotClassifier()

    private val singleCouponText = """
        8:19
        Paytm
        Get Flat 20% Off*
        Code: AHAPPE20 COPY
        Offer Details
        Expires on 31 May, 2025, 11:59 PM
        AVAIL NOW
    """.trimIndent()

    private val multiCouponText = """
        Amazon Great Indian Festival
        Grab Deal Today
        Save Now on Top Deals
        Redeem Offer Instantly
        Claim Offer Rewards
        Exclusive Discount Bonanza
        Limited Time Offer Details
        Hot Deal Cashback Offer
    """.trimIndent()

    @Test
    fun `classify treats single offer screenshots as single coupon`() {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val result = classifier.classify(bitmap, singleCouponText)

        assertEquals(ScreenshotClassifier.ScreenshotType.SINGLE_SCREENSHOT, result.type)
        assertTrue(classifier.isLikelySingleCoupon(singleCouponText))
    }

    @Test
    fun `classify detects multi coupon layouts when signals are strong`() {
        val bitmap = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val result = classifier.classify(bitmap, multiCouponText)

        assertEquals(ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP, result.type)
    }
}


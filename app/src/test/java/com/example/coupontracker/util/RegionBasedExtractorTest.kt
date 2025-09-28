package com.example.coupontracker.util

import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RegionBasedExtractorTest {

    private val extractor = RegionBasedExtractor(TextExtractor())

    @Test
    fun `extractRedeemCode accepts alphabetic coupon codes`() = runBlocking {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val rawText = """
            Special Deals
            Don't miss out on exclusive savings
            MISSEDYOU
            Redeem today
        """.trimIndent()

        val couponInfo = extractor.extractCouponInfo(bitmap, rawText)

        assertNotNull("Expected redeem code to be extracted", couponInfo.redeemCode)
        assertEquals("MISSEDYOU", couponInfo.redeemCode)
    }
}

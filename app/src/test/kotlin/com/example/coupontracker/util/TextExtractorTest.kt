package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TextExtractorRobolectricTest {

    private val extractor = TextExtractor()

    @Test
    fun extractStoreName_filtersCommonStopwords() {
        val text = "JUST launched OTTplay Premium plans"
        val result = extractor.extractStoreName(text)
        assertEquals("OTTplay", result)
    }

    @Test
    fun extractStoreName_ignoresWalletStatusTabs() {
        val text = """
            vouchers
            (5)
            ACTIVE
            VOUCHERS
            (5)
            LH
            HDFC
            BANK
            PIXEL
            PLAY
            offer
            details
            terms
            EXPIRES IN 18 DAYS
            3. the credit card offered is lifetime free with no joining or annual fee
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("HDFC", result)
    }
}

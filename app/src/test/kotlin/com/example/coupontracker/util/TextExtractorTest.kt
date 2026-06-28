package com.example.coupontracker.util

import com.example.coupontracker.extraction.rules.TextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun extractCouponInfo_doesNotUseUnlabeledModalActionAsCodeWhenBackgroundCardsSayNoCodeNeeded() {
        val text = """
            My vouchers
            Scratch & win
            IDFC FIRST BANK TOOTHSI
            Monthly Interest Flat 20k off
            NO CODE NEEDED TOOTHSI20KOFF G
            OPEN N NOW >
            MAKEMYTRIP FLIGHTS
            Flat 15% off*
            NO COD NEEDED
            FLYMART
            CLAIM IOW >
            Book Now!
            IIl use it later!
            MAKEMYTRIP FLIGHTS
            Flat 15% off
            SCRATCH HERE FLYMARTO
            BOOK NOW! >
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertNull(result.redeemCode)
    }

    @Test
    fun extractCouponInfo_doesNotUseScratchWinAsStoreOrNoCodeLineAsCode() {
        val text = """
            5:59 (9 GU955%
            (*)
            SCRATCH & WIN
            REWARDS!
            ixigo
            1XI
            Up to
            SWGG224 H HERE
            ixigo
            CLAIM
            IXIGO
            Up to 30% Off
            E SWGG224CYNU9SPA O hsi
            MakeO
            IDFC FIF THSI
            Claim Now
            Monthl Dk off
            NO CODE 20KOFFO
            OPEN NOW > later! CLAIM NOW >
            UNI GOLDX CARD BE 10X
            Zero Forex Card Free Al Webinar
            NO CODE NEEDED NO CODE NEEDED
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text)

        assertEquals("IXIGO", result.storeName)
        assertEquals("SWGG224CYNU9SPA", result.redeemCode)
    }
}

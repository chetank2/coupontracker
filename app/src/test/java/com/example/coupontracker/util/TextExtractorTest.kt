package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TextExtractorTest {

    private val extractor = TextExtractor()

    private val samplePaytmOcr = """
        8:19
        Paytm
        M
        /oTTplay
        PREMIUM
        OTTplay
        149*
        LIONSGATS
        aha
        Code: TTPHONEBUFF
        Offer Details
        SUBSCRIBE NOW
        About OTTplay
        TELUGU
        WIN,
        Scratch card received on offer
        Enjoy 30+ OTTs at Just 149*
        SONY
        Expires on 31 May, 2025, 11:59 PM
        liv
        +26
        Enjoy 30+ OTTs at Just
        More OTTS
        Yo 5G36%
        includes SonyLIV, ZEE5, Sun NXT and much more,
        along with 500+ Live TV channels
        SUN
        NT
        500+
        Live Channels
        ZEE5
        COPY
    """.trimIndent()

    @Test
    fun `extractCouponInfoSync should recover store code and expiry from Paytm OCR`() {
        val result = extractor.extractCouponInfoSync(samplePaytmOcr)

        assertEquals("Paytm", result.storeName)
        assertEquals("TTPHONEBUFF", result.redeemCode)

        val expiry = result.expiryDate
        assertNotNull("Expected expiry date to be parsed", expiry)

        val calendar = Calendar.getInstance(Locale.US).apply { time = expiry!! }
        assertEquals(2025, calendar.get(Calendar.YEAR))
        assertEquals(Calendar.MAY, calendar.get(Calendar.MONTH))
        assertEquals(31, calendar.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `extractDescription combines multi-line offer text`() {
        val text = """
            Buy 2 Get 2 FREE*
            + Up to 5% Foxcoins
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Buy 2 Get 2 FREE* + Up to 5% Foxcoins",
            result
        )
    }

    @Test
    fun `extractDescription preserves upto connector without whitespace`() {
        val text = """
            Buy 2 Get 2 FREE* +
            Upto 5% Foxcoins
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Buy 2 Get 2 FREE* + Upto 5% Foxcoins",
            result
        )
    }

    @Test
    fun `extractDescription builds rupee summary when LLM returns placeholder`() {
        val text = """
            Minimalist
            Offer Details
            Flat 7100 Off + 750 Cashback
            Use code MNPPRK100UAPR255QYSGZA
        """.trimIndent()

        val result = extractor.extractDescription(text)

        assertEquals(
            "Minimalist Coupon - Flat ₹7100 off",
            result
        )
    }

    @Test
    fun `extractStoreName skips watermark noise like Pastm`() {
        val text = """
            Pastm rewards watermark
            Redeem on Aha Annual Plan Offer
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Aha", result)
    }

    @Test
    fun `extractStoreName removes leading details label`() {
        val text = """
            Details
            Leaf
            you won 16099 off on Leaf Halo Smart Ring
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Leaf", result)
    }

    @Test
    fun `extractStoreName rejects weak pm vit fragment and keeps real OCR brand`() {
        val text = """
            pm Vit
            Gritzo
            Flat off for kids Products on Gritzo
            Code PHGZQZG2DCY9WB
            ORDER NOW
        """.trimIndent()

        val result = extractor.extractStoreName(text)

        assertEquals("Gritzo", result)
    }

    @Test
    fun `extractRedeemCode finds token on line after indicator`() {
        val text = """
            Stream exclusively on aha
            Use code
            XYXXCRED2024 today
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals("XYXXCRED2024", result)
    }

    @Test
    fun `extractRedeemCode preserves hyphenated explicit code`() {
        val text = """
            Lenskart
            Gold Membership offer
            Code: AFFLPHG-UFPJ-TDOAB
            Valid till 30 Jun 2026
        """.trimIndent()

        val result = extractor.extractRedeemCode(text)

        assertEquals("AFFLPHG-UFPJ-TDOAB", result)
    }

    @Test
    fun `parseExpiryDate handles day first format with trailing time`() {
        val text = "Get 2 Months Audible Premium Plus\nExpires on 31 May, 2025, 11:59 PM"

        val result = extractor.parseExpiryDate(text)

        assertNotNull(result)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2025-05-31", formatter.format(result!!))
    }
}

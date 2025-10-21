package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TextExtractorTest {

    private val extractor = TextExtractor()

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
    fun `parseExpiryDate handles day first format with trailing time`() {
        val text = "Get 2 Months Audible Premium Plus\nExpires on 31 May, 2025, 11:59 PM"

        val result = extractor.parseExpiryDate(text)

        assertNotNull(result)
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        assertEquals("2025-05-31", formatter.format(result!!))
    }
}

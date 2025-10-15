package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

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
}

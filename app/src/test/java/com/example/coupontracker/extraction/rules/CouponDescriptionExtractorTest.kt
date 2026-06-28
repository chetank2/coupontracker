package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CouponDescriptionExtractorTest {
    private val extractor = CouponDescriptionExtractor()

    @Test
    fun `extract combines multiline buy-get offer`() {
        val text = """
            Buy 2 Get 2 FREE*
            + Up to 5% Foxcoins
        """.trimIndent()

        val result = extractor.extract(text)

        assertEquals("Buy 2 Get 2 FREE* + Up to 5% Foxcoins", result)
    }

    @Test
    fun `extract removes store chrome and action text from commercial offer`() {
        val text = """
            TECMARY
            Get Roar Bluetooth Earbuds @
            299*
            only on TECMARY website
            BUY NOW
            Code: TECMARY299
        """.trimIndent()

        val result = extractor.extract(
            text = text,
            storeName = "TECMARY",
            redeemCode = "TECMARY299",
            sourceText = text
        )

        assertEquals("Get Roar Bluetooth Earbuds @ ₹299*", result)
        assertFalse(result.orEmpty().contains("TECMARY"))
        assertFalse(result.orEmpty().contains("BUY NOW", ignoreCase = true))
        assertFalse(result.orEmpty().contains("website", ignoreCase = true))
    }
}

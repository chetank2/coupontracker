package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextExtractorCurrencyTest {

    private val extractor = TextExtractor()

    @Test
    fun `extract cashback detail retains usd symbol`() {
        val text = """
            Mega Sale
            Get ${'$'}50 cashback on all electronics
            Code: SAVE50
        """.trimIndent()

        val detail = extractor.extractCashbackDetail(text)

        assertEquals("Cashback: ${'$'}50 off", detail)
    }

    @Test
    fun `extract cashback detail retains eur symbol`() {
        val text = """
            Weekend Promo
            Enjoy €20 discount on hotel bookings
            Use code EURO20
        """.trimIndent()

        val detail = extractor.extractCashbackDetail(text)

        assertEquals("Cashback: €20 off", detail)
    }
}


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

    @Test
    fun `extract minimum purchase keeps existing order above behavior`() {
        val text = "Flat 20% off on orders above ₹499"

        val amount = extractor.extractMinimumPurchase(text)

        assertEquals(499.0, amount ?: 0.0, 0.0)
    }

    @Test
    fun `extract maximum discount keeps existing up to behavior`() {
        val text = "Get discount up to Rs.250 on your first order"

        val amount = extractor.extractMaximumDiscount(text)

        assertEquals(250.0, amount ?: 0.0, 0.0)
    }
}

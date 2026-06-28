package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TextCouponInfoExtractorTest {
    private val extractor = TextCouponInfoExtractor()

    @Test
    fun `extractCouponInfoSync assembles fields through focused rule extractors`() {
        val baseDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse("2026-06-17")
        val text = """
            BigBasket
            You won flat ₹150 off
            on orders above ₹400 on BigBasket Details code:
            BBNOWCRED3-GZGE7F7BAHEXFY
            Details
            Redeem Now
            Expires in 06 days
        """.trimIndent()

        val result = extractor.extractCouponInfoSync(text, baseDate)

        assertEquals("BigBasket", result.storeName)
        assertEquals("You won flat ₹150 off on orders above ₹400 on BigBasket", result.description)
        assertEquals("BBNOWCRED3-GZGE7F7BAHEXFY", result.redeemCode)
        assertEquals("Cashback: ₹150 off", result.cashbackDetail)
        assertEquals(400.0, result.minimumPurchase!!, 0.0)
        assertEquals("2026-06-23", dateOnly.format(result.expiryDate!!))
    }

    private companion object {
        val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

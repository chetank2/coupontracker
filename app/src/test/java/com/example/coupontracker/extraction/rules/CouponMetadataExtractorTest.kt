package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class CouponMetadataExtractorTest {
    @Test
    fun `extract reads category status payment usage and platform metadata`() {
        val text = """
            Fashion Store
            Flat 20% off on shirts
            Status: Available to Redeem
            Rating: 4.5
            valid only on Credit Card payments
            valid 2 times
            app exclusive offer
        """.trimIndent()

        val metadata = CouponMetadataExtractor.extract(
            text = text,
            cashbackDetail = "20% off"
        )

        assertEquals("Fashion", metadata.category)
        assertEquals("4.5", metadata.rating)
        assertEquals("Available to Redeem", metadata.status)
        assertEquals("PERCENTAGE", metadata.discountType)
        assertEquals("Credit Card", metadata.paymentMethod)
        assertEquals(2, metadata.usageLimit)
        assertEquals("Payment", metadata.platformType)
    }

    @Test
    fun `text extractor metadata methods delegate to metadata extractor`() {
        val extractor = TextExtractor()
        val text = """
            Food offer
            Pay using UPI
            single use only
            Expired
        """.trimIndent()

        assertEquals(CouponMetadataExtractor.extractCategory(text), extractor.extractCategory(text))
        assertEquals(CouponMetadataExtractor.extractPaymentMethod(text), extractor.extractPaymentMethod(text))
        assertEquals(CouponMetadataExtractor.extractUsageLimit(text), extractor.extractUsageLimit(text))
        assertEquals(CouponMetadataExtractor.extractStatus(text), extractor.extractStatus(text))
    }
}

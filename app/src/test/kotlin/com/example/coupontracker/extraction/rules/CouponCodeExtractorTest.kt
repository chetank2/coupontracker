package com.example.coupontracker.extraction.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CouponCodeExtractorTest {

    @Test
    fun `does not extract unlabeled alpha only logo token as code`() {
        val text = """
            PORTRONICS
            You won neck fan at Rs 1100
            Expires in 4 hours
        """.trimIndent()

        assertNull(CouponCodeExtractor.extract(text))
    }

    @Test
    fun `extracts alpha only code when explicit code label is present`() {
        val text = """
            Fashion voucher
            Use code WELCOME
            Flat 20% off on orders above Rs 999
        """.trimIndent()

        assertEquals("WELCOME", CouponCodeExtractor.extract(text))
    }

    @Test
    fun `extracts standalone mixed alpha numeric code`() {
        val text = """
            IKEA
            Flat 10% off up to Rs 2000
            563X9XFYDUR604GD
            Expires in 29 days
        """.trimIndent()

        assertEquals("563X9XFYDUR604GD", CouponCodeExtractor.extract(text))
    }

    @Test
    fun `does not treat generic word after coupon indicator as code`() {
        val text = """
            Coupon offer
            Flat 25% off on accessories
            Valid today
        """.trimIndent()

        assertNull(CouponCodeExtractor.extract(text))
    }
}

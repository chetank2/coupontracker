package com.example.coupontracker.util

import org.junit.Assert.assertFalse
import org.junit.Test

class CouponFieldExtractorTest {

    @Test
    fun `missing merchant is not reported as trusted unknown store`() {
        val fields = CouponFieldExtractor().extractWithConfidence(
            "Flat 25% off on accessories\nUse code SAVE25"
        )

        assertFalse(fields.containsKey("merchantName"))
    }
}

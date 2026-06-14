package com.example.coupontracker.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostOcrCouponNormalizerTest {

    @Test
    fun normalizerKeepsRawOcrOutOfDescription() {
        val rawOcr = """
            11:18
            HDFC
            vouchers (5) VOUCH
            CODE
            XXXXXX
            EXPIRES
            Jun 30, 2026
            Get 33% off 750 Ca
            ₹150 off
            Get Extra 20% off
            Copy code
            Terms and conditions apply
        """.trimIndent()

        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = rawOcr,
            ocrText = rawOcr,
            storeName = "HDFC",
            redeemCode = null,
        )

        assertEquals("Get 33% off 750 Ca\nTerms and conditions apply", result.description)
        assertFalse(result.description.orEmpty().contains("vouchers", ignoreCase = true))
        assertFalse(result.description.orEmpty().contains("CODE", ignoreCase = true))
        assertTrue(result.issues.contains("raw_ocr_description_replaced"))
    }

    @Test
    fun normalizerDropsZeroCashbackNoise() {
        val result = PostOcrCouponNormalizer.normalize(
            currentDescription = "cashback: 0.0\ncashback 0.0\nGet Extra 20% off",
            ocrText = "cashback: 0.0\ncashback 0.0\nGet Extra 20% off",
            storeName = "TrueBasics",
            redeemCode = "TRUE20",
        )

        assertEquals("Get Extra 20% off", result.description)
        assertFalse(result.description.orEmpty().contains("0.0"))
    }
}

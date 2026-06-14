package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Test

class CouponPostProcessorTest {

    @Test
    fun `refine prefers explicit OCR code over weak guessed code`() {
        val ocrText = """
            Lenskart
            Get 20% off on eyewear
            Code: AFFLPHG-UFPJ-TDOAB
            DYSITTWE
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Lenskart",
            description = "Get 20% off on eyewear",
            redeemCode = "DYSITTWE",
            imageUri = null,
        )

        val refined = CouponPostProcessor.refine(
            coupon = coupon,
            context = CouponFixContext(ocrText = ocrText)
        )

        assertEquals("AFFLPHG-UFPJ-TDOAB", refined.redeemCode)
    }
}

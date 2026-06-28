package com.example.coupontracker.util

import com.example.coupontracker.data.model.Coupon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `refine replaces unsupported store fragment with OCR backed store`() {
        val ocrText = """
            Gritzo
            Flat off for kids Products on Gritzo
            Code PHGZQZG2DCY9WB
        """.trimIndent()
        val coupon = Coupon(
            storeName = "pm Vit",
            description = "Flat off for kids",
            redeemCode = "PHGZQZG2DCY9WB",
            imageUri = null,
        )

        val refined = CouponPostProcessor.refine(
            coupon = coupon,
            context = CouponFixContext(ocrText = ocrText)
        )

        assertEquals("Gritzo", refined.storeName)
        assertTrue(refined.needsAttention)
    }

    @Test
    fun `refine replaces weak merchant fragment with fuller OCR backed store`() {
        val ocrText = """
            FACEMINC
            CompanvJE MAN COHDANY
            Lhe Man
            Buy any 4 products at
            699*
            from The Man Company website
            Code: TMCPe6990425SQTJ COPY
            About The Man Company
            Buy any 4 products at 7699*
        """.trimIndent()
        val coupon = Coupon(
            storeName = "Lhe Man",
            description = "Buy any 4 products at 7699*",
            redeemCode = "TMCPE6990425SQTJ",
            imageUri = null,
        )

        val refined = CouponPostProcessor.refine(
            coupon = coupon,
            context = CouponFixContext(ocrText = ocrText)
        )

        assertEquals("The Man Company", refined.storeName)
        assertEquals("Buy any 4 products at ₹699*", refined.description)
    }

    @Test
    fun `refine does not extract needed from no code needed text`() {
        val ocrText = """
            SAMPLE STORE
            Complimentary service voucher
            NO CODE NEEDED
        """.trimIndent()
        val coupon = Coupon(
            storeName = "SAMPLE STORE",
            description = "Complimentary service voucher",
            redeemCode = null,
            imageUri = null,
            codeState = Coupon.CodeState.NO_CODE_NEEDED
        )

        val refined = CouponPostProcessor.refine(
            coupon = coupon,
            context = CouponFixContext(ocrText = ocrText)
        )

        assertEquals(null, refined.redeemCode)
        assertEquals(Coupon.CodeState.NO_CODE_NEEDED, refined.codeState)
    }

    @Test
    fun `refine uses review-safe description when placeholder cannot be resolved`() {
        val coupon = Coupon(
            storeName = "",
            description = "Coupon offer",
            redeemCode = null,
            imageUri = null,
        )

        val refined = CouponPostProcessor.refine(coupon)

        assertEquals("", refined.storeName)
        assertEquals("Needs review: offer details not extracted", refined.description)
    }
}

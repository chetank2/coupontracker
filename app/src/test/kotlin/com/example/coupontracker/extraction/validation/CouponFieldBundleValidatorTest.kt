package com.example.coupontracker.extraction.validation

import android.graphics.RectF
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponFieldBundleValidatorTest {

    private val validator = CouponFieldBundleValidator()

    @Test
    fun `flags alpha only code without visible code label`() {
        val result = validator.validate(
            bundle = FieldValueBundle(
                storeName = "PORTRONICS",
                description = "You won neck fan at Rs 1100",
                redeemCode = "PORTRONICS",
                expiryDateText = "expires in 4 hours"
            ),
            fields = mapOf(
                FieldType.STORE_NAME to candidate("PORTRONICS"),
                FieldType.DESCRIPTION to candidate("You won neck fan at Rs 1100"),
                FieldType.COUPON_CODE to candidate("PORTRONICS")
            ),
            rawOcrText = """
                PORTRONICS
                You won neck fan at Rs 1100
                Expires in 4 hours
            """.trimIndent(),
            ocrBlocks = emptyList(),
            imageHeight = 1000
        )

        assertFalse(result.trusted)
        assertTrue(result.needsAttention)
        assertTrue(result.reason.orEmpty().contains("code_duplicates_store"))
    }

    @Test
    fun `trusts alpha only code when code label supports it`() {
        val result = validator.validate(
            bundle = FieldValueBundle(
                storeName = "Myntra",
                description = "Flat 20% off on fashion",
                redeemCode = "WELCOME",
                expiryDateText = "2026-06-30"
            ),
            fields = mapOf(
                FieldType.STORE_NAME to candidate("Myntra"),
                FieldType.DESCRIPTION to candidate("Flat 20% off on fashion"),
                FieldType.COUPON_CODE to candidate("WELCOME"),
                FieldType.EXPIRY_DATE to candidate("2026-06-30")
            ),
            rawOcrText = """
                Myntra
                Flat 20% off on fashion
                Use code WELCOME
                Valid till 30 Jun 2026
            """.trimIndent(),
            ocrBlocks = listOf(
                block("Myntra", 100f),
                block("Flat 20% off on fashion", 140f),
                block("Use code WELCOME", 180f),
                block("Valid till 30 Jun 2026", 220f)
            ),
            imageHeight = 1000
        )

        assertTrue(result.reason.orEmpty(), result.trusted)
        assertFalse(result.needsAttention)
    }

    @Test
    fun `weak description stays reviewable instead of trusted`() {
        val result = validator.validate(
            bundle = FieldValueBundle(
                storeName = "Skullcandy",
                description = "you won off",
                redeemCode = "SKULL80",
                expiryDateText = null
            ),
            fields = mapOf(
                FieldType.STORE_NAME to candidate("Skullcandy"),
                FieldType.DESCRIPTION to candidate("you won off"),
                FieldType.COUPON_CODE to candidate("SKULL80")
            ),
            rawOcrText = """
                Skullcandy
                you won off
                Code SKULL80
            """.trimIndent(),
            ocrBlocks = emptyList(),
            imageHeight = 1000
        )

        assertTrue(result.trusted)
        assertTrue(result.needsAttention)
        assertTrue(result.reason.orEmpty().contains("weak_or_generic_description"))
    }

    @Test
    fun `flags repeated coupon sections inside one extraction region`() {
        val rawOcr = """
            CREDBASS
            Vouchers
            X active : 18 lifetime :428
            code: CREDBASS
            Details
            Redeem Now
            EXPIRES IN 13 DAYS
            you won flat 50 off on your next zepto Zepto Cafe order
            Zepto Cafe 4.38
            code: CAFE50
            Details
            Redeem Now
            EXPIRES IN 06 DAYS
        """.trimIndent()

        val result = validator.validate(
            bundle = FieldValueBundle(
                storeName = "CREDBASS",
                description = "Vouchers X active : 18 lifetime :428 you won flat 50 off on your next zepto Zepto Cafe order",
                redeemCode = "CAFE50",
                expiryDateText = "2025-10-07"
            ),
            fields = mapOf(
                FieldType.STORE_NAME to candidate("CREDBASS"),
                FieldType.DESCRIPTION to candidate("Vouchers X active : 18 lifetime :428"),
                FieldType.COUPON_CODE to candidate("CAFE50"),
                FieldType.EXPIRY_DATE to candidate("2025-10-07")
            ),
            rawOcrText = rawOcr,
            ocrBlocks = emptyList(),
            imageHeight = 1000
        )

        assertFalse(result.trusted)
        assertTrue(result.needsAttention)
        assertTrue(result.reason.orEmpty().contains("multiple_coupon_sections_in_single_region"))
    }

    private fun candidate(value: String): FieldCandidate {
        return FieldCandidate(
            value = value,
            confidence = 0.8f,
            source = "test",
            context = null
        )
    }

    private fun block(text: String, top: Float): TextBlock {
        return TextBlock(
            text = text,
            bounds = RectF(20f, top, 360f, top + 24f),
            confidence = 0.9f
        )
    }
}

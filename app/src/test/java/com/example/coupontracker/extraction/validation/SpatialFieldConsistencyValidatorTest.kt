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
class SpatialFieldConsistencyValidatorTest {

    private val validator = SpatialFieldConsistencyValidator()

    @Test
    fun `flags merchant and offer from upper card with code from lower card`() {
        val fields = mapOf(
            FieldType.STORE_NAME to candidate("Uber"),
            FieldType.DESCRIPTION to candidate("membership for 1 month"),
            FieldType.COUPON_CODE to candidate("563X9XFYDUR604GD"),
            FieldType.EXPIRY_DATE to candidate("2026-07-16")
        )
        val blocks = listOf(
            block("Uber", 40f, 60f),
            block("membership for 1 month", 72f, 96f),
            block("IKEA", 520f, 548f),
            block("Flat 10% off up to ₹2,000", 560f, 590f),
            block("563X9XFYDUR604GD", 612f, 640f),
            block("EXPIRES IN 29 DAYS", 656f, 684f)
        )

        val result = validator.validate(fields, blocks, imageHeight = 760)

        assertFalse(result.consistent)
    }

    @Test
    fun `accepts fields anchored within one coupon card`() {
        val fields = mapOf(
            FieldType.STORE_NAME to candidate("IKEA"),
            FieldType.DESCRIPTION to candidate("Flat 10% off up to ₹2,000"),
            FieldType.COUPON_CODE to candidate("563X9XFYDUR604GD"),
            FieldType.EXPIRY_DATE to candidate("2026-07-16")
        )
        val blocks = listOf(
            block("IKEA", 380f, 408f),
            block("Flat 10% off up to ₹2,000", 420f, 450f),
            block("563X9XFYDUR604GD", 474f, 502f),
            block("EXPIRES IN 29 DAYS", 520f, 548f)
        )

        val result = validator.validate(fields, blocks, imageHeight = 760)

        assertTrue(result.reason.orEmpty(), result.consistent)
    }

    private fun candidate(value: String): FieldCandidate {
        return FieldCandidate(
            value = value,
            confidence = 0.8f,
            source = "test",
            context = null
        )
    }

    private fun block(text: String, top: Float, bottom: Float): TextBlock {
        return TextBlock(
            text = text,
            bounds = RectF(24f, top, 340f, bottom),
            confidence = 0.9f
        )
    }
}

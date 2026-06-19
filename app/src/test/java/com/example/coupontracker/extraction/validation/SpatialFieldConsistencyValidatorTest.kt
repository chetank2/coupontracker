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

    @Test
    fun `accepts full screen duplicate store anchor when tight card anchors exist`() {
        val fields = mapOf(
            FieldType.STORE_NAME to candidate("PUMA"),
            FieldType.DESCRIPTION to candidate("Get Upto 50% Off Extra 33% Off"),
            FieldType.COUPON_CODE to candidate("KQSKLBLBIR"),
            FieldType.EXPIRY_DATE to candidate("2025-05-05")
        )
        val blocks = listOf(
            block("PUMA", 56f, 82f),
            block("Rewards header", 160f, 190f),
            block("Get Upto 50% Off Extra 33% Off at PUMA", 1067f, 1108f),
            block("KQSKLBLBIR", 1212f, 1242f),
            block("Expires on 05 May, 2025", 1280f, 1312f)
        )

        val result = validator.validate(fields, blocks, imageHeight = 1700)

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

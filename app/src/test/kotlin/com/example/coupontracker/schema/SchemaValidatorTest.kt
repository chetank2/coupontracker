package com.example.coupontracker.schema

import com.example.coupontracker.schema.ValidationResult.Invalid
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SchemaValidatorTest {

    private val schema = CouponSchema.SCHEMA

    @Test
    fun `validate accepts minimal valid coupon`() {
        val json = """
            {
              "storeName": "Amazon"
            }
        """.trimIndent()

        val result = SchemaValidator.validate(json, schema)
        assertTrue(result is ValidationResult.Valid)
    }

    @Test
    fun `validate rejects missing store name`() {
        val json = """
            {
                "redeemCode": "SAVE20",
                "expiryDate": "2025-12-31"
            }
        """.trimIndent()

        val result = SchemaValidator.validate(json, schema)
        assertTrue(result is Invalid)
        val issues = (result as Invalid).issues
        assertTrue(issues.any { it.contains("storeName") })
    }
}

package com.example.coupontracker.schema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CouponSchemaTest {

    @Test
    fun `schema exposes expected field names`() {
        val fieldNames = CouponSchema.SCHEMA.fields.map { it.name }
        assertEquals(
            listOf(
                "storeName",
                "description",
                "redeemCode",
                "expiryDate",
                "storeNameSource",
                "storeNameEvidence",
                "needsAttention"
            ),
            fieldNames
        )
    }

    @Test
    fun `only store name is required`() {
        val required = CouponSchema.SCHEMA.fields.filter { it.required }.map { it.name }
        assertEquals(listOf("storeName"), required)
    }

    @Test
    fun `optional fields remain optional`() {
        val descriptionField = CouponSchema.SCHEMA.fields.first { it.name == "description" }
        val codeField = CouponSchema.SCHEMA.fields.first { it.name == "redeemCode" }
        val expiryField = CouponSchema.SCHEMA.fields.first { it.name == "expiryDate" }
        val sourceField = CouponSchema.SCHEMA.fields.first { it.name == "storeNameSource" }
        val evidenceField = CouponSchema.SCHEMA.fields.first { it.name == "storeNameEvidence" }
        val needsAttentionField = CouponSchema.SCHEMA.fields.first { it.name == "needsAttention" }

        assertTrue(!descriptionField.required)
        assertTrue(!codeField.required)
        assertTrue(!expiryField.required)
        assertTrue(!sourceField.required)
        assertTrue(!evidenceField.required)
        assertTrue(!needsAttentionField.required)
    }
}

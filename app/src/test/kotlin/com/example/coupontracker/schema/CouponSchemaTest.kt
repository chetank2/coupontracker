package com.example.coupontracker.schema

import org.junit.Assert.assertEquals
import org.junit.Test

class CouponSchemaTest {

    @Test
    fun `schema exposes expected field names`() {
        val fieldNames = CouponSchema.getAllFieldNames()
        assertEquals(
            listOf(
                "storeName",
                "description",
                "cashback",
                "redeemCode",
                "expiryDate",
                "minOrderAmount"
            ),
            fieldNames
        )
    }

    @Test
    fun `only store name is required`() {
        val requiredFields = CouponSchema.getRequiredFieldNames()
        assertEquals(listOf("storeName"), requiredFields)
    }
}
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
                "cashback",
                "redeemCode",
                "expiryDate",
                "minOrderAmount"
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
    fun `cashback schema enforces expected structure`() {
        val cashbackField = CouponSchema.SCHEMA.fields.first { it.name == "cashback" }
        val objectType = cashbackField.type as FieldType.ObjectType
        val properties = objectType.properties
        assertEquals(setOf("type", "valueNum", "currency"), properties.keys)
        val allowed = (properties["type"]?.type as FieldType.EnumType).allowedValues
        assertTrue(allowed.containsAll(listOf("percent", "amount", "text")))
    }
}


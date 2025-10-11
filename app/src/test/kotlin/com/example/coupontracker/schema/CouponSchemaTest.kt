package com.example.coupontracker.schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for the CouponSchema definition
 */
class CouponSchemaTest {
    
    @Test
    fun `schema has correct field count`() {
        assertEquals("Schema should have exactly 7 fields", 7, CouponSchema.SCHEMA.fields.size)
    }
    
    @Test
    fun `schema has all required fields`() {
        val fieldNames = CouponSchema.getAllFieldNames()
        
        assertTrue("Should have storeName field", fieldNames.contains("storeName"))
        assertTrue("Should have description field", fieldNames.contains("description"))
        assertTrue("Should have cashback field", fieldNames.contains("cashback"))
        assertTrue("Should have offerText field", fieldNames.contains("offerText"))
        assertTrue("Should have redeemCode field", fieldNames.contains("redeemCode"))
        assertTrue("Should have expiryDate field", fieldNames.contains("expiryDate"))
        assertTrue("Should have minOrderAmount field", fieldNames.contains("minOrderAmount"))
    }
    
    @Test
    fun `storeName field is required`() {
        assertTrue("storeName should be required", CouponSchema.isFieldRequired("storeName"))
    }
    
    @Test
    fun `description field is required`() {
        assertTrue("description should be required", CouponSchema.isFieldRequired("description"))
    }
    
    @Test
    fun `cashback field is optional`() {
        assertFalse("cashback should be optional", CouponSchema.isFieldRequired("cashback"))
    }
    
    @Test
    fun `redeemCode field is optional`() {
        assertFalse("redeemCode should be optional", CouponSchema.isFieldRequired("redeemCode"))
    }
    
    @Test
    fun `expiryDate field is optional`() {
        assertFalse("expiryDate should be optional", CouponSchema.isFieldRequired("expiryDate"))
    }
    
    @Test
    fun `all fields have metadata`() {
        CouponSchema.SCHEMA.fields.forEach { field ->
            assertNotNull("${field.name} should have metadata", field.metadata)
            assertFalse("${field.name} should have description", field.metadata.description.isBlank())
        }
    }
    
    @Test
    fun `all fields have examples`() {
        CouponSchema.SCHEMA.fields.forEach { field ->
            assertTrue(
                "${field.name} should have at least one example",
                field.metadata.examples.isNotEmpty()
            )
        }
    }
    
    @Test
    fun `all fields have hints`() {
        CouponSchema.SCHEMA.fields.forEach { field ->
            assertTrue(
                "${field.name} should have at least one hint",
                field.metadata.hints.isNotEmpty()
            )
        }
    }
    
    @Test
    fun `cashback has correct structure`() {
        val cashbackField = CouponSchema.getField("cashback")
        assertNotNull("Cashback field should exist", cashbackField)
        
        val objectType = cashbackField?.type as? FieldType.ObjectType
        assertNotNull("Cashback should be ObjectType", objectType)
        
        val properties = objectType?.properties
        assertNotNull("Cashback should have properties", properties)
        
        assertTrue("Cashback should have 'type' property", properties?.containsKey("type") == true)
        assertTrue("Cashback should have 'valueNum' property", properties?.containsKey("valueNum") == true)
        assertTrue("Cashback should have 'currency' property", properties?.containsKey("currency") == true)
    }
    
    @Test
    fun `cashback type is enum with correct values`() {
        val cashbackField = CouponSchema.getField("cashback")
        val objectType = cashbackField?.type as? FieldType.ObjectType
        val typeProperty = objectType?.properties?.get("type")
        
        val enumType = typeProperty?.type as? FieldType.EnumType
        assertNotNull("Cashback type should be EnumType", enumType)
        
        val allowedValues = enumType?.allowedValues
        assertEquals("Should have 3 allowed values", 3, allowedValues?.size)
        assertTrue("Should allow 'percent'", allowedValues?.contains("percent") == true)
        assertTrue("Should allow 'amount'", allowedValues?.contains("amount") == true)
        assertTrue("Should allow 'text'", allowedValues?.contains("text") == true)
    }
    
    @Test
    fun `schema has global rules`() {
        assertTrue("Schema should have global rules", CouponSchema.SCHEMA.globalRules.isNotEmpty())
    }
    
    @Test
    fun `schema version is set`() {
        assertFalse("Schema version should not be empty", CouponSchema.VERSION.isBlank())
        assertEquals("Schema version should be 1.0.0", "1.0.0", CouponSchema.VERSION)
    }
    
    @Test
    fun `getField returns correct field`() {
        val storeNameField = CouponSchema.getField("storeName")
        assertNotNull("Should find storeName field", storeNameField)
        assertEquals("Field name should match", "storeName", storeNameField?.name)
    }
    
    @Test
    fun `getField returns null for non-existent field`() {
        val field = CouponSchema.getField("nonExistentField")
        assertNull("Should return null for non-existent field", field)
    }
    
    @Test
    fun `getRequiredFieldNames returns only required fields`() {
        val requiredFields = CouponSchema.getRequiredFieldNames()
        
        assertTrue("Should include storeName", requiredFields.contains("storeName"))
        assertTrue("Should include description", requiredFields.contains("description"))
        assertFalse("Should not include cashback", requiredFields.contains("cashback"))
        assertFalse("Should not include redeemCode", requiredFields.contains("redeemCode"))
    }
    
    @Test
    fun `storeName has validation rules`() {
        val field = CouponSchema.getField("storeName")
        assertTrue(
            "storeName should have validation rules",
            field?.metadata?.validationRules?.isNotEmpty() == true
        )
    }
    
    @Test
    fun `redeemCode has extraction hints`() {
        val field = CouponSchema.getField("redeemCode")
        assertNotNull(
            "redeemCode should have extraction hints",
            field?.metadata?.extractionHints
        )
    }
    
    @Test
    fun `expiryDate preserves format hint exists`() {
        val field = CouponSchema.getField("expiryDate")
        val hints = field?.metadata?.hints ?: emptyList()
        
        assertTrue(
            "expiryDate should have hint about preserving format",
            hints.any { it.contains("EXACTLY as shown", ignoreCase = true) }
        )
    }
}


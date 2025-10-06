package com.example.coupontracker.schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for SchemaValidator
 */
class SchemaValidatorTest {
    
    private val schema = CouponSchema.SCHEMA
    
    @Test
    fun `validate accepts valid JSON`() {
        val validJson = """
            {
                "storeName": "Amazon",
                "description": "Get 50% off on electronics",
                "cashback": {"type": "percent", "valueNum": 50, "currency": null},
                "offerText": "Flat 50% off on select electronics",
                "redeemCode": "SAVE50",
                "expiryDate": "31 May, 2025",
                "minOrderAmount": "₹999"
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(validJson, schema)
        assertTrue("Should accept valid JSON", result.isValid())
    }
    
    @Test
    fun `validate accepts minimal valid JSON with nulls`() {
        val validJson = """
            {
                "storeName": "Flipkart",
                "description": "Coupon offer",
                "cashback": null,
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(validJson, schema)
        assertTrue("Should accept valid JSON with nulls for optional fields", result.isValid())
    }
    
    @Test
    fun `validate rejects missing required field`() {
        val invalidJson = """
            {
                "description": "Get 50% off",
                "cashback": null,
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject JSON missing required field", result.isValid())
        assertTrue(
            "Error should mention missing field",
            result.getIssues().any { it.contains("storeName") }
        )
    }
    
    @Test
    fun `validate rejects null required field`() {
        val invalidJson = """
            {
                "storeName": null,
                "description": "Test",
                "cashback": null,
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject null required field", result.isValid())
    }
    
    @Test
    fun `validate rejects unknown keys`() {
        val invalidJson = """
            {
                "storeName": "Amazon",
                "description": "Test",
                "cashback": null,
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null,
                "unknownField": "value"
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject unknown keys", result.isValid())
        assertTrue(
            "Error should mention unknown key",
            result.getIssues().any { it.contains("unknownField") }
        )
    }
    
    @Test
    fun `validate rejects invalid type`() {
        val invalidJson = """
            {
                "storeName": 12345,
                "description": "Test",
                "cashback": null,
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject invalid type", result.isValid())
        assertTrue(
            "Error should mention type mismatch",
            result.getIssues().any { it.contains("string") || it.contains("number") }
        )
    }
    
    @Test
    fun `validate handles nested object (cashback)`() {
        val validJson = """
            {
                "storeName": "Amazon",
                "description": "Test",
                "cashback": {
                    "type": "percent",
                    "valueNum": 50,
                    "currency": null
                },
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(validJson, schema)
        assertTrue("Should accept valid nested object", result.isValid())
    }
    
    @Test
    fun `validate rejects invalid enum value`() {
        val invalidJson = """
            {
                "storeName": "Amazon",
                "description": "Test",
                "cashback": {
                    "type": "invalid_type",
                    "valueNum": 50,
                    "currency": null
                },
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject invalid enum value", result.isValid())
    }
    
    @Test
    fun `validate rejects missing required nested field`() {
        val invalidJson = """
            {
                "storeName": "Amazon",
                "description": "Test",
                "cashback": {
                    "valueNum": 50,
                    "currency": null
                },
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject missing required nested field", result.isValid())
        assertTrue(
            "Error should mention missing 'type' field",
            result.getIssues().any { it.contains("type") }
        )
    }
    
    @Test
    fun `validate rejects invalid nested type`() {
        val invalidJson = """
            {
                "storeName": "Amazon",
                "description": "Test",
                "cashback": {
                    "type": "percent",
                    "valueNum": "fifty",
                    "currency": null
                },
                "offerText": null,
                "redeemCode": null,
                "expiryDate": null,
                "minOrderAmount": null
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject invalid nested type", result.isValid())
        assertTrue(
            "Error should mention valueNum type issue",
            result.getIssues().any { it.contains("valueNum") }
        )
    }
    
    @Test
    fun `validate rejects malformed JSON`() {
        val malformedJson = """
            {
                "storeName": "Amazon"
                "description": "Missing comma"
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(malformedJson, schema)
        assertFalse("Should reject malformed JSON", result.isValid())
        assertTrue(
            "Error should mention JSON syntax",
            result.getIssues().any { it.contains("JSON") || it.contains("syntax") }
        )
    }
    
    @Test
    fun `validate provides detailed error messages`() {
        val invalidJson = """
            {
                "description": "Test",
                "unknownField": "value",
                "cashback": "invalid"
            }
        """.trimIndent()
        
        val result = SchemaValidator.validate(invalidJson, schema)
        assertFalse("Should reject invalid JSON", result.isValid())
        
        val issues = result.getIssues()
        assertTrue("Should have multiple issues", issues.size > 1)
    }
    
    @Test
    fun `validate accepts all allowed enum values`() {
        val enumValues = listOf("percent", "amount", "text")
        
        enumValues.forEach { enumValue ->
            val validJson = """
                {
                    "storeName": "Amazon",
                    "description": "Test",
                    "cashback": {
                        "type": "$enumValue",
                        "valueNum": 50,
                        "currency": null
                    },
                    "offerText": null,
                    "redeemCode": null,
                    "expiryDate": null,
                    "minOrderAmount": null
                }
            """.trimIndent()
            
            val result = SchemaValidator.validate(validJson, schema)
            assertTrue("Should accept enum value: $enumValue", result.isValid())
        }
    }
    
    @Test
    fun `ValidationResult provides helper methods`() {
        val validResult = ValidationResult.Valid
        assertTrue("isValid should return true for Valid", validResult.isValid())
        assertTrue("getIssues should return empty for Valid", validResult.getIssues().isEmpty())
        
        val invalidResult = ValidationResult.Invalid(listOf("Error 1", "Error 2"))
        assertFalse("isValid should return false for Invalid", invalidResult.isValid())
        assertEquals("getIssues should return issues", 2, invalidResult.getIssues().size)
    }
}


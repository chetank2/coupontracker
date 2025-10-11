package com.example.coupontracker.schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for GBNFGenerator
 */
class GBNFGeneratorTest {
    
    private val schema = CouponSchema.SCHEMA
    
    @Test
    fun `generate creates valid grammar`() {
        val grammar = GBNFGenerator.generate(schema)
        
        assertFalse("Grammar should not be empty", grammar.isBlank())
        assertTrue("Grammar should be valid", GBNFGenerator.validateGrammar(grammar))
    }
    
    @Test
    fun `grammar has root rule`() {
        val grammar = GBNFGenerator.generate(schema)
        
        assertTrue("Grammar should have root rule", grammar.contains("root ::="))
    }
    
    @Test
    fun `grammar includes all field rules`() {
        val grammar = GBNFGenerator.generate(schema)
        
        schema.fields.forEach { field ->
            assertTrue(
                "Grammar should include rule for ${field.name}",
                grammar.contains("${field.name}-value ::=")
            )
        }
    }
    
    @Test
    fun `grammar has primitive rules`() {
        val grammar = GBNFGenerator.generate(schema)
        
        assertTrue("Should have string rule", grammar.contains("string ::="))
        assertTrue("Should have number rule", grammar.contains("number ::="))
        assertTrue("Should have boolean rule", grammar.contains("boolean ::="))
        assertTrue("Should have null rule", grammar.contains("null ::="))
        assertTrue("Should have whitespace rule", grammar.contains("ws ::="))
    }
    
    @Test
    fun `grammar includes schema metadata`() {
        val grammar = GBNFGenerator.generate(schema)
        
        assertTrue("Should include schema name", grammar.contains(schema.name))
        assertTrue("Should include schema version", grammar.contains(schema.version))
    }
    
    @Test
    fun `validateGrammar accepts valid grammar`() {
        val validGrammar = """
            root ::= "{" "test" "}"
            string ::= "test"
            number ::= "123"
            ws ::= " "
        """.trimIndent()
        
        assertTrue("Should accept valid grammar", GBNFGenerator.validateGrammar(validGrammar))
    }
    
    @Test
    fun `validateGrammar rejects invalid grammar`() {
        val invalidGrammar = "invalid grammar without required rules"
        
        assertFalse("Should reject invalid grammar", GBNFGenerator.validateGrammar(invalidGrammar))
    }
    
    @Test
    fun `generated grammar handles optional fields`() {
        val grammar = GBNFGenerator.generate(schema)
        
        // redeemCode is optional, should allow null
        assertTrue(
            "Optional fields should allow null",
            grammar.contains("redeemCode-value ::=") && 
            grammar.lines().any { line ->
                line.contains("redeemCode-value") && line.contains("null")
            }
        )
    }
    
    @Test
    fun `generated grammar handles required fields`() {
        val grammar = GBNFGenerator.generate(schema)
        
        // storeName is required - check its rule doesn't allow standalone null
        val storeNameRule = grammar.lines().find { it.startsWith("storeName-value ::=") }
        assertNotNull("Should have storeName rule", storeNameRule)
        
        // Required fields should just be the type (e.g., "string" not "string | null")
        assertTrue(
            "Required field should not allow null",
            storeNameRule!!.trim() == "storeName-value ::= string"
        )
    }
    
    @Test
    fun `generated grammar handles object types`() {
        val grammar = GBNFGenerator.generate(schema)
        
        // cashback is an object type
        assertTrue(
            "Should generate object rule for cashback",
            grammar.contains("cashback-value ::=") &&
            grammar.contains("cashback-object ::=")
        )
    }
    
    @Test
    fun `generated grammar handles enum types`() {
        val grammar = GBNFGenerator.generate(schema)
        
        // cashback.type is an enum with values: percent, amount, text
        assertTrue(
            "Should handle enum type",
            grammar.contains("percent") &&
            grammar.contains("amount") &&
            grammar.contains("text")
        )
    }
    
    @Test
    fun `formatGrammar preserves structure`() {
        val grammar = GBNFGenerator.generate(schema)
        val formatted = GBNFGenerator.formatGrammar(grammar)
        
        assertFalse("Formatted grammar should not be empty", formatted.isBlank())
        assertTrue("Should preserve root rule", formatted.contains("root ::="))
    }
    
    @Test
    fun `generated grammar is deterministic`() {
        val grammar1 = GBNFGenerator.generate(schema)
        val grammar2 = GBNFGenerator.generate(schema)
        
        assertEquals("Grammar should be deterministic", grammar1, grammar2)
    }
    
    @Test
    fun `grammar follows GBNF syntax`() {
        val grammar = GBNFGenerator.generate(schema)
        
        // Check basic GBNF syntax elements
        assertTrue("Should use ::= for definitions", grammar.contains("::="))
        assertTrue("Should use | for alternatives", grammar.contains("|"))
        assertTrue("Should use quotes for literals", grammar.contains("\""))
    }
    
    @Test
    fun `root rule includes all fields in order`() {
        val grammar = GBNFGenerator.generate(schema)
        val rootRule = grammar.substringAfter("root ::=").substringBefore("\n\n")
        
        // Check that all field names appear in root rule
        schema.fields.forEach { field ->
            assertTrue(
                "Root rule should reference ${field.name}",
                rootRule.contains(field.name)
            )
        }
    }
}


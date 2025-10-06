package com.example.coupontracker.schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PromptGenerator
 */
class PromptGeneratorTest {
    
    private val schema = CouponSchema.SCHEMA
    
    @Test
    fun `generateSchemaJson includes all fields`() {
        val schemaJson = PromptGenerator.generateSchemaJson(schema)
        
        assertTrue("Should contain storeName", schemaJson.contains("\"storeName\""))
        assertTrue("Should contain description", schemaJson.contains("\"description\""))
        assertTrue("Should contain cashback", schemaJson.contains("\"cashback\""))
        assertTrue("Should contain offerText", schemaJson.contains("\"offerText\""))
        assertTrue("Should contain redeemCode", schemaJson.contains("\"redeemCode\""))
        assertTrue("Should contain expiryDate", schemaJson.contains("\"expiryDate\""))
        assertTrue("Should contain minOrderAmount", schemaJson.contains("\"minOrderAmount\""))
    }
    
    @Test
    fun `generateSchemaJson marks required fields correctly`() {
        val schemaJson = PromptGenerator.generateSchemaJson(schema)
        
        // storeName and description are required - should show type without |null
        assertTrue("storeName should be str", schemaJson.contains("\"storeName\":str"))
        assertTrue("description should be str", schemaJson.contains("\"description\":str"))
        
        // Optional fields should have |null
        assertTrue("cashback should be optional", schemaJson.contains("\"cashback\":obj|null"))
        assertTrue("redeemCode should be optional", schemaJson.contains("\"redeemCode\":str|null"))
    }
    
    @Test
    fun `generateSystemPrompt contains all fields`() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        schema.fields.forEach { field ->
            assertTrue(
                "System prompt should mention ${field.name}",
                systemPrompt.contains(field.name)
            )
        }
    }
    
    @Test
    fun `generateSystemPrompt contains global rules`() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        schema.globalRules.forEach { rule ->
            assertTrue(
                "System prompt should contain rule: $rule",
                systemPrompt.contains(rule)
            )
        }
    }
    
    @Test
    fun `generateSystemPrompt uses ChatML format`() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        assertTrue("Should start with <|im_start|>system", systemPrompt.startsWith("<|im_start|>system"))
        assertTrue("Should end with <|im_end|>", systemPrompt.endsWith("<|im_end|>"))
    }
    
    @Test
    fun `generateUserPrompt uses ChatML format`() {
        val userPrompt = PromptGenerator.generateUserPrompt("Test OCR text")
        
        assertTrue("Should start with <|im_start|>user", userPrompt.startsWith("<|im_start|>user"))
        assertTrue("Should end with <|im_end|>", userPrompt.endsWith("<|im_end|>"))
        assertTrue("Should contain OCR text", userPrompt.contains("Test OCR text"))
    }
    
    @Test
    fun `generateAssistantPrimer starts JSON object`() {
        val primer = PromptGenerator.generateAssistantPrimer()
        
        assertTrue("Should start with assistant tag", primer.contains("<|im_start|>assistant"))
        assertTrue("Should end with opening brace", primer.endsWith("{"))
    }
    
    @Test
    fun `generateCompletePrompt combines all parts`() {
        val completePrompt = PromptGenerator.generateCompletePrompt(schema, "Sample OCR")
        
        assertTrue("Should contain system section", completePrompt.contains("<|im_start|>system"))
        assertTrue("Should contain user section", completePrompt.contains("<|im_start|>user"))
        assertTrue("Should contain assistant section", completePrompt.contains("<|im_start|>assistant"))
        assertTrue("Should contain OCR text", completePrompt.contains("Sample OCR"))
    }
    
    @Test
    fun `generateSystemPrompt includes field examples`() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        // Check that at least some examples are present
        val storeNameField = schema.fields.find { it.name == "storeName" }
        val storeNameExamples = storeNameField?.metadata?.examples ?: emptyList()
        
        if (storeNameExamples.isNotEmpty()) {
            val hasExample = storeNameExamples.any { example ->
                systemPrompt.contains(example)
            }
            assertTrue("Should contain at least one storeName example", hasExample)
        }
    }
    
    @Test
    fun `generateSystemPrompt includes field hints`() {
        val systemPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        // Check that field hints are included
        val redeemCodeField = schema.fields.find { it.name == "redeemCode" }
        val hints = redeemCodeField?.metadata?.hints ?: emptyList()
        
        if (hints.isNotEmpty()) {
            val hasHint = hints.any { hint ->
                systemPrompt.contains(hint)
            }
            assertTrue("Should contain at least one redeemCode hint", hasHint)
        }
    }
    
    @Test
    fun `sanitizeOcrSnippet handles empty input`() {
        val result = PromptGenerator.sanitizeOcrSnippet("")
        assertEquals("Should return placeholder for empty input", "(no OCR text captured)", result)
    }
    
    @Test
    fun `sanitizeOcrSnippet truncates long input`() {
        val longText = "a".repeat(3000)
        val result = PromptGenerator.sanitizeOcrSnippet(longText, maxChars = 2000)
        
        assertTrue("Should truncate to max chars", result.length <= 2001) // +1 for ellipsis
        assertTrue("Should end with ellipsis", result.endsWith("…"))
    }
    
    @Test
    fun `sanitizeOcrSnippet preserves short input`() {
        val shortText = "Short coupon text"
        val result = PromptGenerator.sanitizeOcrSnippet(shortText)
        
        assertEquals("Should preserve short text", shortText, result)
    }
    
    @Test
    fun `estimateTokenCount returns reasonable value`() {
        val prompt = "Hello world" // ~11 chars
        val tokenCount = PromptGenerator.estimateTokenCount(prompt)
        
        assertTrue("Should estimate reasonable token count", tokenCount in 2..4) // ~2-3 tokens
    }
    
    @Test
    fun `generateRules returns schema rules`() {
        val rules = PromptGenerator.generateRules(schema)
        
        assertEquals("Should return all global rules", schema.globalRules.size, rules.size)
        assertTrue("Should contain rules", rules.isNotEmpty())
    }
    
    @Test
    fun `generated prompt is deterministic`() {
        val prompt1 = PromptGenerator.generateSystemPrompt(schema)
        val prompt2 = PromptGenerator.generateSystemPrompt(schema)
        
        assertEquals("Generated prompts should be identical", prompt1, prompt2)
    }
    
    @Test
    fun `compare with manual prompt structure`() {
        val generatedPrompt = PromptGenerator.generateSystemPrompt(schema)
        
        // Manual prompt characteristics (from LocalLlmOcrService.kt:645)
        val manualPromptFeatures = listOf(
            "JSON extractor",
            "Schema (exact key order)",
            "Rules:",
            "Field Extraction Guide:",
            "storeName",
            "redeemCode",
            "expiryDate",
            "cashback",
            "minOrderAmount",
            "offerText",
            "description"
        )
        
        manualPromptFeatures.forEach { feature ->
            assertTrue(
                "Generated prompt should contain feature: $feature",
                generatedPrompt.contains(feature, ignoreCase = true)
            )
        }
    }
    
    @Test
    fun `generated prompt maintains field order`() {
        val prompt = PromptGenerator.generateSystemPrompt(schema)
        
        // Check that fields appear in schema order
        val storeNamePos = prompt.indexOf("storeName")
        val descriptionPos = prompt.indexOf("description")
        val cashbackPos = prompt.indexOf("cashback")
        val redeemCodePos = prompt.indexOf("redeemCode")
        
        assertTrue("Field order should be maintained", storeNamePos > 0)
        assertTrue("Description should come after in guide", descriptionPos > storeNamePos)
    }
}


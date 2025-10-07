package com.example.coupontracker.schema

import org.json.JSONObject

/**
 * Compact prompt generator - reduces token count by 60-70%
 * Optimized for grammar-enforced JSON output where structure is guaranteed.
 * 
 * Target: <350 tokens (vs 920 tokens with verbose generator)
 */
object CompactPromptGenerator {
    
    /**
     * Generate minimal system prompt (grammar handles structure)
     */
    fun generateSystemPrompt(schema: Schema): String {
        val schemaJson = generateCompactSchemaJson(schema)
        
        return """<|im_start|>system
Extract coupon as JSON. Output ONLY valid JSON, no text.

Schema: $schemaJson

Rules:
- All keys required (null if missing)
- No text before/after JSON
- Start with { end with }

Key fields:
${generateCompactFieldGuide(schema)}
<|im_end|>"""
    }
    
    /**
     * Generate compact field guide (1 line per field, no verbose examples)
     */
    private fun generateCompactFieldGuide(schema: Schema): String {
        return schema.fields.joinToString("\n") { field ->
            val hint = field.metadata.hints.firstOrNull() ?: field.metadata.description
            val example = field.metadata.examples.firstOrNull()?.let { "e.g. \"$it\"" } ?: ""
            "- ${field.name}: ${hint.take(60)} $example"
        }
    }
    
    /**
     * Generate compact schema JSON (inline, no pretty-print)
     */
    private fun generateCompactSchemaJson(schema: Schema): String {
        val json = JSONObject()
        schema.fields.forEach { field ->
            val typeStr = when (field.type) {
                is FieldType.StringType -> "str"
                is FieldType.NumberType -> "num"
                is FieldType.BooleanType -> "bool"
                is FieldType.DateType -> "str"
                is FieldType.EnumType -> "str"
                is FieldType.ObjectType -> "obj"
                is FieldType.AnyType -> "any"
            }
            json.put(field.name, "$typeStr|null")
        }
        return json.toString()  // Single line, no indent
    }
    
    /**
     * Generate user prompt
     */
    fun generateUserPrompt(ocrText: String): String {
        return "<|im_start|>user\n$ocrText<|im_end|>"
    }
    
    /**
     * Generate assistant primer
     */
    fun generateAssistantPrimer(): String {
        return "<|im_start|>assistant\n{"
    }
    
    /**
     * Generate complete compact prompt
     */
    fun generateCompletePrompt(schema: Schema, ocrText: String): String {
        return buildString {
            append(generateSystemPrompt(schema))
            append("\n")
            append(generateUserPrompt(ocrText))
            append("\n")
            append(generateAssistantPrimer())
        }
    }
}

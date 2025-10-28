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
        val fieldHints = """
- storeName: primary merchant/app name from header or logo; never null when brand text exists.
- redeemCode: coupon/promo code text only; null when absent.
- expiryDate: copy exact wording for validity or return null if none.
- description: main offer sentence verbatim with symbols.
""".trimIndent()

        return """<|im_start|>system
Return strict JSON with keys storeName, redeemCode, expiryDate, description.
Schema: $schemaJson
Rules:
- Output JSON only (no prose).
- All keys required; use null only if information is truly absent.
$fieldHints
<|im_end|>"""
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
                is FieldType.ArrayType -> "arr"
                is FieldType.NullableType -> "null"
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

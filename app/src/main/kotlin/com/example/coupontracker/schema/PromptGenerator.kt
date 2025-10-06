package com.example.coupontracker.schema

import org.json.JSONObject

/**
 * Generates LLM prompts from schema definitions.
 * Produces deterministic, stable prompts for consistent extraction.
 */
object PromptGenerator {
    
    /**
     * Generate complete system prompt for Qwen2 ChatML format
     */
    fun generateSystemPrompt(schema: Schema): String {
        return buildString {
            appendLine("<|im_start|>system")
            appendLine("You are a JSON extractor. Extract coupon data and output ONLY valid JSON.")
            appendLine()
            appendLine("Schema (exact key order):")
            appendLine(generateSchemaJson(schema))
            appendLine()
            appendLine("Rules:")
            schema.globalRules.forEach { rule ->
                appendLine("- $rule")
            }
            appendLine()
            appendLine("Field Extraction Guide:")
            appendLine()
            
            // Generate field-specific extraction guidelines
            schema.fields.forEachIndexed { index, field ->
                appendLine("${index + 1}. ${field.name}: ${field.metadata.description}")
                
                // Add hints
                if (field.metadata.hints.isNotEmpty()) {
                    field.metadata.hints.forEach { hint ->
                        appendLine("   - $hint")
                    }
                }
                
                // Add examples if available
                if (field.metadata.examples.isNotEmpty()) {
                    val exampleLine = when {
                        field.type is FieldType.ObjectType -> {
                            // Use first example as-is for complex types
                            "   - Example: ${field.metadata.examples.first()}"
                        }
                        field.metadata.examples.size > 1 -> {
                            // Show multiple examples inline
                            "   - Examples: ${field.metadata.examples.take(3).joinToString(", ") { "\"$it\"" }}"
                        }
                        else -> {
                            "   - Example: \"${field.metadata.examples.first()}\""
                        }
                    }
                    appendLine(exampleLine)
                }
                
                appendLine()
            }
            
            append("<|im_end|>")
        }
    }
    
    /**
     * Generate user prompt with OCR text
     */
    fun generateUserPrompt(ocrText: String): String {
        return buildString {
            appendLine("<|im_start|>user")
            appendLine("Extract coupon from OCR:")
            appendLine(ocrText)
            append("<|im_end|>")
        }
    }
    
    /**
     * Generate assistant primer to start JSON output
     */
    fun generateAssistantPrimer(): String {
        return "<|im_start|>assistant\n{"
    }
    
    /**
     * Generate complete prompt (system + user + primer)
     */
    fun generateCompletePrompt(schema: Schema, ocrText: String): String {
        return buildString {
            append(generateSystemPrompt(schema))
            appendLine()
            append(generateUserPrompt(ocrText))
            appendLine()
            append(generateAssistantPrimer())
        }
    }
    
    /**
     * Generate schema JSON representation for prompt
     * Format: {"field1":type|null,"field2":type|null,...}
     */
    fun generateSchemaJson(schema: Schema): String {
        val pairs = schema.fields.map { field ->
            val typeString = when (field.type) {
                is FieldType.StringType -> "str"
                is FieldType.NumberType -> "num"
                is FieldType.BooleanType -> "bool"
                is FieldType.DateType -> "str" // Dates as strings to preserve format
                is FieldType.EnumType -> "str"
                is FieldType.ObjectType -> "obj"
                is FieldType.ArrayType -> "arr"
                is FieldType.NullableType -> "any"
            }
            val nullability = if (field.required) typeString else "$typeString|null"
            "\"${field.name}\":$nullability"
        }
        return "{${pairs.joinToString(",")}}"
    }
    
    /**
     * Generate rules section
     */
    fun generateRules(schema: Schema): List<String> {
        return schema.globalRules
    }
    
    /**
     * Generate examples section for a field
     */
    fun generateExamples(field: SchemaField): String {
        return when {
            field.metadata.examples.isEmpty() -> ""
            field.type is FieldType.ObjectType -> {
                // Complex type - show full example
                field.metadata.examples.first()
            }
            else -> {
                // Simple type - show multiple examples
                field.metadata.examples.take(3).joinToString(", ") { "\"$it\"" }
            }
        }
    }
    
    /**
     * Count tokens in prompt (rough approximation)
     * Uses ~1 token per 4 characters as heuristic
     */
    fun estimateTokenCount(prompt: String): Int {
        return (prompt.length / 4.0).toInt()
    }
    
    /**
     * Sanitize OCR snippet for prompt (limit length, clean formatting)
     */
    fun sanitizeOcrSnippet(ocrText: String, maxChars: Int = 2000): String {
        if (ocrText.isBlank()) return "(no OCR text captured)"
        val normalized = ocrText.trim().replace("\r", "")
        return if (normalized.length <= maxChars) {
            normalized
        } else {
            normalized.substring(0, maxChars).trimEnd() + "…"
        }
    }
}


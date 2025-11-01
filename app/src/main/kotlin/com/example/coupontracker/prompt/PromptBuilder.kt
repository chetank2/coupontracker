package com.example.coupontracker.prompt

import com.example.coupontracker.ocr.OcrResultProcessor
import com.example.coupontracker.ocr.OcrResultProcessor.OcrTile
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.FieldType
import com.example.coupontracker.schema.Schema
import com.example.coupontracker.schema.SchemaField
import com.example.coupontracker.schema.PromptGenerator
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds LLM prompts from OCR output while applying normalization and schema guardrails.
 */
class PromptBuilder(
    private val schema: Schema = CouponSchema.SCHEMA,
    private val ocrResultProcessor: OcrResultProcessor = OcrResultProcessor()
) {

    data class Result(
        val prompt: String,
        val systemPrompt: String,
        val userPrompt: String,
        val assistantPrimer: String,
        val processedOcr: OcrResultProcessor.ProcessedOcrResult,
        val truncatedOcrForPrompt: String
    )

    companion object {
        private const val MAX_OCR_CHARS = 1_200
    }

    fun build(rawOcrText: String, tiles: List<OcrTile> = emptyList()): Result {
        val processed = ocrResultProcessor.process(rawOcrText, tiles)
        val truncatedOcr = PromptGenerator.sanitizeOcrSnippet(processed.normalizedText, MAX_OCR_CHARS)
        val system = buildSystemPrompt(processed)
        val user = buildUserPrompt(truncatedOcr, processed)
        val assistant = "<|im_start|>assistant\n{"
        val prompt = listOf(system, user, assistant).joinToString(separator = "\n\n")
        return Result(
            prompt = prompt,
            systemPrompt = system,
            userPrompt = user,
            assistantPrimer = assistant,
            processedOcr = processed,
            truncatedOcrForPrompt = truncatedOcr
        )
    }

    private fun buildSystemPrompt(processed: OcrResultProcessor.ProcessedOcrResult): String {
        val guardrails = buildJsonSchema(schema)
        val requiredFields = schema.getRequiredFields().joinToString { it.name }
        val confidenceSummary = String.format(
            Locale.US,
            "Average OCR confidence: %.2f | Unknown glyph rate: %.2f | Tiles: %d",
            processed.averageConfidence,
            processed.unknownGlyphRate,
            processed.tileCount
        )
        val qualityHint = "OCR normalization applied (deskewed, merged lines, UI noise removed). $confidenceSummary"
        return buildString {
            appendLine("<|im_start|>system")
            appendLine("You are a contractual JSON generator. Emit exactly one JSON object that validates against the schema below.")
            appendLine("Never include commentary before or after the JSON payload.")
            appendLine()
            appendLine("Schema version: ${schema.version}")
            appendLine("JSON Schema:")
            appendLine(guardrails)
            appendLine()
            appendLine("Rules:")
            appendLine("- Required keys: $requiredFields")
            appendLine("- Use null (without quotes) when a field is absent.")
            appendLine("- Preserve wording verbatim; never add arithmetic or commentary.")
            appendLine("- Additional properties are forbidden (must not appear in output).")
            appendLine()
            appendLine(qualityHint)
            append("<|im_end|>")
        }
    }

    private fun buildUserPrompt(
        truncatedOcr: String,
        processed: OcrResultProcessor.ProcessedOcrResult
    ): String {
        val metrics = String.format(
            Locale.US,
            "metrics: avg_conf=%.2f unknown_rate=%.2f", processed.averageConfidence, processed.unknownGlyphRate
        )
        return buildString {
            appendLine("<|im_start|>user")
            appendLine("Structure the following OCR text into JSON (quality $metrics). Only respond with JSON:")
            appendLine(truncatedOcr)
            append("<|im_end|>")
        }
    }

    private fun buildJsonSchema(schema: Schema): String {
        val json = JSONObject()
        json.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        json.put("title", "${schema.name}Response")
        json.put("type", "object")
        json.put("additionalProperties", false)

        val properties = JSONObject()
        schema.fields.forEach { field ->
            properties.put(field.name, buildFieldSchema(field))
        }
        json.put("properties", properties)

        val required = schema.getRequiredFields()
        if (required.isNotEmpty()) {
            json.put("required", JSONArray(required.map { it.name }))
        }

        return json.toString(2)
    }

    private fun buildFieldSchema(field: SchemaField): JSONObject {
        return when (val type = field.type) {
            FieldType.StringType, FieldType.DateType -> baseSchema("string", field)
            FieldType.NumberType -> baseSchema("number", field)
            FieldType.BooleanType -> baseSchema("boolean", field)
            is FieldType.EnumType -> baseSchema("string", field).apply {
                put("enum", JSONArray(type.allowedValues))
            }
            is FieldType.ArrayType -> JSONObject().apply {
                put("type", "array")
                put("items", buildNestedType(type.itemType))
                applyNullability(this, field)
            }
            is FieldType.ObjectType -> JSONObject().apply {
                put("type", "object")
                val nestedProps = JSONObject()
                type.properties.values.forEach { nestedField ->
                    nestedProps.put(nestedField.name, buildFieldSchema(nestedField))
                }
                put("properties", nestedProps)
                val nestedRequired = type.properties.values.filter { it.required }
                if (nestedRequired.isNotEmpty()) {
                    put("required", JSONArray(nestedRequired.map { it.name }))
                }
                put("additionalProperties", false)
                applyNullability(this, field)
            }
            FieldType.NullableType -> JSONObject().apply {
                put("type", JSONArray(listOf("null", "string")))
            }
        }
    }

    private fun buildNestedType(type: FieldType): JSONObject {
        return when (type) {
            FieldType.StringType, FieldType.DateType -> JSONObject().put("type", "string")
            FieldType.NumberType -> JSONObject().put("type", "number")
            FieldType.BooleanType -> JSONObject().put("type", "boolean")
            is FieldType.EnumType -> JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(type.allowedValues))
            }
            is FieldType.ArrayType -> JSONObject().apply {
                put("type", "array")
                put("items", buildNestedType(type.itemType))
            }
            is FieldType.ObjectType -> JSONObject().apply {
                put("type", "object")
                val nestedProps = JSONObject()
                type.properties.values.forEach { nestedField ->
                    nestedProps.put(nestedField.name, buildFieldSchema(nestedField))
                }
                put("properties", nestedProps)
                val nestedRequired = type.properties.values.filter { it.required }
                if (nestedRequired.isNotEmpty()) {
                    put("required", JSONArray(nestedRequired.map { it.name }))
                }
                put("additionalProperties", false)
            }
            FieldType.NullableType -> JSONObject().put("type", JSONArray(listOf("null", "string")))
        }
    }

    private fun baseSchema(typeName: String, field: SchemaField): JSONObject {
        return JSONObject().apply {
            put("type", typeName)
            applyNullability(this, field)
        }
    }

    private fun applyNullability(target: JSONObject, field: SchemaField) {
        if (!field.required) {
            val existing = target.opt("type")
            when (existing) {
                is JSONArray -> {
                    val values = mutableListOf<String>()
                    for (i in 0 until existing.length()) {
                        values.add(existing.getString(i))
                    }
                    if (!values.contains("null")) {
                        values.add("null")
                    }
                    target.put("type", JSONArray(values))
                }
                is String -> {
                    target.put("type", JSONArray(listOf(existing, "null")))
                }
            }
        }
    }
}

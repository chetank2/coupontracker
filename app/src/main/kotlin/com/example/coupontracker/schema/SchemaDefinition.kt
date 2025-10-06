package com.example.coupontracker.schema

/**
 * Schema-driven architecture for coupon extraction.
 * Defines field types, metadata, and validation rules.
 */

/**
 * Represents the data type of a schema field.
 */
sealed class FieldType {
    data object StringType : FieldType()
    data object NumberType : FieldType()
    data object BooleanType : FieldType()
    data object DateType : FieldType()
    data class EnumType(val allowedValues: List<String>) : FieldType()
    data class ObjectType(val properties: Map<String, SchemaField>) : FieldType()
    data class ArrayType(val itemType: FieldType) : FieldType()
    data object NullableType : FieldType() // Used for optional fields
}

/**
 * Metadata for a schema field, used for prompt generation and validation.
 */
data class FieldMetadata(
    val description: String,
    val examples: List<String> = emptyList(),
    val hints: List<String> = emptyList(),
    val extractionHints: String? = null,
    val validationRules: List<String> = emptyList()
)

/**
 * Represents a single field in the schema.
 */
data class SchemaField(
    val name: String,
    val type: FieldType,
    val required: Boolean = true,
    val metadata: FieldMetadata
)

/**
 * Complete schema definition for a structured data extraction task.
 */
data class Schema(
    val name: String,
    val version: String,
    val fields: List<SchemaField>,
    val globalRules: List<String> = emptyList()
) {
    /**
     * Get a field by name.
     */
    fun getField(name: String): SchemaField? = fields.find { it.name == name }

    /**
     * Check if a field is required.
     */
    fun isFieldRequired(name: String): Boolean = getField(name)?.required ?: false

    /**
     * Get all required fields.
     */
    fun getRequiredFields(): List<SchemaField> = fields.filter { it.required }

    /**
     * Get all optional fields.
     */
    fun getOptionalFields(): List<SchemaField> = fields.filter { !it.required }
}


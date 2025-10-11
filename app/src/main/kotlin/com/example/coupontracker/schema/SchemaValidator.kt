package com.example.coupontracker.schema

import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Schema-driven JSON validator.
 * Validates JSON against schema definitions with detailed error reporting.
 */
object SchemaValidator {
    
    private const val TAG = "SchemaValidator"
    
    /**
     * Validate JSON against schema
     */
    fun validate(jsonString: String, schema: Schema): ValidationResult {
        return try {
            val json = JSONObject(jsonString.trim())
            validateObject(json, schema)
        } catch (e: JSONException) {
            ValidationResult.Invalid(listOf("Invalid JSON syntax: ${e.message}"))
        }
    }
    
    /**
     * Validate JSON object against schema
     */
    fun validateObject(json: JSONObject, schema: Schema): ValidationResult {
        val issues = mutableListOf<String>()
        
        // Check for unknown keys
        val jsonKeys = json.keys().asSequence().toSet()
        val schemaKeys = schema.fields.map { it.name }.toSet()
        val unknownKeys = jsonKeys - schemaKeys
        
        if (unknownKeys.isNotEmpty()) {
            issues.add("Unknown keys: ${unknownKeys.joinToString(", ")}")
        }
        
        // Validate each field
        schema.fields.forEach { field ->
            val fieldIssues = validateField(json, field)
            issues.addAll(fieldIssues)
        }
        
        return if (issues.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(issues)
        }
    }
    
    /**
     * Validate a specific field
     */
    private fun validateField(json: JSONObject, field: SchemaField): List<String> {
        val issues = mutableListOf<String>()
        val fieldName = field.name
        
        // Check if field exists
        if (!json.has(fieldName)) {
            if (field.required) {
                issues.add("Missing required field: $fieldName")
            }
            return issues
        }
        
        // Check if field is null
        if (json.isNull(fieldName)) {
            if (field.required) {
                issues.add("Required field cannot be null: $fieldName")
            }
            return issues
        }
        
        // Validate field type
        val typeIssues = validateType(json, fieldName, field.type)
        issues.addAll(typeIssues.map { "$fieldName: $it" })
        
        return issues
    }
    
    /**
     * Validate field type
     */
    private fun validateType(json: JSONObject, fieldName: String, type: FieldType): List<String> {
        return when (type) {
            is FieldType.StringType -> validateStringType(json, fieldName)
            is FieldType.NumberType -> validateNumberType(json, fieldName)
            is FieldType.BooleanType -> validateBooleanType(json, fieldName)
            is FieldType.DateType -> validateStringType(json, fieldName) // Dates as strings
            is FieldType.EnumType -> validateEnumType(json, fieldName, type.allowedValues)
            is FieldType.ObjectType -> validateObjectType(json, fieldName, type.properties)
            is FieldType.ArrayType -> validateArrayType(json, fieldName, type.itemType)
            is FieldType.NullableType -> emptyList() // Any type allowed
        }
    }
    
    /**
     * Validate string type
     */
    private fun validateStringType(json: JSONObject, fieldName: String): List<String> {
        return try {
            json.getString(fieldName)
            emptyList()
        } catch (e: JSONException) {
            listOf("Expected string, got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Validate number type
     */
    private fun validateNumberType(json: JSONObject, fieldName: String): List<String> {
        return try {
            json.getDouble(fieldName)
            emptyList()
        } catch (e: JSONException) {
            listOf("Expected number, got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Validate boolean type
     */
    private fun validateBooleanType(json: JSONObject, fieldName: String): List<String> {
        return try {
            json.getBoolean(fieldName)
            emptyList()
        } catch (e: JSONException) {
            listOf("Expected boolean, got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Validate enum type
     */
    private fun validateEnumType(json: JSONObject, fieldName: String, allowedValues: List<String>): List<String> {
        return try {
            val value = json.getString(fieldName)
            if (value !in allowedValues) {
                listOf("Value '$value' not in allowed values: ${allowedValues.joinToString(", ")}")
            } else {
                emptyList()
            }
        } catch (e: JSONException) {
            listOf("Expected string (enum), got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Validate object type (nested structure)
     */
    private fun validateObjectType(
        json: JSONObject,
        fieldName: String,
        properties: Map<String, SchemaField>
    ): List<String> {
        return try {
            val nestedObject = json.getJSONObject(fieldName)
            val issues = mutableListOf<String>()
            
            // Check for unknown keys in nested object
            val nestedKeys = nestedObject.keys().asSequence().toSet()
            val expectedKeys = properties.keys
            val unknownKeys = nestedKeys - expectedKeys
            
            if (unknownKeys.isNotEmpty()) {
                issues.add("Unknown nested keys: ${unknownKeys.joinToString(", ")}")
            }
            
            // Validate each nested property
            properties.forEach { (propName, propField) ->
                if (!nestedObject.has(propName)) {
                    if (propField.required) {
                        issues.add("Missing required nested field: $propName")
                    }
                } else if (!nestedObject.isNull(propName)) {
                    val propIssues = validateType(nestedObject, propName, propField.type)
                    issues.addAll(propIssues.map { "$propName: $it" })
                } else if (propField.required) {
                    issues.add("Required nested field cannot be null: $propName")
                }
            }
            
            issues
        } catch (e: JSONException) {
            listOf("Expected object, got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Validate array type
     */
    private fun validateArrayType(json: JSONObject, fieldName: String, itemType: FieldType): List<String> {
        return try {
            val array = json.getJSONArray(fieldName)
            val issues = mutableListOf<String>()
            
            // Validate each item
            for (i in 0 until array.length()) {
                val item = array.get(i)
                val itemJson = JSONObject().apply {
                    put("item", item)
                }
                val itemIssues = validateType(itemJson, "item", itemType)
                issues.addAll(itemIssues.map { "[$i]: $it" })
            }
            
            issues
        } catch (e: JSONException) {
            listOf("Expected array, got ${getActualType(json, fieldName)}")
        }
    }
    
    /**
     * Get actual type of a field for error messages
     */
    private fun getActualType(json: JSONObject, fieldName: String): String {
        return try {
            val value = json.get(fieldName)
            when (value) {
                is String -> "string"
                is Number -> "number"
                is Boolean -> "boolean"
                is JSONObject -> "object"
                is JSONArray -> "array"
                JSONObject.NULL -> "null"
                else -> "unknown"
            }
        } catch (e: Exception) {
            "undefined"
        }
    }
}

/**
 * Validation result
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val issues: List<String>) : ValidationResult()
    
    fun isValid(): Boolean = this is Valid
    fun getIssuesList(): List<String> = if (this is Invalid) issues else emptyList()
}


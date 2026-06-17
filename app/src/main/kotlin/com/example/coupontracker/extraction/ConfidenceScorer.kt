package com.example.coupontracker.extraction

import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.universal.ExtractionContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Field-level confidence scoring for extracted coupon data
 * Validates field values and assigns confidence scores based on:
 * - Format validation (regex patterns, length, character types)
 * - Generic text-quality checks
 * - Semantic checks (future dates, reasonable amounts)
 */

/**
 * Confidence result for a single field
 */
data class FieldConfidence(
    val field: FieldType,
    val value: String,
    val confidence: Float, // 0.0-1.0
    val source: ExtractionSource,
    val validationNotes: List<String> = emptyList()
)

/**
 * Source of field extraction
 */
enum class ExtractionSource {
    LLM,           // Extracted by LLM (Qwen)
    OCR_PATTERN,   // Extracted by structured pattern matching
    HEURISTIC,     // Extracted by heuristic analysis
    SEMANTIC,      // Extracted by semantic analysis
    LEARNED,       // Extracted from learned patterns
    DEFAULT        // Default/fallback value
}

/**
 * Validation result for a complete coupon
 */
data class ValidationResult(
    val isValid: Boolean,
    val overallConfidence: Float,
    val missingRequiredFields: List<FieldType>,
    val lowConfidenceFields: List<FieldType>,
    val warnings: List<String>,
    val suggestedAction: ValidationAction
)

enum class ValidationAction {
    ACCEPT,          // Good enough to save
    REVIEW,          // Save but flag for user review
    RETRY_OCR,       // Retry with different OCR engine
    RETRY_STRATEGY,  // Retry with different extraction strategy
    REJECT           // Too low quality, don't save
}

/**
 * Confidence scorer for extracted coupon fields
 */
class ConfidenceScorer {
    
    companion object {
        private const val TAG = "ConfidenceScorer"
        
        // Confidence thresholds
        const val HIGH_CONFIDENCE = 0.85f
        const val MEDIUM_CONFIDENCE = 0.60f
        const val LOW_CONFIDENCE = 0.40f
        
        // Required fields for a valid coupon
        private val REQUIRED_FIELDS = setOf(
            FieldType.STORE_NAME,
            FieldType.DESCRIPTION
        )
        
        // Valid currency symbols
        private val VALID_CURRENCIES = setOf("₹", "Rs", "Rs.", "INR", "$", "USD", "€", "EUR", "£", "GBP")
    }
    
    /**
     * Score a single field based on its type and value
     */
    fun scoreField(
        field: FieldType,
        value: String,
        extractionContext: ExtractionContext,
        source: ExtractionSource = ExtractionSource.HEURISTIC
    ): FieldConfidence {
        if (value.isBlank()) {
            return FieldConfidence(field, value, 0.0f, source, listOf("Empty value"))
        }
        
        return when (field) {
            FieldType.STORE_NAME -> scoreStoreName(value, source)
            FieldType.COUPON_CODE -> scoreCouponCode(value, source)
            FieldType.AMOUNT -> scoreCashbackAmount(value, source)
            FieldType.EXPIRY_DATE -> scoreExpiryDate(value, source)
            FieldType.DESCRIPTION -> scoreDescription(value, source)
            else -> FieldConfidence(field, value, 0.5f, source) // Default medium confidence
        }
    }
    
    /**
     * Score store name field
     */
    private fun scoreStoreName(value: String, source: ExtractionSource): FieldConfidence {
        val notes = mutableListOf<String>()
        var confidence = 0.5f // Base confidence
        
        // Length check
        if (value.length < 2 || value.length > 50) {
            notes.add("Unusual length: ${value.length} chars")
            confidence -= 0.2f
        } else {
            confidence += 0.1f
        }
        
        // Title case check (brands are usually title case)
        val isTitleCase = value.split(" ").all { word ->
            word.isEmpty() || word[0].isUpperCase()
        }
        if (isTitleCase && value.any { it.isLowerCase() }) {
            notes.add("Proper title case")
            confidence += 0.2f
        }
        
        // Check for garbage patterns
        if (value.matches(Regex(""".*\d{4,}.*"""))) {
            notes.add("Contains long number sequence")
            confidence -= 0.3f
        }
        
        if (value.matches(Regex("""^[A-Z]{1,2}$"""))) {
            notes.add("Too short (single/double letter)")
            confidence -= 0.4f
        }
        
        // LLM extraction gets bonus
        if (source == ExtractionSource.LLM) {
            confidence += 0.1f
        }
        
        return FieldConfidence(
            field = FieldType.STORE_NAME,
            value = value,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            source = source,
            validationNotes = notes
        )
    }
    
    /**
     * Score coupon code field
     */
    private fun scoreCouponCode(value: String, source: ExtractionSource): FieldConfidence {
        val notes = mutableListOf<String>()
        var confidence = 0.5f
        
        // Length check (typical coupon codes: 4-20 chars)
        when {
            value.length < 4 -> {
                notes.add("Too short for coupon code")
                confidence -= 0.3f
            }
            value.length > 25 -> {
                notes.add("Too long for coupon code")
                confidence -= 0.2f
            }
            value.length in 6..15 -> {
                notes.add("Typical coupon code length")
                confidence += 0.2f
            }
        }
        
        // Alphanumeric check (coupon codes are usually alphanumeric)
        if (value.all { it.isLetterOrDigit() }) {
            notes.add("Valid alphanumeric format")
            confidence += 0.3f
        } else if (value.count { !it.isLetterOrDigit() } > 2) {
            notes.add("Too many special characters")
            confidence -= 0.2f
        }
        
        // Upper case check (many codes are all caps)
        if (value.all { !it.isLetter() || it.isUpperCase() }) {
            notes.add("All uppercase (typical format)")
            confidence += 0.1f
        }
        
        // Mix of letters and numbers (stronger signal)
        val hasLetters = value.any { it.isLetter() }
        val hasDigits = value.any { it.isDigit() }
        if (hasLetters && hasDigits) {
            notes.add("Has both letters and numbers")
            confidence += 0.2f
        }
        
        return FieldConfidence(
            field = FieldType.COUPON_CODE,
            value = value,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            source = source,
            validationNotes = notes
        )
    }
    
    /**
     * Score cashback amount field
     */
    private fun scoreCashbackAmount(value: String, source: ExtractionSource): FieldConfidence {
        val notes = mutableListOf<String>()
        var confidence = 0.5f
        
        // Check for currency symbol
        if (VALID_CURRENCIES.any { value.contains(it) }) {
            notes.add("Has valid currency symbol")
            confidence += 0.2f
        }
        
        // Check for percentage
        if (value.contains("%") || value.contains("percent", ignoreCase = true)) {
            notes.add("Percentage format")
            confidence += 0.2f
        }
        
        // Extract numeric value
        val numeric = Regex("""(\d+(?:\.\d+)?)""").find(value)?.value?.toFloatOrNull()
        if (numeric != null) {
            notes.add("Valid numeric value: $numeric")
            confidence += 0.2f
            
            // Sanity check on amount
            when {
                numeric < 0 -> {
                    notes.add("Negative amount (invalid)")
                    confidence -= 0.5f
                }
                numeric > 10000 -> {
                    notes.add("Very high amount (check if correct)")
                    confidence -= 0.1f
                }
                numeric in 10.0..5000.0 -> {
                    notes.add("Reasonable cashback range")
                    confidence += 0.1f
                }
            }
        } else {
            notes.add("No numeric value found")
            confidence -= 0.3f
        }
        
        return FieldConfidence(
            field = FieldType.AMOUNT,
            value = value,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            source = source,
            validationNotes = notes
        )
    }
    
    /**
     * Score expiry date field
     */
    private fun scoreExpiryDate(
        value: String,
        source: ExtractionSource
    ): FieldConfidence {
        val notes = mutableListOf<String>()
        var confidence = 0.5f
        
        if (value.equals("unknown", ignoreCase = true) || value.equals("null", ignoreCase = true)) {
            notes.add("No expiry date provided")
            return FieldConfidence(FieldType.EXPIRY_DATE, value, 0.3f, source, notes)
        }
        
        // Try to parse date
        val dateFormats = listOf(
            SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        )
        
        var parsedDate: Date? = null
        for (format in dateFormats) {
            try {
                parsedDate = format.parse(value)
                if (parsedDate != null) {
                    notes.add("Parsed as date: ${format.toPattern()}")
                    confidence += 0.3f
                    break
                }
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        // Check if it's a future date
        if (parsedDate != null) {
            val today = Date()
            if (parsedDate.after(today)) {
                notes.add("Valid future date")
                confidence += 0.2f
            } else {
                notes.add("Date is in the past (expired coupon?)")
                confidence -= 0.3f
            }
        } else {
            // Check for relative date format
            if (Regex("""\b\d+\s+days?""", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
                notes.add("Relative date format (X days)")
                confidence += 0.2f
            } else {
                notes.add("Could not parse as date")
                confidence -= 0.2f
            }
        }
        
        return FieldConfidence(
            field = FieldType.EXPIRY_DATE,
            value = value,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            source = source,
            validationNotes = notes
        )
    }
    
    /**
     * Score description field
     */
    private fun scoreDescription(value: String, source: ExtractionSource): FieldConfidence {
        val notes = mutableListOf<String>()
        var confidence = 0.6f // Description is usually present, give base credit
        
        // Length check
        when {
            value.length < 5 -> {
                notes.add("Very short description")
                confidence -= 0.2f
            }
            value.length > 200 -> {
                notes.add("Very long description")
                confidence -= 0.1f
            }
            value.length in 10..100 -> {
                notes.add("Good description length")
                confidence += 0.2f
            }
        }
        
        // Check for common coupon keywords
        val keywords = listOf("off", "discount", "cashback", "save", "flat", "upto", "get", "offer")
        if (keywords.any { value.contains(it, ignoreCase = true) }) {
            notes.add("Contains coupon keywords")
            confidence += 0.2f
        }
        
        return FieldConfidence(
            field = FieldType.DESCRIPTION,
            value = value,
            confidence = confidence.coerceIn(0.0f, 1.0f),
            source = source,
            validationNotes = notes
        )
    }
    
    /**
     * Validate required fields are present and have acceptable confidence
     */
    fun validateRequiredFields(fields: Map<FieldType, FieldConfidence>): ValidationResult {
        val missingRequired = REQUIRED_FIELDS.filter { it !in fields.keys }
        val lowConfidence = fields.filter { it.value.confidence < LOW_CONFIDENCE }.keys.toList()
        val warnings = mutableListOf<String>()
        
        // Calculate overall confidence (weighted average)
        val overallConfidence = if (fields.isEmpty()) {
            0.0f
        } else {
            val weights = mapOf(
                FieldType.STORE_NAME to 3.0f,
                FieldType.COUPON_CODE to 2.0f,
                FieldType.AMOUNT to 2.0f,
                FieldType.EXPIRY_DATE to 1.5f,
                FieldType.DESCRIPTION to 1.0f
            )
            
            val weightedSum = fields.entries.sumOf { (field, confidence) ->
                (weights[field] ?: 1.0f).toDouble() * confidence.confidence
            }
            val totalWeight = fields.keys.sumOf { (weights[it] ?: 1.0f).toDouble() }
            
            (weightedSum / totalWeight).toFloat()
        }
        
        // Determine validation action
        val suggestedAction = when {
            missingRequired.isNotEmpty() -> {
                warnings.add("Missing required fields: ${missingRequired.joinToString()}")
                ValidationAction.RETRY_STRATEGY
            }
            overallConfidence >= HIGH_CONFIDENCE -> ValidationAction.ACCEPT
            overallConfidence >= MEDIUM_CONFIDENCE -> ValidationAction.REVIEW
            overallConfidence >= LOW_CONFIDENCE -> ValidationAction.RETRY_OCR
            else -> ValidationAction.REJECT
        }
        
        // Add warnings for low confidence fields
        if (lowConfidence.isNotEmpty()) {
            warnings.add("Low confidence fields: ${lowConfidence.joinToString()}")
        }
        
        Log.d(TAG, "Validation result: confidence=$overallConfidence, action=$suggestedAction")
        
        return ValidationResult(
            isValid = missingRequired.isEmpty() && overallConfidence >= LOW_CONFIDENCE,
            overallConfidence = overallConfidence,
            missingRequiredFields = missingRequired,
            lowConfidenceFields = lowConfidence,
            warnings = warnings,
            suggestedAction = suggestedAction
        )
    }
}

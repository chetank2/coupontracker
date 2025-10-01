package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType

/**
 * Provides conservative default values for missing fields.
 * This is Pass 5 of the progressive extraction pipeline.
 * Always succeeds and ensures we never return "Error processing coupon".
 */
class DefaultFieldProvider {
    
    /**
     * Provide default values for missing fields using OCR text as fallback
     */
    fun provideDefaults(
        context: ExtractionContext,
        missingFields: Set<FieldType>
    ): Map<FieldType, FieldCandidate> {
        
        val defaults = mutableMapOf<FieldType, FieldCandidate>()
        
        // Store: Use first non-empty line or "Coupon"
        if (FieldType.STORE_NAME in missingFields) {
            val firstLine = context.ocrText.lines()
                .firstOrNull { it.trim().isNotEmpty() }
                ?.trim()
                ?.take(30) // Truncate long lines
                ?: "Coupon"
            
            defaults[FieldType.STORE_NAME] = FieldCandidate(
                value = firstLine,
                confidence = 0.1f,
                source = "default_first_line",
                context = "No store found, using first line"
            )
        }
        
        // Description: Always use full OCR text (truncated to reasonable length)
        // This ensures we NEVER show "Error processing coupon"
        val description = context.ocrText.take(200).trim()
        if (description.isNotBlank()) {
            defaults[FieldType.DESCRIPTION] = FieldCandidate(
                value = description,
                confidence = 0.5f,
                source = "default_ocr_text",
                context = "Using OCR text as description"
            )
        }
        
        // Amount: 0.0 (but marked as uncertain)
        if (FieldType.AMOUNT in missingFields) {
            defaults[FieldType.AMOUNT] = FieldCandidate(
                value = "0.0",
                confidence = 0.0f,
                source = "default_zero",
                context = "No amount found"
            )
        }
        
        // Code: NO_CODE_NEEDED (assume cashback/auto-applied)
        if (FieldType.COUPON_CODE in missingFields) {
            defaults[FieldType.COUPON_CODE] = FieldCandidate(
                value = "NO_CODE_NEEDED",
                confidence = 0.3f,
                source = "default_no_code",
                context = "No code pattern found, assuming no code needed"
            )
        }
        
        // Expiry: Leave null if not found (no good default for dates)
        // This is intentionally omitted as a wrong date is worse than no date
        
        return defaults
    }
}


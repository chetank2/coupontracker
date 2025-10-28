package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.OcrTextCleaner
import java.util.Locale

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
        
        if (FieldType.STORE_NAME in missingFields) {
            val firstLine = OcrTextCleaner.getFirstMeaningfulLine(context.ocrText, 30)
            if (firstLine != null && !isGenericLine(firstLine)) {
                defaults[FieldType.STORE_NAME] = FieldCandidate(
                    value = firstLine,
                    confidence = 0.1f,
                    source = "default_first_line",
                    context = "No store found, using first meaningful line"
                )
            }
        }
        
        // Description: Use cleaned OCR text (no UI chrome, truncated to reasonable length)
        // This ensures we NEVER show "Error processing coupon"
        val cleanedOcr = OcrTextCleaner.cleanOcrText(context.ocrText)
        val description = cleanedOcr.take(200).trim()
        if (description.isNotBlank() && !isGenericDescription(description)) {
            defaults[FieldType.DESCRIPTION] = FieldCandidate(
                value = description,
                confidence = 0.5f,
                source = "default_ocr_text",
                context = "Using cleaned OCR text as description"
            )
        } else {
            // Fallback to raw OCR if cleaning removed everything
            val rawDescription = context.ocrText.take(200).trim()
            if (rawDescription.isNotBlank() && !isGenericDescription(rawDescription)) {
                defaults[FieldType.DESCRIPTION] = FieldCandidate(
                    value = rawDescription,
                    confidence = 0.3f,
                    source = "default_raw_ocr",
                    context = "Using raw OCR text as description (cleaning too aggressive)"
                )
            }
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

    private fun isGenericLine(line: String): Boolean {
        val normalized = line.trim().uppercase(Locale.ROOT)
        if (normalized.isEmpty()) return true
        val genericTokens = setOf(
            "COUPON", "NOW", "TODAY", "SHOP NOW", "BUY NOW", "REDEEM", "CLAIM",
            "ORDER", "APPLY", "USE", "SAVE", "DISCOUNT", "OFFER", "EXCLUSIVE",
            "DEAL", "SPECIAL", "FLASH", "LIMITED", "SALE", "CLICK", "TAP"
        )
        if (normalized in genericTokens) return true
        if (normalized.length < 3) return true
        return normalized.any { it.isDigit() }
    }

    private fun isGenericDescription(description: String): Boolean {
        val normalized = description.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return true
        val disqualifiers = listOf(
            "tap to view", "swipe", "screenshot", "copy code", "details", "scan to pay",
            "shop now", "click here", "open app", "android", "ios", "claim now", "apply now",
            "download", "loyalty", "profile", "limited time", "verify"
        )
        return disqualifiers.any { normalized.contains(it) }
    }
}


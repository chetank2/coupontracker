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
        
        if (FieldType.DESCRIPTION in missingFields) {
            val cleanedDescription = OcrTextCleaner.cleanOcrText(context.ocrText).take(200).trim()
            MissingFieldPolicy.lowConfidenceDescriptionFromOcr(context.ocrText)?.let { description ->
                val fromCleanedOcr = description == cleanedDescription
                defaults[FieldType.DESCRIPTION] = FieldCandidate(
                    value = description,
                    confidence = if (fromCleanedOcr) 0.35f else 0.2f,
                    source = if (fromCleanedOcr) "default_ocr_text" else "default_raw_ocr",
                    context = if (fromCleanedOcr) {
                        "Using cleaned OCR text as low-confidence description"
                    } else {
                        "Using raw OCR text as low-confidence description"
                    }
                )
            }
        }
        
        // Amount: leave missing. A synthetic 0.0 amount looks like extracted
        // data and can leak into saved descriptions.
        
        // Code: only mark no-code when OCR explicitly says so.
        if (FieldType.COUPON_CODE in missingFields) {
            if (MissingFieldPolicy.hasExplicitNoCodeEvidence(context.ocrText)) {
                defaults[FieldType.COUPON_CODE] = FieldCandidate(
                    value = MissingFieldPolicy.explicitNoCodeValue(),
                    confidence = 0.65f,
                    source = MissingFieldPolicy.EXPLICIT_NO_CODE_SOURCE,
                    context = "OCR explicitly says no coupon code is needed"
                )
            }
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

}

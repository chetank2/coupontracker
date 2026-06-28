package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.GenericFieldHeuristics
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
            // Description: use OCR only when it is concrete offer text.
            val cleanedOcr = OcrTextCleaner.cleanOcrText(context.ocrText)
            val description = cleanedOcr.take(200).trim()
            if (description.isNotBlank() && !isGenericDescription(description)) {
                defaults[FieldType.DESCRIPTION] = FieldCandidate(
                    value = description,
                    confidence = 0.35f,
                    source = "default_ocr_text",
                    context = "Using cleaned OCR text as low-confidence description"
                )
            } else {
                // Fallback to raw OCR if cleaning removed a meaningful offer line.
                val rawDescription = context.ocrText.take(200).trim()
                if (rawDescription.isNotBlank() && !isGenericDescription(rawDescription)) {
                    defaults[FieldType.DESCRIPTION] = FieldCandidate(
                        value = rawDescription,
                        confidence = 0.2f,
                        source = "default_raw_ocr",
                        context = "Using raw OCR text as low-confidence description"
                    )
                }
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
        
        // Code: only mark no-code when OCR explicitly says so.
        if (FieldType.COUPON_CODE in missingFields) {
            if (hasNoCodeNeededEvidence(context.ocrText)) {
                defaults[FieldType.COUPON_CODE] = FieldCandidate(
                    value = "NO_CODE_NEEDED",
                    confidence = 0.65f,
                    source = "explicit_no_code_evidence",
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

    private fun isGenericDescription(description: String): Boolean {
        val normalized = description.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return true
        if (!GenericFieldHeuristics.isMeaningfulDescription(description)) return true
        val disqualifiers = listOf(
            "tap to view", "swipe", "screenshot", "copy code", "details", "scan to pay",
            "shop now", "click here", "open app", "android", "ios", "claim now", "apply now",
            "download", "loyalty", "profile", "limited time", "verify", "coupon offer"
        )
        return disqualifiers.any { normalized.contains(it) }
    }

    private fun hasNoCodeNeededEvidence(text: String): Boolean {
        val normalized = text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return listOf(
            Regex("\\bno\\s+code\\s+(?:needed|required)\\b"),
            Regex("\\bno\\s+coupon\\s+code\\s+(?:needed|required)\\b"),
            Regex("\\bcode\\s+(?:not\\s+)?required\\b"),
            Regex("\\bwithout\\s+(?:a\\s+)?(?:coupon\\s+)?code\\b"),
            Regex("\\bauto(?:matically)?\\s+applied\\b")
        ).any { it.containsMatchIn(normalized) }
    }
}

package com.example.coupontracker.extraction

import com.example.coupontracker.data.model.FieldType

/**
 * Heuristic field extraction - Pass 3 of progressive pipeline.
 * Uses simple heuristics when patterns and semantics fail.
 * Lower confidence but better than nothing.
 */
class HeuristicFieldExtractor {
    
    companion object {
        private val COMMON_WORDS = setOf(
            "the", "and", "for", "you", "get", "with", "from", "expires", "code",
            "coupon", "offer", "valid", "upto", "flat", "off", "cashback", "this",
            "that", "your", "use", "apply", "save", "discount", "via", "pay"
        )
    }
    
    /**
     * Extract fields using simple heuristics
     */
    fun extractFieldsHeuristic(
        context: ExtractionContext,
        missingFields: Set<FieldType>
    ): Map<FieldType, List<FieldCandidate>> {
        
        val results = mutableMapOf<FieldType, MutableList<FieldCandidate>>()
        
        // Store: ANY capitalized word (not in common words)
        if (FieldType.STORE_NAME in missingFields) {
            val capitalPattern = Regex("""\b([A-Z][A-Za-z0-9]{2,})\b""")
            capitalPattern.findAll(context.ocrText).forEach { match ->
                val word = match.value
                if (word.lowercase() !in COMMON_WORDS) {
                    results.getOrPut(FieldType.STORE_NAME) { mutableListOf() }.add(
                        FieldCandidate(
                            value = word,
                            confidence = 0.3f,
                            source = "heuristic_capital",
                            context = "First capitalized word found"
                        )
                    )
                    return@forEach // Only take first one
                }
            }
        }
        
        // Amount: ANY number (very low confidence)
        if (FieldType.AMOUNT in missingFields) {
            val numberPattern = Regex("""[0-9]+""")
            numberPattern.find(context.ocrText)?.let { match ->
                results.getOrPut(FieldType.AMOUNT) { mutableListOf() }.add(
                    FieldCandidate(
                        value = match.value,
                        confidence = 0.2f,
                        source = "heuristic_number",
                        context = "First number found in text"
                    )
                )
            }
        }
        
        // Description: First sentence
        if (FieldType.DESCRIPTION in missingFields) {
            val sentences = context.ocrText.split(Regex("[.!?\n]+"))
            val firstSentence = sentences.firstOrNull { it.trim().isNotEmpty() }?.trim()
            if (firstSentence != null && firstSentence.length > 10) {
                results.getOrPut(FieldType.DESCRIPTION) { mutableListOf() }.add(
                    FieldCandidate(
                        value = firstSentence,
                        confidence = 0.4f,
                        source = "heuristic_first_sentence",
                        context = "Using first sentence as description"
                    )
                )
            }
        }
        
        return results
    }
}


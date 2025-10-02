package com.example.coupontracker.extraction

import android.util.Log
import com.example.coupontracker.data.model.FieldType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Semantic field extraction - Pass 2 of progressive pipeline.
 * Understands sentence semantics and context, not just patterns.
 * Extracts meaning from natural language.
 */
class SemanticFieldExtractor {
    
    companion object {
        private const val TAG = "SemanticFieldExtractor"
    }
    
    /**
     * Extract fields using semantic sentence analysis
     */
    suspend fun extractFieldsSemantic(
        context: ExtractionContext,
        missingFields: Set<FieldType>
    ): Map<FieldType, List<FieldCandidate>> = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<FieldType, MutableList<FieldCandidate>>()
        
        // Split into sentences for analysis
        val sentences = context.ocrText.split(Regex("[.!?\n]+")).map { it.trim() }
        
        for (sentence in sentences) {
            if (sentence.length < 5) continue // Skip very short segments
            
            // Extract store name from sentence
            if (FieldType.STORE_NAME in missingFields) {
                extractStoreFromSentence(sentence)?.let {
                    results.getOrPut(FieldType.STORE_NAME) { mutableListOf() }.add(it)
                }
            }
            
            // Extract amount from sentence
            if (FieldType.AMOUNT in missingFields) {
                extractAmountFromSentence(sentence)?.let {
                    results.getOrPut(FieldType.AMOUNT) { mutableListOf() }.add(it)
                }
            }
            
            // Extract description
            if (FieldType.DESCRIPTION in missingFields) {
                extractDescriptionFromSentence(sentence)?.let {
                    results.getOrPut(FieldType.DESCRIPTION) { mutableListOf() }.add(it)
                }
            }
        }
        
        // Record attempt
        context.attempts.add(ExtractionAttempt(
            passName = "Pass 2: Semantic",
            strategy = "sentence_analysis",
            timestamp = startTime,
            durationMs = System.currentTimeMillis() - startTime,
            fieldsExtracted = results.mapValues { it.value.firstOrNull() ?: FieldCandidate("", 0f, "semantic", null) },
            confidence = results.values.flatten().mapNotNull { it.confidence }.average().toFloat().let { if (it.isNaN()) 0f else it },
            reason = "Semantic sentence analysis"
        ))
        
        Log.d(TAG, "Semantic extraction found ${results.size} field types with ${results.values.sumOf { it.size }} candidates")
        
        results
    }
    
    /**
     * Extract store name from sentence semantics
     */
    private fun extractStoreFromSentence(sentence: String): FieldCandidate? {
        
        // Pattern 1: "you get X from Y" → Y is store
        // Pattern 2: "you won X at Y" → Y is store
        val fromPattern = Regex("""you\s+(?:get|won)\s+.+?\s+(?:from|at|via)\s+([A-Z][A-Za-z0-9&.'\-\s]{1,25})""", RegexOption.IGNORE_CASE)
        fromPattern.find(sentence)?.let { match ->
            val storeName = match.groupValues[1].trim()
            return FieldCandidate(
                value = storeName,
                confidence = 0.75f,
                source = "semantic_from",
                context = sentence
            )
        }
        
        // Pattern 3: "STORE NAME cashback" → STORE NAME is likely the store
        val cashbackPattern = Regex("""([A-Z][A-Za-z0-9&.'\-\s]{2,20})\s+(?:cashback|rewards?|offer|voucher)""", RegexOption.IGNORE_CASE)
        cashbackPattern.find(sentence)?.let { match ->
            val storeName = match.groupValues[1].trim()
            return FieldCandidate(
                value = storeName,
                confidence = 0.65f,
                source = "semantic_cashback",
                context = sentence
            )
        }
        
        // Pattern 4: "via PAYMENT_METHOD" → often preceded by store
        val viaPattern = Regex("""([A-Z][A-Za-z0-9&.'\-\s]{2,20})\s+(?:via|through|using)\s+(?:CRED|UPI|Card|Pay)""", RegexOption.IGNORE_CASE)
        viaPattern.find(sentence)?.let { match ->
            val storeName = match.groupValues[1].trim()
            return FieldCandidate(
                value = storeName,
                confidence = 0.55f,
                source = "semantic_via",
                context = sentence
            )
        }
        
        return null
    }
    
    /**
     * Extract amount from sentence with context awareness
     */
    private fun extractAmountFromSentence(sentence: String): FieldCandidate? {
        
        // Find all currency amounts in sentence
        val amountPattern = Regex("""(?:₹|Rs\.?\s*)\s*([0-9,]+(?:\.[0-9]{1,2})?)""")
        val amounts = amountPattern.findAll(sentence).toList()
        
        if (amounts.isEmpty()) return null
        
        // If "cashback" appears, prefer amount near it
        val cashbackIndex = sentence.indexOf("cashback", ignoreCase = true)
        if (cashbackIndex >= 0) {
            // Find amount closest to "cashback"
            val closestAmount = amounts.minByOrNull { match ->
                kotlin.math.abs(match.range.first - cashbackIndex)
            }
            
            closestAmount?.let { match ->
                val amountValue = match.groupValues[1].replace(",", "")
                return FieldCandidate(
                    value = "₹$amountValue cashback",
                    confidence = 0.8f,
                    source = "semantic_cashback_amount",
                    context = sentence
                )
            }
        }
        
        // If "discount" or "off" appears, prefer percentage or amount near it
        val discountIndex = listOf("discount", "off", "save").map { sentence.indexOf(it, ignoreCase = true) }.maxOrNull() ?: -1
        if (discountIndex >= 0) {
            // Check for percentage near discount
            val percentPattern = Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*%""")
            percentPattern.find(sentence)?.let { match ->
                return FieldCandidate(
                    value = "${match.groupValues[1]}%",
                    confidence = 0.85f,
                    source = "semantic_discount_percent",
                    context = sentence
                )
            }
            
            // Otherwise use closest rupee amount
            val closestAmount = amounts.minByOrNull { match ->
                kotlin.math.abs(match.range.first - discountIndex)
            }
            
            closestAmount?.let { match ->
                val amountValue = match.groupValues[1].replace(",", "")
                return FieldCandidate(
                    value = "₹$amountValue",
                    confidence = 0.7f,
                    source = "semantic_discount_amount",
                    context = sentence
                )
            }
        }
        
        // Default: Return last amount (often most significant)
        amounts.lastOrNull()?.let { match ->
            val amountValue = match.groupValues[1].replace(",", "")
            return FieldCandidate(
                value = "₹$amountValue",
                confidence = 0.5f,
                source = "semantic_last_amount",
                context = sentence
            )
        }
        
        return null
    }
    
    /**
     * Extract meaningful description from sentence
     */
    private fun extractDescriptionFromSentence(sentence: String): FieldCandidate? {
        // If sentence contains offer-related words and has substance, use it
        val offerKeywords = listOf("get", "offer", "save", "discount", "cashback", "off", "free", "win", "won")
        val conditionKeywords = listOf("above", "minimum", "min", "orders over", "on orders", "spend")
        
        val hasOfferKeyword = offerKeywords.any { sentence.contains(it, ignoreCase = true) }
        val hasCondition = conditionKeywords.any { sentence.contains(it, ignoreCase = true) }
        
        // HIGHEST priority: Sentences with both offer AND conditions
        if (hasOfferKeyword && hasCondition && sentence.length >= 20) {
            return FieldCandidate(
                value = sentence.trim(),
                confidence = 0.85f,  // Higher confidence
                source = "semantic_offer_with_condition",
                context = "Sentence contains offer keywords and conditions"
            )
        }
        
        // HIGH priority: Sentences with offer keywords
        if (hasOfferKeyword && sentence.length >= 20) {
            return FieldCandidate(
                value = sentence.trim(),
                confidence = 0.70f,
                source = "semantic_offer_sentence",
                context = "Sentence contains offer keywords"
            )
        }
        
        // MEDIUM priority: Substantial sentences (fallback)
        if (sentence.length >= 30 && sentence.split(" ").size >= 5) {
            return FieldCandidate(
                value = sentence.trim(),
                confidence = 0.5f,
                source = "semantic_substantial_sentence",
                context = "Substantial sentence"
            )
        }
        
        return null
    }
}


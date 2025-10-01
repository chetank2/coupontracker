package com.example.coupontracker.extraction

import android.util.Log
import com.example.coupontracker.data.model.FieldType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Structured field extraction - Pass 1 of progressive pipeline.
 * Uses enhanced pattern matching with multiple strategies per field.
 * NO brand lists - truly universal extraction.
 */
class StructuredFieldExtractor {
    
    companion object {
        private const val TAG = "StructuredFieldExtractor"
        
        private val COMMON_WORDS = setOf(
            "THE", "AND", "FOR", "YOU", "GET", "WITH", "FROM", "EXPIRES", "CODE",
            "COUPON", "OFFER", "VALID", "UPTO", "FLAT", "OFF", "CASHBACK", "THIS",
            "THAT", "YOUR", "USE", "APPLY", "SAVE", "DISCOUNT", "VIA", "PAY", "ONLY"
        )
    }
    
    /**
     * Detect fields using structured patterns
     */
    suspend fun detectFieldsStructured(
        context: ExtractionContext,
        minConfidence: Float = 0.4f
    ): Map<FieldType, List<FieldCandidate>> = withContext(Dispatchers.Default) {
        
        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<FieldType, List<FieldCandidate>>()
        
        // Store Name: Multiple strategies (NO brand list!)
        results[FieldType.STORE_NAME] = detectStoreName_AllStrategies(context, minConfidence)
        
        // Amount: Handle compound expressions
        results[FieldType.AMOUNT] = detectAmount_CompoundAware(context, minConfidence)
        
        // Expiry: Handle relative dates
        results[FieldType.EXPIRY_DATE] = detectExpiry_RelativeAware(context, minConfidence)
        
        // Code: Pattern + no-code detection
        results[FieldType.COUPON_CODE] = detectCode_OrMarkNotNeeded(context, minConfidence)
        
        // Record attempt
        context.attempts.add(ExtractionAttempt(
            passName = "Pass 1: Structured",
            strategy = "pattern_matching",
            timestamp = startTime,
            durationMs = System.currentTimeMillis() - startTime,
            fieldsExtracted = results.mapValues { it.value.firstOrNull() ?: FieldCandidate("", 0f, "pattern", null) },
            confidence = results.values.flatten().mapNotNull { it.confidence }.average().toFloat().let { if (it.isNaN()) 0f else it },
            reason = "Structured pattern matching"
        ))
        
        Log.d(TAG, "Structured extraction found ${results.values.sumOf { it.size }} candidates across ${results.size} field types")
        
        results
    }
    
    /**
     * Store name detection using multiple strategies (NO BRAND LIST!)
     */
    private fun detectStoreName_AllStrategies(
        context: ExtractionContext,
        minConfidence: Float
    ): List<FieldCandidate> {
        val candidates = mutableListOf<FieldCandidate>()
        
        // Strategy 1: Explicit context patterns ("from X", "at Y", "via Z")
        val explicitPattern = Regex("""(?:from|at|via|by)\s+([A-Z][A-Za-z0-9&.'\-]{1,20})""", RegexOption.IGNORE_CASE)
        explicitPattern.findAll(context.ocrText).forEach { match ->
            val storeName = match.groupValues[1]
            if (storeName.length >= 3) {
                candidates.add(FieldCandidate(
                    value = storeName,
                    confidence = 0.8f,
                    source = "explicit_pattern",
                    context = match.value
                ))
            }
        }
        
        // Strategy 2: ALL CAPS words (likely brand names)
        val allCapsPattern = Regex("""\b([A-Z]{2,})\b""")
        allCapsPattern.findAll(context.ocrText).forEach { match ->
            val word = match.value
            if (word !in COMMON_WORDS && word.length in 3..15) {
                candidates.add(FieldCandidate(
                    value = word,
                    confidence = 0.5f,
                    source = "all_caps",
                    context = getWordContext(context.ocrText, match.range)
                ))
            }
        }
        
        // Strategy 3: Title Case words in first 20% of text
        val textLength = context.ocrText.length
        if (textLength > 0) {
            val earlyText = context.ocrText.take((textLength * 0.2).toInt().coerceAtLeast(50))
            val titleCasePattern = Regex("""\b([A-Z][a-z]{2,}(?:\s+[A-Z][a-z]{2,}){0,2})\b""")
            titleCasePattern.findAll(earlyText).forEach { match ->
                val storeName = match.value
                if (storeName.lowercase() !in COMMON_WORDS.map { it.lowercase() }) {
                    candidates.add(FieldCandidate(
                        value = storeName,
                        confidence = 0.6f,
                        source = "title_case_early",
                        context = "Found in first 20% of text"
                    ))
                }
            }
        }
        
        // Strategy 4: Repeated words (brand names often repeat)
        val wordFrequency = mutableMapOf<String, Int>()
        Regex("""\b([A-Z][A-Za-z]{2,})\b""").findAll(context.ocrText).forEach { match ->
            val word = match.value
            if (word !in COMMON_WORDS) {
                wordFrequency[word] = wordFrequency.getOrDefault(word, 0) + 1
            }
        }
        wordFrequency.filter { it.value >= 2 }.forEach { (word, count) ->
            candidates.add(FieldCandidate(
                value = word,
                confidence = (0.4f + (count * 0.1f)).coerceAtMost(0.7f),
                source = "repeated_word",
                context = "Appears $count times"
            ))
        }
        
        return candidates.filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
    }
    
    /**
     * Compound-aware amount detection (handles "₹599 + ₹50 cashback")
     */
    private fun detectAmount_CompoundAware(
        context: ExtractionContext,
        minConfidence: Float
    ): List<FieldCandidate> {
        val candidates = mutableListOf<FieldCandidate>()
        
        // Pattern 1: Compound expression "₹A + ₹B cashback"
        val compoundPattern = Regex(
            """₹\s*([0-9,]+)\s*\+\s*₹\s*([0-9,]+)\s*(cashback|off|discount)""",
            RegexOption.IGNORE_CASE
        )
        compoundPattern.findAll(context.ocrText).forEach { match ->
            val baseAmount = match.groupValues[1].replace(",", "")
            val cashbackAmount = match.groupValues[2].replace(",", "")
            val type = match.groupValues[3]
            
            // Prioritize cashback component
            candidates.add(FieldCandidate(
                value = "₹$cashbackAmount $type",
                confidence = 0.9f,
                source = "compound_cashback",
                context = match.value
            ))
            
            // Also add base amount with lower confidence
            candidates.add(FieldCandidate(
                value = "₹$baseAmount",
                confidence = 0.5f,
                source = "compound_base",
                context = match.value
            ))
        }
        
        // Pattern 2: Simple amount with context
        val simplePattern = Regex(
            """(?:₹|Rs\.?|INR)\s*([0-9,]+(?:\.[0-9]{1,2})?)\s*(off|cashback|discount|save)?""",
            RegexOption.IGNORE_CASE
        )
        simplePattern.findAll(context.ocrText).forEach { match ->
            val amount = match.groupValues[1].replace(",", "")
            val contextWord = match.groupValues[2]
            
            val confidence = if (contextWord.isNotBlank()) 0.7f else 0.4f
            candidates.add(FieldCandidate(
                value = if (contextWord.isNotBlank()) "₹$amount $contextWord" else "₹$amount",
                confidence = confidence,
                source = "simple_amount",
                context = match.value
            ))
        }
        
        // Pattern 3: Percentage
        val percentPattern = Regex(
            """([0-9]+(?:\.[0-9]{1,2})?)\s*%\s*(off|discount|cashback)?""",
            RegexOption.IGNORE_CASE
        )
        percentPattern.findAll(context.ocrText).forEach { match ->
            candidates.add(FieldCandidate(
                value = "${match.groupValues[1]}%",
                confidence = 0.8f,
                source = "percentage",
                context = match.value
            ))
        }
        
        // Pattern 4: "upto ₹X" or "up to ₹X"
        val uptoPattern = Regex(
            """(?:upto|up\s+to)\s+(?:₹|Rs\.?)\s*([0-9,]+)""",
            RegexOption.IGNORE_CASE
        )
        uptoPattern.findAll(context.ocrText).forEach { match ->
            val amount = match.groupValues[1].replace(",", "")
            candidates.add(FieldCandidate(
                value = "₹$amount",
                confidence = 0.75f,
                source = "upto_amount",
                context = match.value
            ))
        }
        
        return candidates.filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
    }
    
    /**
     * Relative date-aware expiry detection
     */
    private fun detectExpiry_RelativeAware(
        context: ExtractionContext,
        minConfidence: Float
    ): List<FieldCandidate> {
        val candidates = mutableListOf<FieldCandidate>()
        
        // Pattern 1: "Expires in X days/weeks/months"
        val relativePattern = Regex(
            """(?:expires?|valid)\s+in\s+(\d+)\s+(days?|weeks?|months?)""",
            RegexOption.IGNORE_CASE
        )
        relativePattern.findAll(context.ocrText).forEach { match ->
            val count = match.groupValues[1].toIntOrNull() ?: return@forEach
            val unit = match.groupValues[2].lowercase()
            
            val calendar = Calendar.getInstance()
            when {
                unit.startsWith("day") -> calendar.add(Calendar.DAY_OF_YEAR, count)
                unit.startsWith("week") -> calendar.add(Calendar.WEEK_OF_YEAR, count)
                unit.startsWith("month") -> calendar.add(Calendar.MONTH, count)
            }
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            candidates.add(FieldCandidate(
                value = dateFormat.format(calendar.time),
                confidence = 0.9f,
                source = "relative_date",
                context = match.value
            ))
        }
        
        // Pattern 2: Absolute dates (DD/MM/YYYY, DD-MM-YYYY, etc.)
        val absolutePatterns = listOf(
            Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})"""),
            Regex("""(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in absolutePatterns) {
            pattern.findAll(context.ocrText).forEach { match ->
                candidates.add(FieldCandidate(
                    value = match.value,
                    confidence = 0.8f,
                    source = "absolute_date",
                    context = getWordContext(context.ocrText, match.range)
                ))
            }
        }
        
        // Pattern 3: "Valid until/till DD/MM/YYYY"
        val validUntilPattern = Regex(
            """(?:valid|expires?)\s+(?:until|till|by)\s+(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        validUntilPattern.findAll(context.ocrText).forEach { match ->
            candidates.add(FieldCandidate(
                value = match.groupValues[1],
                confidence = 0.85f,
                source = "valid_until",
                context = match.value
            ))
        }
        
        return candidates.filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
    }
    
    /**
     * Code detection with "no code needed" support
     */
    private fun detectCode_OrMarkNotNeeded(
        context: ExtractionContext,
        minConfidence: Float
    ): List<FieldCandidate> {
        val candidates = mutableListOf<FieldCandidate>()
        
        // Pattern 1: Explicit code with context
        val contextCodePattern = Regex(
            """(?:code|coupon|promo|voucher)\s*:?\s*([A-Z0-9]{4,20})""",
            RegexOption.IGNORE_CASE
        )
        contextCodePattern.findAll(context.ocrText).forEach { match ->
            val code = match.groupValues[1]
            if (isValidCodePattern(code)) {
                candidates.add(FieldCandidate(
                    value = code,
                    confidence = 0.85f,
                    source = "context_code",
                    context = match.value
                ))
            }
        }
        
        // Pattern 2: Generic alphanumeric code
        val genericCodePattern = Regex("""\b([A-Z0-9]{6,15})\b""")
        genericCodePattern.findAll(context.ocrText).forEach { match ->
            val code = match.value
            if (code !in COMMON_WORDS && isValidCodePattern(code)) {
                candidates.add(FieldCandidate(
                    value = code,
                    confidence = 0.6f,
                    source = "generic_code",
                    context = getWordContext(context.ocrText, match.range)
                ))
            }
        }
        
        // Pattern 3: Check for "no code" indicators
        val noCodePatterns = listOf(
            "no code needed", "no code required", "cashback", "automatic",
            "auto-applied", "auto applied", "automatically applied"
        )
        val hasNoCodeIndicator = noCodePatterns.any {
            context.ocrText.contains(it, ignoreCase = true)
        }
        
        if (hasNoCodeIndicator && candidates.isEmpty()) {
            candidates.add(FieldCandidate(
                value = "NO_CODE_NEEDED",
                confidence = 0.8f,
                source = "no_code_indicator",
                context = "Cashback/auto-applied offer"
            ))
        }
        
        return candidates.filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }
    }
    
    /**
     * Validate if a string is a valid code pattern
     */
    private fun isValidCodePattern(code: String): Boolean {
        // Must have at least one digit
        if (!code.any { it.isDigit() }) return false
        
        // Must have at least one letter
        if (!code.any { it.isLetter() }) return false
        
        // Shouldn't be all the same character
        if (code.toSet().size == 1) return false
        
        return true
    }
    
    /**
     * Get surrounding context for a word
     */
    private fun getWordContext(text: String, range: IntRange): String {
        val start = (range.first - 20).coerceAtLeast(0)
        val end = (range.last + 20).coerceAtMost(text.length)
        return text.substring(start, end).trim()
    }
}


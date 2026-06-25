package com.example.coupontracker.extraction

import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.StoreCandidateValidator
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
            "COUPON", "OFFER", "VALID", "UPTO", "FLAT", "OFF", "CASHBACK", "COPY", "THIS",
            "THAT", "YOUR", "USE", "APPLY", "SAVE", "DISCOUNT", "VIA", "PAY", "ONLY",
            "WON", "WIN", "NEXT", "ORDER", "PURCHASE", "BUY", "DETAILS", "NOW", "NEW",
            "MINIMUM", "VALUE", "MIN", "VALIDITY"
        )

        private val LAYOUT_TOKENS = setOf("minimum", "order", "value", "validity", "details")

        // Payment methods - NOT store names
        private val PAYMENT_METHODS = setOf(
            "UPI", "CARD", "WALLET", "CASH", "COD", "NET", "BANKING", "DEBIT", "CREDIT"
        )

        private val WATERMARK_WORDS = setOf(
            "SHARE", "DETAILS", "TERMS", "NOW", "TODAY"
        )

        private inline fun safeLogDebug(tag: String, message: () -> String) {
            try {
                Log.d(tag, message())
            } catch (_: Throwable) {
                // Ignore logging failures in unit tests where android.util.Log is a stub.
            }
        }
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
        
        safeLogDebug(TAG) { "Structured extraction found ${results.values.sumOf { it.size }} candidates across ${results.size} field types" }
        
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
        
        // Strategy 1: Explicit context patterns ("from X", "at Y") - but NOT payment methods
        val explicitPattern = Regex("""\b(from|at|on)\s+([A-Z][A-Za-z0-9&.'\-]{1,30})""", RegexOption.IGNORE_CASE)
        explicitPattern.findAll(context.ocrText).forEach { match ->
            val preposition = match.groupValues[1].lowercase(Locale.ROOT)
            val storeName = match.groupValues[2]
            // Skip payment methods like "via CRED", "via UPI"
            if (storeName.length >= 3 &&
                storeName.uppercase() !in PAYMENT_METHODS &&
                StoreCandidateValidator.isAcceptable(storeName, context.ocrText)
            ) {
                val confidence = when {
                    storeName.contains('.') -> 0.55f
                    preposition == "on" -> 0.45f
                    else -> 0.8f
                }
                candidates.add(FieldCandidate(
                    value = storeName,
                    confidence = confidence,
                    source = "explicit_pattern",
                    context = match.value
                ))
            }
        }
        
        // Strategy 2: ALL CAPS words (likely brand names) - prioritize early text + validate
        val allCapsPattern = Regex("""\b([A-Z]{2,})\b""")
        allCapsPattern.findAll(context.ocrText).forEach { match ->
            val word = match.value
            if (
                word !in COMMON_WORDS &&
                word.uppercase() !in PAYMENT_METHODS &&
                !isLikelyWatermark(word) &&
                !isWalletHeaderLine(context.ocrText, match.range) &&
                word.length in 3..15 &&
                isValidBrandName(word) &&
                StoreCandidateValidator.isAcceptable(word, context.ocrText)
            ) {
                // Higher confidence for words in first 30% of text
                val position = match.range.first.toFloat() / context.ocrText.length
                val confidence = if (position < 0.3f) 0.70f else 0.5f  // Increased from 0.65
                
                candidates.add(FieldCandidate(
                    value = word,
                    confidence = confidence,
                    source = "all_caps_validated",
                    context = getWordContext(context.ocrText, match.range)
                ))
            }
        }
        
        // Strategy 3: Title Case brands with SMART position-based confidence
        val titleCasePattern = Regex("""\b([A-Z][a-z]{2,}(?:[ \t]+[A-Z][a-z]{2,}){0,2})\b""")
        
        titleCasePattern.findAll(context.ocrText).forEach { match ->
            val storeName = match.value
            val words = storeName.split("\\s+".toRegex())
            val wordCount = words.size

            // Skip if all words are common
            val isAllCommon = words.all { it.uppercase() in COMMON_WORDS }
            if (isAllCommon) return@forEach

            if (words.any { LAYOUT_TOKENS.contains(it.lowercase(Locale.ROOT)) }) return@forEach
            
            // Validate brand name quality (reject OCR garbage like "Pastm Patm")
            if (!isValidBrandName(storeName) ||
                isLikelyWatermark(storeName) ||
                !StoreCandidateValidator.isAcceptable(storeName, context.ocrText)
            ) {
                return@forEach
            }
            if (isWalletHeaderLine(context.ocrText, match.range)) return@forEach
            
            // Calculate position-based confidence
            val position = match.range.first.toFloat() / context.ocrText.length
            val lineIndex = context.ocrText.substring(0, match.range.first).count { it == '\n' }
            val isInFirstFewLines = lineIndex < 3
            
            // SMART CONFIDENCE LOGIC:
            // 1. Single word in first 3 lines = HIGHEST (likely brand name/logo)
            // 2. Multi-word in first 3 lines = HIGH
            // 3. Multi-word later = MEDIUM (could be description)
            // 4. Single word later = LOW
            val confidence = when {
                wordCount == 1 && isInFirstFewLines -> 0.90f  // Single-word logo/header at top
                wordCount >= 2 && isInFirstFewLines -> 0.85f  // Multi-word logo/header at top
                wordCount >= 2 && position < 0.5f -> 0.70f    // Multi-word merchant in middle
                wordCount == 1 && position < 0.3f -> 0.65f    // Single word early
                else -> 0.50f                                  // Low confidence
            }
            
            candidates.add(FieldCandidate(
                value = storeName,
                confidence = confidence,
                source = when {
                    wordCount == 1 && isInFirstFewLines -> "brand_name_top"
                    wordCount >= 2 && isInFirstFewLines -> "multi_word_brand_top"
                    else -> "title_case"
                },
                context = "$wordCount-word at line $lineIndex (${(position * 100).toInt()}%)"
            ))
        }
        
        // Strategy 4: Repeated words (brand names often repeat)
        val wordFrequency = mutableMapOf<String, Int>()
        Regex("""\b([A-Z][A-Za-z]{2,})\b""").findAll(context.ocrText).forEach { match ->
            val word = match.value
            if (word !in COMMON_WORDS &&
                !isWalletHeaderLine(context.ocrText, match.range) &&
                StoreCandidateValidator.isAcceptable(word, context.ocrText)
            ) {
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
        
        // Pattern 3: Percentage (filter spurious like "030%" and battery indicators)
        // CRITICAL: Filter out battery/signal indicators (5G 36%, LTE 45%, Ở 38%)
        val percentPattern = Regex(
            """(?<![0-9])([1-9][0-9]?|100)(?:\.[0-9]{1,2})?\s*%\s*(off|discount|cashback)?""",
            RegexOption.IGNORE_CASE
        )
        
        // UI chrome patterns to exclude (battery, signal, time indicators)
        val uiNoisePattern = Regex(
            """(?:5G|4G|LTE|VoLTE|Ở|🔋|📶|battery|signal|wifi)\s+\d+%""",
            RegexOption.IGNORE_CASE
        )
        
        percentPattern.findAll(context.ocrText).forEach { match ->
            val percentage = match.groupValues[1].toIntOrNull() ?: 0
            val matchContext = context.ocrText.substring(
                maxOf(0, match.range.first - 10),
                minOf(context.ocrText.length, match.range.last + 10)
            )
            
            // Valid percentages are 1-100 AND not part of UI chrome
            if (percentage in 1..100 && !uiNoisePattern.containsMatchIn(matchContext)) {
                // Extra validation: check if percentage appears in first 3 lines (likely UI chrome)
                val matchLine = context.ocrText.substring(0, match.range.first).count { it == '\n' }
                
                // Reduce confidence if in first 3 lines and isolated (likely status bar)
                val isLikelyUiChrome = matchLine < 3 && !match.value.lowercase().contains(Regex("off|discount|cashback|save"))
                val confidence = if (isLikelyUiChrome) 0.3f else 0.75f
                
                if (confidence >= 0.5f) {  // Only add if confidence threshold met
                    candidates.add(FieldCandidate(
                        value = "${match.groupValues[1]}%",
                        confidence = confidence,
                        source = "percentage",
                        context = match.value
                    ))
                }
            }
        }
        
        // Pattern 3b: "flat X off" without currency symbol
        val flatAmountPattern = Regex(
            """(?:flat|get|win|won)\s+([0-9]{1,5})\s+(?:off|cashback|discount|rupees?)""",
            RegexOption.IGNORE_CASE
        )
        flatAmountPattern.findAll(context.ocrText).forEach { match ->
            val amount = match.groupValues[1]
            candidates.add(FieldCandidate(
                value = "₹$amount",
                confidence = 0.85f,  // High confidence for explicit "flat X off"
                source = "flat_amount",
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
            
            // Use screenshot timestamp if available, otherwise use current time
            val calendar = Calendar.getInstance()
            if (context.captureTimestamp != null) {
                calendar.time = context.captureTimestamp
            }
            val baseDate = calendar.clone() as Calendar
            
            when {
                unit.startsWith("day") -> calendar.add(Calendar.DAY_OF_YEAR, count)
                unit.startsWith("week") -> calendar.add(Calendar.WEEK_OF_YEAR, count)
                unit.startsWith("month") -> calendar.add(Calendar.MONTH, count)
            }
            
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val calculatedDate = dateFormat.format(calendar.time)
            
            safeLogDebug(TAG) {
                "📅 Expiry: OCR='${match.value}' → Base=${dateFormat.format(baseDate.time)} + $count $unit = $calculatedDate (capture timestamp: ${context.captureTimestamp != null})"
            }
            
            candidates.add(FieldCandidate(
                value = calculatedDate,
                confidence = 0.9f,
                source = "relative_date",
                context = match.value
            ))
        }
        
        // Pattern 2: Absolute dates (DD/MM/YYYY, DD-MM-YYYY, etc.)
        val absolutePatterns = listOf(
            Regex("""(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})"""),
            Regex(
                """(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\s*,?\s*\d{2,4})""",
                RegexOption.IGNORE_CASE
            )
        )
        
        for (pattern in absolutePatterns) {
            pattern.findAll(context.ocrText).forEach { match ->
                val normalized = normalizeAbsoluteDate(match.value)
                if (normalized != null) {
                    candidates.add(FieldCandidate(
                        value = normalized,
                        confidence = 0.8f,
                        source = "absolute_date",
                        context = getWordContext(context.ocrText, match.range)
                    ))
                }
            }
        }

        // Pattern 3: "Valid until/till DD/MM/YYYY"
        val validUntilPattern = Regex(
            """(?:valid|expires?)\s+(?:until|till|by)\s+(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        validUntilPattern.findAll(context.ocrText).forEach { match ->
            val normalized = normalizeAbsoluteDate(match.groupValues[1])
            if (normalized != null) {
                candidates.add(FieldCandidate(
                    value = normalized,
                    confidence = 0.85f,
                    source = "valid_until",
                    context = match.value
                ))
            }
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
        
        // Pattern 1: Explicit code with context (supports hyphens)
        val contextCodePattern = Regex(
            """(?:code|coupon|promo|voucher)\s*:?\s*([A-Z0-9\-]{4,40})""",
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
        
        // Pattern 2: Generic alphanumeric code (supports hyphens, longer codes)
        val genericCodePattern = Regex("""\b([A-Z0-9\-]{6,40})\b""")
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
        // Remove hyphens for validation
        val cleanCode = code.replace("-", "")
        
        // Must have at least one digit
        if (!cleanCode.any { it.isDigit() }) return false
        
        // Must have at least one letter
        if (!cleanCode.any { it.isLetter() }) return false
        
        // Shouldn't be all the same character (excluding hyphens)
        if (cleanCode.toSet().size == 1) return false
        
        // Don't allow codes that are just hyphens or too short
        if (cleanCode.length < 4) return false
        
        return true
    }
    
    /**
     * Validate if a string looks like a real brand name (NOT OCR garbage)
     */
    private fun isValidBrandName(name: String): Boolean {
        val cleanName = name.trim()
        val lower = cleanName.lowercase(Locale.ROOT)

        if (LAYOUT_TOKENS.any { lower.contains(it) }) return false

        // Too short or too long
        if (cleanName.length < 3 || cleanName.length > 25) return false

        // Disallow names that look like overlays/watermarks.
        if (isLikelyWatermark(cleanName)) return false

        // Should include at least one alphabetic character
        if (!cleanName.any { it.isLetter() }) return false

        // Must have at least one vowel-like character.
        val hasVowel = cleanName.any { it.lowercaseChar() in "aeiouy" }
        if (!hasVowel) return false

        // Reject if it has too many consonants in a row (like "Pastm")
        val maxConsecutiveConsonants = cleanName.windowed(4, 1, partialWindows = false)
            .count { window -> window.all { char -> char.isLetter() && char.lowercaseChar() !in "aeiouy" } }
        if (maxConsecutiveConsonants > 0) return false

        // Reject if contains too many special characters
        val specialCharCount = cleanName.count { !it.isLetterOrDigit() && it != ' ' }
        if (specialCharCount > 2) return false

        // Reject numeric-heavy tokens (like "428")
        val digitCount = cleanName.count { it.isDigit() }
        val letterCount = cleanName.count { it.isLetter() }
        if (digitCount > 0 && letterCount < 2) return false

        // Reject OCR-like garbage: repeated character patterns.
        // Only reject if the name is very short (< 4) or if it has excessive repetition
        if (cleanName.length < 4) {
            val hasRepeatedChars = cleanName.lowercase().zipWithNext().any { (a, b) -> a == b && a.isLetter() }
            if (hasRepeatedChars) return false
        }

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

    private fun isLikelyWatermark(name: String): Boolean {
        val upper = name.uppercase(Locale.US)
        if (upper in WATERMARK_WORDS) return true

        return false
    }

    private fun isWalletHeaderLine(text: String, range: IntRange): Boolean {
        val lineStart = text.lastIndexOf('\n', range.first).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', range.last).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd).lowercase(Locale.US)
        return listOf("reward", "wallet", "active", "available").any { line.contains(it) }
    }

    private fun normalizeAbsoluteDate(raw: String): String? {
        val cleaned = raw.trim().replace("\n", " ").replace(",", " ").replace(Regex("\\s+"), " ").trimEnd('.', ',', ';')
        val patterns = listOf(
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "d MMM yyyy",
            "dd MMM yyyy",
            "d MMMM yyyy",
            "dd MMMM yyyy",
            "MMM dd yyyy",
            "MMM dd yyyy"
        )

        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")  // Use same timezone as formatter
                }
                val date = parser.parse(cleaned)
                if (date != null) {
                    return isoFormat.format(date)
                }
            } catch (_: Exception) {
                // Try next pattern
            }
        }

        return null
    }
}

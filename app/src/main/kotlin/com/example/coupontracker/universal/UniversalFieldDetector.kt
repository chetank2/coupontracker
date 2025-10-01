package com.example.coupontracker.universal

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.IndianDateParser
import com.example.coupontracker.util.IndianCurrencyParser
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Universal field detector that uses visual, textual, and learned patterns
 * to detect coupon fields without brand-specific hardcoding.
 */
@Singleton
class UniversalFieldDetector @Inject constructor(
    private val patternLearner: PatternLearningEngine,
    private val layoutAnalyzer: UniversalLayoutAnalyzer,
    private val confidenceScorer: AdaptiveConfidenceScorer,
    private val ocrEngine: OcrEngine
) {
    companion object {
        private const val TAG = "UniversalFieldDetector"
        private const val MIN_CONFIDENCE_THRESHOLD = 0.3f
    }

    /**
     * Detect all fields in a coupon using universal patterns
     */
    suspend fun detectFields(
        image: Bitmap, 
        ocrText: String,
        context: ExtractionContext = ExtractionContext()
    ): Map<FieldType, List<ExtractionCandidate>> {
        
        Log.d(TAG, "Starting universal field detection")
        
        // Analyze layout structure
        val layout = layoutAnalyzer.analyzeCouponStructure(image)
        
        return mapOf(
            FieldType.COUPON_CODE to detectCouponCodes(image, ocrText, layout, context),
            FieldType.EXPIRY_DATE to detectExpiryDates(image, ocrText, layout, context),
            FieldType.AMOUNT to detectCashbackAmounts(image, ocrText, layout, context),
            FieldType.STORE_NAME to detectStoreNames(image, ocrText, layout, context)
        ).filterValues { it.isNotEmpty() }
    }

    /**
     * Detect coupon codes using multiple strategies
     */
    private suspend fun detectCouponCodes(
        image: Bitmap,
        text: String,
        layout: CouponLayout,
        context: ExtractionContext
    ): List<ExtractionCandidate> {

        val candidates = mutableListOf<ExtractionCandidate>()
        val seenRegionCodes = mutableSetOf<String>()

        // Strategy 1: Visual cues (boxes, highlighting, distinct fonts)
        layout.codeRegion?.let { region ->
            val regionText = extractTextFromRegion(image, region)
            val normalized = normalizeCodeText(regionText)
            if (normalized.isNotEmpty() && seenRegionCodes.add(normalized)) {
                candidates.add(
                    ExtractionCandidate(
                        text = normalized,
                        confidence = 0.9f,
                        source = ExtractionSource.VISUAL_REGION,
                        context = mapOf("region" to "code")
                    )
                )
            }

            val visualCandidates = detectVisuallyDistinctCodes(image, region)
            candidates.addAll(visualCandidates)
        }
        
        // Strategy 2: Context cues (near "code:", "use:", "apply")
        val contextCandidates = detectContextualCodes(text)
        candidates.addAll(contextCandidates)
        
        // Strategy 3: Learned patterns from successful extractions
        val learnedPatterns = patternLearner.getRelevantPatterns(FieldType.COUPON_CODE, context)
        val learnedCandidates = applyLearnedPatterns(text, learnedPatterns)
        candidates.addAll(learnedCandidates)
        
        // Strategy 4: Universal alphanumeric patterns
        val genericCandidates = detectGenericCodePatterns(text)
        candidates.addAll(genericCandidates)
        
        // Score and filter candidates
        return candidates
            .distinctBy { it.text.uppercase() }
            .map { candidate ->
                val confidence = confidenceScorer.scoreCandidate(candidate, FieldType.COUPON_CODE)
                candidate.copy(confidence = confidence)
            }
            .filter { it.confidence >= MIN_CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }
    }

    /**
     * Detect expiry dates using enhanced pattern matching
     */
    private suspend fun detectExpiryDates(
        image: Bitmap,
        text: String,
        layout: CouponLayout, 
        context: ExtractionContext
    ): List<ExtractionCandidate> {
        
        val candidates = mutableListOf<ExtractionCandidate>()
        
        // Use enhanced Indian date parser
        val parseResult = IndianDateParser.extractExpiryFromText(text)
        if (parseResult.date != null) {
            candidates.add(
                ExtractionCandidate(
                    text = parseResult.date.toString(),
                    confidence = parseResult.confidence,
                    source = ExtractionSource.PATTERN_MATCHING,
                    context = mapOf("parser_reason" to parseResult.reason)
                )
            )
        }
        
        // Look for dates in specific regions
        layout.expiryRegion?.let { region ->
            val regionText = extractTextFromRegion(image, region)
            val regionParseResult = IndianDateParser.extractExpiryFromText(regionText)
            if (regionParseResult.date != null && regionParseResult.confidence > parseResult.confidence) {
                candidates.add(
                    ExtractionCandidate(
                        text = regionParseResult.date.toString(),
                        confidence = regionParseResult.confidence,
                        source = ExtractionSource.VISUAL_REGION,
                        context = mapOf("region" to "expiry", "parser_reason" to regionParseResult.reason)
                    )
                )
            }
        }
        
        // Apply learned expiry patterns
        val learnedPatterns = patternLearner.getRelevantPatterns(FieldType.EXPIRY_DATE, context)
        val learnedCandidates = applyLearnedPatterns(text, learnedPatterns)
        candidates.addAll(learnedCandidates)
        
        return candidates
            .distinctBy { it.text }
            .filter { it.confidence >= MIN_CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }
    }

    /**
     * Detect cashback amounts using universal currency parsing
     */
    private suspend fun detectCashbackAmounts(
        image: Bitmap,
        text: String,
        layout: CouponLayout,
        context: ExtractionContext
    ): List<ExtractionCandidate> {
        
        val candidates = mutableListOf<ExtractionCandidate>()
        
        // Extract all potential amounts from text
        val amountPatterns = listOf(
            // Rupee amounts
            Regex("""(?:₹|Rs\.?\s*|INR\s*)\s*([0-9,]+(?:\.[0-9]{1,2})?)(?:\s*(?:off|cashback|back|discount|save))?""", RegexOption.IGNORE_CASE),
            // Percentage amounts  
            Regex("""([0-9]+(?:\.[0-9]{1,2})?)\s*%(?:\s*(?:off|discount|cashback))?""", RegexOption.IGNORE_CASE),
            // "Up to" patterns
            Regex("""(?:up\s*to|upto)\s*(?:₹\s*)?([0-9,]+(?:\.[0-9]{1,2})?)\s*(?:%|off|cashback)?""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in amountPatterns) {
            pattern.findAll(text).forEach { match ->
                val amountText = match.value
                val parsedAmount = IndianCurrencyParser.parseAmount(amountText)
                
                if (parsedAmount != null && parsedAmount > 0) {
                    val confidence = calculateAmountConfidence(match, text)
                    candidates.add(
                        ExtractionCandidate(
                            text = amountText,
                            confidence = confidence,
                            source = ExtractionSource.PATTERN_MATCHING,
                            context = mapOf("parsed_value" to parsedAmount.toString())
                        )
                    )
                }
            }
        }
        
        // Look for amounts in specific regions
        layout.amountRegion?.let { region ->
            val regionText = extractTextFromRegion(image, region)
            // Apply same patterns to region text with higher confidence
            // ... (similar pattern matching with region boost)
        }
        
        return candidates
            .distinctBy { it.text }
            .filter { it.confidence >= MIN_CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }
    }

    /**
     * Detect store names using universal brand detection
     */
    private suspend fun detectStoreNames(
        image: Bitmap,
        text: String,
        layout: CouponLayout,
        context: ExtractionContext
    ): List<ExtractionCandidate> {
        
        val candidates = mutableListOf<ExtractionCandidate>()
        val seenRegionStores = mutableSetOf<String>()

        // Look for store names in logo region (usually top)
        layout.logoRegion?.let { region ->
            val logoText = extractTextFromRegion(image, region)
            val normalized = normalizeRegionText(logoText)
            if (normalized.isNotBlank() && seenRegionStores.add(normalized.lowercase(Locale.ROOT))) {
                candidates.add(
                    ExtractionCandidate(
                        text = normalized,
                        confidence = 0.8f, // High confidence for logo region
                        source = ExtractionSource.VISUAL_REGION,
                        context = mapOf("region" to "logo")
                    )
                )
            }
        }
        
        // Look for explicit store indicators
        val storePatterns = listOf(
            Regex("""(?:from|at|by|shop|store|brand)\s+([A-Za-z0-9\s&.'-]+)""", RegexOption.IGNORE_CASE),
            Regex("""^([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)"""), // Capitalized words at start
        )
        
        for (pattern in storePatterns) {
            pattern.findAll(text).forEach { match ->
                val storeName = match.groupValues[1].trim()
                if (storeName.length >= 3 && storeName.length <= 30) {
                    val confidence = calculateStoreNameConfidence(storeName, text)
                    candidates.add(
                        ExtractionCandidate(
                            text = storeName,
                            confidence = confidence,
                            source = ExtractionSource.PATTERN_MATCHING,
                            context = mapOf("pattern" to pattern.pattern)
                        )
                    )
                }
            }
        }
        
        return candidates
            .distinctBy { it.text }
            .filter { it.confidence >= MIN_CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }
    }

    // Helper methods for specific detection strategies
    
    private fun detectVisuallyDistinctCodes(image: Bitmap, region: Region): List<ExtractionCandidate> {
        // TODO: Implement visual analysis for highlighted/boxed text
        // This would use computer vision to find text that stands out visually
        return emptyList()
    }
    
    private fun detectContextualCodes(text: String): List<ExtractionCandidate> {
        val contextPatterns = listOf(
            Regex("""(?:code|coupon|promo|voucher)\s*:?\s*([A-Z0-9-_]{4,20})""", RegexOption.IGNORE_CASE),
            Regex("""(?:use|apply)\s+(?:code\s+)?([A-Z0-9-_]{4,20})""", RegexOption.IGNORE_CASE),
            Regex("""\b([A-Z0-9-_]{6,15})\b(?=\s*(?:to\s+)?(?:get|save|for))""", RegexOption.IGNORE_CASE)
        )
        
        val candidates = mutableListOf<ExtractionCandidate>()
        
        for (pattern in contextPatterns) {
            pattern.findAll(text).forEach { match ->
                val code = match.groupValues[1]
                if (isValidCodePattern(code)) {
                    candidates.add(
                        ExtractionCandidate(
                            text = code,
                            confidence = 0.7f, // High confidence for context-based detection
                            source = ExtractionSource.CONTEXT_CLUES,
                            context = mapOf("pattern" to pattern.pattern)
                        )
                    )
                }
            }
        }
        
        return candidates
    }
    
    private fun detectGenericCodePatterns(text: String): List<ExtractionCandidate> {
        // Generic alphanumeric patterns without context
        val genericPattern = Regex("""\b([A-Z0-9]{6,15})\b""")
        val candidates = mutableListOf<ExtractionCandidate>()
        
        genericPattern.findAll(text).forEach { match ->
            val code = match.value
            if (isValidCodePattern(code)) {
                candidates.add(
                    ExtractionCandidate(
                        text = code,
                        confidence = 0.4f, // Lower confidence for generic patterns
                        source = ExtractionSource.PATTERN_MATCHING,
                        context = mapOf("pattern" to "generic_alphanumeric")
                    )
                )
            }
        }
        
        return candidates
    }
    
    private fun applyLearnedPatterns(text: String, patterns: List<LearnedPattern>): List<ExtractionCandidate> {
        val candidates = mutableListOf<ExtractionCandidate>()
        
        for (pattern in patterns) {
            try {
                val regex = Regex(pattern.pattern, RegexOption.IGNORE_CASE)
                regex.findAll(text).forEach { match ->
                    candidates.add(
                        ExtractionCandidate(
                            text = match.value,
                            confidence = pattern.confidence,
                            source = ExtractionSource.LEARNED_PATTERN,
                            context = mapOf("learned_pattern_id" to pattern.id)
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid learned pattern: ${pattern.pattern}", e)
            }
        }
        
        return candidates
    }
    
    private fun isValidCodePattern(code: String): Boolean {
        // Universal code validation rules
        return code.length in 4..20 &&
               code.matches(Regex("[A-Z0-9-_]+")) &&
               !isJunkCode(code)
    }
    
    private fun isJunkCode(code: String): Boolean {
        val junkPatterns = setOf(
            "VOUCHER", "COUPON", "OFFER", "DISCOUNT", "CODE", 
            "USING", "NEEDED", "APPLY", "USE", "GET", "SAVE"
        )
        return junkPatterns.contains(code.uppercase())
    }
    
    private fun calculateAmountConfidence(match: MatchResult, fullText: String): Float {
        var confidence = 0.5f
        
        // Boost confidence based on context
        val beforeText = fullText.substring(0, match.range.first).takeLast(20).lowercase()
        val afterText = fullText.substring(match.range.last + 1).take(20).lowercase()
        
        if (beforeText.contains("get") || beforeText.contains("save") || beforeText.contains("flat")) {
            confidence += 0.2f
        }
        
        if (afterText.contains("off") || afterText.contains("cashback") || afterText.contains("discount")) {
            confidence += 0.2f
        }
        
        return confidence.coerceAtMost(1.0f)
    }
    
    private fun calculateStoreNameConfidence(storeName: String, fullText: String): Float {
        var confidence = 0.4f
        
        // Boost confidence for known patterns
        if (storeName.matches(Regex("[A-Z][a-z]+"))) confidence += 0.2f // Proper case
        if (storeName.length in 3..15) confidence += 0.1f // Reasonable length
        if (fullText.indexOf(storeName) < 100) confidence += 0.1f // Near beginning
        
        return confidence.coerceAtMost(1.0f)
    }
    
    private suspend fun extractTextFromRegion(image: Bitmap, region: Region): String {
        var croppedBitmap: Bitmap? = null
        return try {
            val bounds = region.bounds
            val left = bounds.left.coerceIn(0f, image.width.toFloat())
            val top = bounds.top.coerceIn(0f, image.height.toFloat())
            val right = bounds.right.coerceIn(0f, image.width.toFloat())
            val bottom = bounds.bottom.coerceIn(0f, image.height.toFloat())

            val x = left.roundToInt().coerceIn(0, image.width - 1)
            val y = top.roundToInt().coerceIn(0, image.height - 1)
            val width = max(1, min(image.width - x, max(1, (right - left).roundToInt())))
            val height = max(1, min(image.height - y, max(1, (bottom - top).roundToInt())))

            if (width <= 0 || height <= 0 || x + width > image.width || y + height > image.height) {
                return ""
            }

            croppedBitmap = Bitmap.createBitmap(image, x, y, width, height)
            ocrEngine.recognize(croppedBitmap!!).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract text from region", e)
            ""
        } finally {
            croppedBitmap?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun normalizeRegionText(text: String): String {
        return text.replace("\\s+".toRegex(), " ").trim()
    }

    private fun normalizeCodeText(text: String): String {
        return text
            .replace("[^A-Za-z0-9-_]".toRegex(), "")
            .uppercase(Locale.ROOT)
    }
}

/**
 * Represents a candidate for field extraction
 */
data class ExtractionCandidate(
    val text: String,
    val confidence: Float,
    val source: ExtractionSource,
    val context: Map<String, String> = emptyMap()
)

/**
 * Source of extraction candidate
 */
enum class ExtractionSource {
    VISUAL_REGION,      // Found in visually distinct region
    CONTEXT_CLUES,      // Found near context keywords
    PATTERN_MATCHING,   // Found using regex patterns
    LEARNED_PATTERN     // Found using learned patterns
}

/**
 * Context for extraction to help with pattern selection
 */
data class ExtractionContext(
    val brandHint: String? = null,
    val categoryHint: String? = null,
    val previousSuccesses: List<String> = emptyList()
)

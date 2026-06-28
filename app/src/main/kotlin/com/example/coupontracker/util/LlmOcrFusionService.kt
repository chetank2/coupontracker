package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.extraction.rules.CouponInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ROI-guided fusion of LLM and OCR results
 * Implements the fusion logic from the analysis document
 */
class LlmOcrFusionService(
    private val context: android.content.Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine
) {
    private val TAG = "LlmOcrFusionService"
    
    // Keywords to search for when running targeted OCR
    private val CODE_KEYWORDS = listOf("CODE", "Use", "Apply", "Promo", "Coupon")
    private val EXPIRY_KEYWORDS = listOf("Valid", "Expiry", "Expires", "Till", "Until")
    private val AMOUNT_KEYWORDS = listOf("₹", "Rs", "INR", "Off", "Cashback", "%")
    
    /**
     * Fuse LLM results with targeted OCR for improved accuracy
     */
    suspend fun fuseResults(
        bitmap: Bitmap,
        llmResult: CouponInfo,
        brand: String?,
        captureTimestamp: Date? = null
    ): CouponInfo = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Starting LLM-OCR fusion for brand: $brand")
        
        try {
            // Get OCR text spans
            val ocrSpans = extractOcrTextSpans(bitmap)
            
            // Fuse each field independently
            val fusedCode = fuseCode(llmResult.redeemCode, ocrSpans, brand)
            val fusedExpiry = fuseExpiryDate(llmResult.expiryDate, ocrSpans, captureTimestamp)
            val fusedDetail = fuseCashbackDetail(llmResult.cashbackDetail, ocrSpans, brand)
            
            // Return fused result
            llmResult.copy(
                redeemCode = fusedCode,
                expiryDate = fusedExpiry, // Already a Date object, no conversion needed
                cashbackDetail = fusedDetail ?: llmResult.cashbackDetail
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during fusion, returning original LLM result", e)
            llmResult
        }
    }
    
    /**
     * Fuse coupon codes using edit distance and brand awareness
     */
    private fun fuseCode(llmCode: String?, ocrSpans: List<TextSpan>, brand: String?): String? {
        // Extract clean code tokens from OCR spans near code keywords
        val ocrCodeTokens = extractCleanOcrCodeTokens(ocrSpans)
        
        return when {
            // LLM has no code, but OCR found candidates
            llmCode == null && ocrCodeTokens.isNotEmpty() -> {
                val rankedCodes = rankCodesUniversally(ocrCodeTokens)
                val best = rankedCodes.firstOrNull()
                if (best != null && best.score > 0.6) {
                    Log.d(TAG, "Using OCR code (no LLM): ${best.text} (score: ${best.score})")
                    best.text
                } else {
                    null
                }
            }
            
            // LLM has code, check if OCR has better match
            llmCode != null && ocrCodeTokens.isNotEmpty() -> {
                val normalizedLlmCode = llmCode.normalize()
                val closeMatches = ocrCodeTokens.filter { 
                    editDistance(it.normalize(), normalizedLlmCode) <= 2 
                }
                
                if (closeMatches.isNotEmpty()) {
                    val rankedMatches = rankCodesUniversally(closeMatches, normalizedLlmCode)
                    val bestMatch = rankedMatches.firstOrNull()
                    
                    if (bestMatch != null && bestMatch.score > 0.6) {
                        Log.d(TAG, "Using OCR code (better than LLM): ${bestMatch.text} vs $llmCode")
                        bestMatch.text
                    } else {
                        Log.d(TAG, "Keeping LLM code: $llmCode")
                        llmCode
                    }
                } else {
                    llmCode
                }
            }
            
            // Default: use LLM result
            else -> llmCode
        }
    }
    
    /**
     * Extract clean coupon code tokens from OCR text spans
     * Handles common prefixes, punctuation, and formatting issues
     */
    private fun extractCleanOcrCodeTokens(ocrSpans: List<TextSpan>): List<String> {
        val cleanTokens = mutableListOf<String>()
        
        // Get spans near code-related keywords
        val relevantSpans = ocrSpans.nearKeywords(CODE_KEYWORDS)
        
        for (span in relevantSpans) {
            val text = span.text
            
            // Extract potential code tokens using multiple strategies
            val extractedTokens = mutableSetOf<String>()
            
            // Strategy 1: Remove common prefixes and extract alphanumeric sequences
            val cleanedText = removeCommonCodePrefixes(text)
            val alphanumericTokens = extractAlphanumericTokens(cleanedText)
            extractedTokens.addAll(alphanumericTokens)
            
            // Strategy 2: Look for patterns that match typical coupon codes
            val patternMatches = extractCodePatterns(text)
            extractedTokens.addAll(patternMatches)
            
            // Strategy 3: Split on punctuation and filter for code-like tokens
            val punctuationSplit = splitOnPunctuation(text)
            extractedTokens.addAll(punctuationSplit)
            
            // Filter and validate extracted tokens
            for (token in extractedTokens) {
                val cleanToken = token.trim().uppercase()
                if (isValidCodeToken(cleanToken)) {
                    cleanTokens.add(cleanToken)
                }
            }
        }
        
        // Remove duplicates and return
        val uniqueTokens = cleanTokens.distinct()
        Log.d(TAG, "Extracted ${uniqueTokens.size} clean OCR code tokens: $uniqueTokens")
        return uniqueTokens
    }
    
    /**
     * Remove common prefixes that appear before coupon codes
     */
    private fun removeCommonCodePrefixes(text: String): String {
        val prefixPatterns = listOf(
            Regex("(?i)coupon\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)promo\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)use\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)apply\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)redeem\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)discount\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)code\\s*:?\\s*", RegexOption.IGNORE_CASE),
            Regex("(?i)offer\\s*code\\s*:?\\s*", RegexOption.IGNORE_CASE)
        )
        
        var cleaned = text
        for (pattern in prefixPatterns) {
            cleaned = pattern.replace(cleaned, "")
        }
        
        return cleaned.trim()
    }
    
    /**
     * Extract alphanumeric sequences that could be coupon codes
     */
    private fun extractAlphanumericTokens(text: String): List<String> {
        // Look for sequences of letters, numbers, and common separators
        val pattern = Regex("[A-Z0-9][A-Z0-9_-]{2,15}")
        return pattern.findAll(text.uppercase())
            .map { it.value }
            .toList()
    }
    
    /**
     * Extract tokens using specific coupon code patterns
     */
    private fun extractCodePatterns(text: String): List<String> {
        val patterns = listOf(
            // Standard alphanumeric codes
            Regex("\\b[A-Z0-9]{4,12}\\b"),
            // Codes with separators
            Regex("\\b[A-Z0-9]{2,6}[-_][A-Z0-9]{2,6}\\b"),
            Regex("\\b[A-Z0-9]{2,4}[-_][A-Z0-9]{2,4}[-_][A-Z0-9]{2,4}\\b"),
            // Brand-specific patterns (common prefixes)
            Regex("\\b(?:SAVE|FLAT|GET|NEW|EXTRA|FIRST|WELCOME)[A-Z0-9]{2,8}\\b"),
            // Numeric codes with letters
            Regex("\\b[A-Z]{2,4}[0-9]{2,4}\\b"),
            Regex("\\b[0-9]{2,4}[A-Z]{2,4}\\b")
        )
        
        val matches = mutableListOf<String>()
        val upperText = text.uppercase()
        
        for (pattern in patterns) {
            matches.addAll(pattern.findAll(upperText).map { it.value })
        }
        
        return matches
    }
    
    /**
     * Split text on punctuation and filter for code-like tokens
     */
    private fun splitOnPunctuation(text: String): List<String> {
        // Split on common punctuation but preserve hyphens and underscores in codes
        val tokens = text.split(Regex("[^A-Za-z0-9_-]+"))
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        
        return tokens
    }
    
    /**
     * Validate if a token looks like a valid coupon code
     */
    private fun isValidCodeToken(token: String): Boolean {
        // Basic length check
        if (token.length < 3 || token.length > 16) return false
        
        // Must contain at least one letter or number
        if (!token.any { it.isLetterOrDigit() }) return false
        
        // Reject tokens that are mostly punctuation
        val alphanumericCount = token.count { it.isLetterOrDigit() }
        if (alphanumericCount < token.length * 0.6) return false
        
        // Reject common non-code words
        val rejectWords = setOf(
            "CODE", "COUPON", "PROMO", "USE", "APPLY", "REDEEM", "DISCOUNT", 
            "OFFER", "SAVE", "GET", "FREE", "OFF", "PERCENT", "RUPEES",
            "VALID", "TILL", "EXPIRES", "TERMS", "CONDITIONS", "THE", "AND",
            "FOR", "WITH", "ON", "AT", "TO", "FROM", "BY", "OF", "IN"
        )
        
        if (rejectWords.contains(token)) return false
        
        // Reject tokens that are only numbers (unless 4+ digits which might be valid)
        if (token.all { it.isDigit() } && token.length < 4) return false
        
        // Reject tokens that are only letters and too short
        if (token.all { it.isLetter() } && token.length < 4) return false
        
        return true
    }
    
    /**
     * Fuse expiry dates with OCR validation, preserving LLM confidence
     */
    private fun fuseExpiryDate(
        llmExpiry: java.util.Date?,
        ocrSpans: List<TextSpan>,
        captureTimestamp: Date?
    ): java.util.Date? {
        val zone = ZoneId.of("Asia/Kolkata")
        val baseDate = captureTimestamp
            ?.toInstant()
            ?.atZone(zone)
            ?.toLocalDate()
            ?: LocalDate.now(zone)
        // Extract OCR date candidates near expiry keywords
        val ocrDateCandidates = ocrSpans
            .nearKeywords(EXPIRY_KEYWORDS, maxDistance = 300)
            .mapNotNull { span ->
                // Extract potential date strings near expiry keywords
                val text = span.text
                val datePatterns = listOf(
                    Regex("(?i)(?:expires?|expiring|valid)\\s+(?:in|within)\\s+\\d+\\s+(?:hours?|days?|weeks?|months?)"),
                    Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}"),
                    Regex("\\d{1,2}\\s+[A-Za-z]{3,9}\\s*\\d{2,4}"),
                    Regex("[A-Za-z]{3,9}\\s+\\d{1,2},?\\s*\\d{4}")
                )
                
                datePatterns.firstNotNullOfOrNull { pattern ->
                    pattern.find(text)?.value
                }
            }
            .distinct()
        
        return when {
            // LLM has no expiry, use best OCR candidate
            llmExpiry == null && ocrDateCandidates.isNotEmpty() -> {
                val bestCandidate = ocrDateCandidates.maxByOrNull { candidate ->
                    val extracted = IndianDateParser.extractExpiryFromText(candidate, baseDate)
                    val parsed = IndianDateParser.parseExpiryIST(candidate, baseDate)
                    maxOf(extracted.confidence, parsed.confidence)
                }
                
                if (bestCandidate != null) {
                    var parseResult = IndianDateParser.extractExpiryFromText(bestCandidate, baseDate)
                    if (parseResult.date == null) {
                        parseResult = IndianDateParser.parseExpiryIST(bestCandidate, baseDate)
                    }
                    val parsedDate = parseResult.date
                    if (parseResult.confidence > 0.7f && parsedDate != null) {
                        Log.d(TAG, "Using OCR expiry (no LLM): $bestCandidate")
                        java.util.Date.from(parsedDate.atStartOfDay(zone).toInstant())
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            
            // LLM has expiry, validate with OCR only if LLM date seems problematic
            llmExpiry != null -> {
                // Convert LLM Date back to LocalDate to check its validity
                val llmLocalDate = llmExpiry.toInstant()
                    .atZone(java.time.ZoneId.of("Asia/Kolkata"))
                    .toLocalDate()
                
                val now = java.time.LocalDate.now()
                val daysDifference = java.time.temporal.ChronoUnit.DAYS.between(now, llmLocalDate).toInt()
                
                // Calculate LLM confidence based on date reasonableness
                val llmConfidence = when {
                    daysDifference in 1..90 -> 0.9f  // Perfect range
                    daysDifference in 91..365 -> 0.75f  // Acceptable
                    daysDifference in -7..0 -> 0.6f  // Recently expired, might be valid
                    daysDifference < -7 -> 0.2f  // Too old, likely wrong
                    daysDifference > 730 -> 0.3f  // Too far future, suspicious
                    else -> 0.5f
                }
                
                // Only consider OCR if LLM confidence is genuinely low
                if (llmConfidence < 0.5f && ocrDateCandidates.isNotEmpty()) {
                    Log.d(TAG, "LLM expiry has low confidence ($llmConfidence), checking OCR alternatives")
                    
                    val bestOcrCandidate = ocrDateCandidates.maxByOrNull { candidate ->
                        val extracted = IndianDateParser.extractExpiryFromText(candidate, baseDate)
                        val parsed = IndianDateParser.parseExpiryIST(candidate, baseDate)
                        maxOf(extracted.confidence, parsed.confidence)
                    }
                    
                    if (bestOcrCandidate != null) {
                        var ocrParseResult = IndianDateParser.extractExpiryFromText(bestOcrCandidate, baseDate)
                        if (ocrParseResult.date == null) {
                            ocrParseResult = IndianDateParser.parseExpiryIST(bestOcrCandidate, baseDate)
                        }
                        
                        // Only replace if OCR is significantly better
                        val parsedOcrDate = ocrParseResult.date
                        if (ocrParseResult.confidence > llmConfidence + 0.3f && parsedOcrDate != null) {
                            Log.d(TAG, "Using OCR expiry (much better confidence): $bestOcrCandidate (${ocrParseResult.confidence}) vs LLM (${llmConfidence})")
                            java.util.Date.from(parsedOcrDate.atStartOfDay(zone).toInstant())
                        } else {
                            Log.d(TAG, "Keeping LLM expiry despite low confidence: OCR not significantly better")
                            llmExpiry
                        }
                    } else {
                        llmExpiry
                    }
                } else {
                    // LLM confidence is acceptable, keep it
                    Log.d(TAG, "Keeping LLM expiry (confidence: $llmConfidence)")
                    llmExpiry
                }
            }
            
            else -> llmExpiry
        }
    }
    
    /**
     * Fuse cashback details with brand-aware validation
     */
    private fun fuseCashbackDetail(llmDetail: String?, ocrSpans: List<TextSpan>, brand: String?): String? {
        val ocrDetailCandidates = ocrSpans
            .nearKeywords(AMOUNT_KEYWORDS)
            .mapNotNull { span ->
                val patterns = getUniversalCashbackPatterns()
                patterns.firstNotNullOfOrNull { pattern ->
                    val raw = pattern.find(span.text)?.value
                    raw?.let { DescriptionUtils.formatCashbackDetail(it) ?: it.trim() }
                }
            }
            .distinct()
        
        return when {
            llmDetail.isNullOrBlank() && ocrDetailCandidates.isNotEmpty() -> {
                val bestCandidate = ocrDetailCandidates.first()
                Log.d(TAG, "Using OCR cashback detail (no LLM): $bestCandidate")
                bestCandidate
            }
            
            !llmDetail.isNullOrBlank() && ocrDetailCandidates.isNotEmpty() -> {
                val normalizedLlm = llmDetail.replace(Regex("\\s+"), "").lowercase()
                val hasOcrSupport = ocrDetailCandidates.any { candidate ->
                    val normalizedOcr = candidate.replace(Regex("\\s+"), "").lowercase()
                    normalizedOcr.contains(normalizedLlm) || normalizedLlm.contains(normalizedOcr)
                }

                if (hasOcrSupport) {
                    Log.d(TAG, "LLM cashback detail validated by OCR: $llmDetail")
                    llmDetail
                } else {
                    Log.w(TAG, "LLM cashback detail not supported by OCR, using OCR: ${ocrDetailCandidates.first()}")
                    ocrDetailCandidates.first()
                }
            }
            
            else -> llmDetail
        }
    }
    
    /**
     * Extract OCR text spans from bitmap using real ML Kit OCR
     */
    private suspend fun extractOcrTextSpans(bitmap: Bitmap): List<TextSpan> {
        return withContext(Dispatchers.IO) {
            try {
                val boxedSpans = runCatching {
                    ocrEngine.recognizeWithBoxes(bitmap)
                        .filter { it.text.isNotBlank() && it.boundingBox.width() > 0 && it.boundingBox.height() > 0 }
                        .map { span ->
                            TextSpan(
                                text = span.text.trim(),
                                x = span.boundingBox.left,
                                y = span.boundingBox.top,
                                width = span.boundingBox.width(),
                                height = span.boundingBox.height()
                            )
                        }
                }.getOrElse { error ->
                    Log.w(TAG, "OCR boxed spans unavailable, falling back to plain text OCR", error)
                    emptyList()
                }

                if (boxedSpans.isNotEmpty()) {
                    Log.d(TAG, "Extracted ${boxedSpans.size} boxed text spans from OCR")
                    return@withContext boxedSpans
                }

                val fullText = ocrEngine.recognize(bitmap)
                
                // Split text into lines and create simple TextSpan objects
                val textSpans = mutableListOf<TextSpan>()
                
                val lines = fullText.split("\n").filter { it.isNotBlank() }
                for (line in lines) {
                    textSpans.add(
                        TextSpan(
                            text = line.trim(),
                            x = 0,
                            y = 0,
                            width = bitmap.width,
                            height = 20 // Estimated line height
                        )
                    )
                }
                
                Log.d(TAG, "Extracted ${textSpans.size} text spans from OCR")
                textSpans
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to extract OCR text spans", e)
                emptyList()
            }
        }
    }
    
    /**
     * Universal code ranking without brand-specific validators.
     */
    private data class CodeCandidate(
        val text: String,
        val baseMatch: Boolean,
        val contextualMatch: Boolean,
        val score: Double
    )

    private fun rankCodesUniversally(
        codeTokens: List<String>,
        reference: String? = null
    ): List<CodeCandidate> {
        val normalizedReference = reference?.normalize()
        return codeTokens
            .map { it.trim().uppercase() }
            .filter { it.length in 4..16 }
            .map { token ->
                val baseScore = calculateUniversalCodeScore(token)
                val contextualBonus = normalizedReference?.let { ref ->
                    calculateReferenceBonus(token, ref)
                } ?: 0.0
                val finalScore = (baseScore + contextualBonus).coerceIn(0.0, 1.0)
                CodeCandidate(token, finalScore >= 0.5, false, finalScore)
            }
            .filter { it.baseMatch }
            .sortedByDescending { it.score }
    }

    /**
     * Calculate universal code score without brand-specific patterns
     */
    private fun calculateUniversalCodeScore(code: String): Double {
        var score = 0.0

        // Base format validation
        val basePattern = Regex("^[A-Z0-9][A-Z0-9_-]{3,15}$")
        if (!basePattern.matches(code)) return 0.0

        // Length scoring (sweet spot 6-12 chars)
        score += when (code.length) {
            in 6..12 -> 0.4
            in 4..5, in 13..16 -> 0.2
            else -> 0.0
        }

        // Character variety (good codes have mix of letters and numbers)
        val hasLetters = code.any { it.isLetter() }
        val hasNumbers = code.any { it.isDigit() }
        if (hasLetters && hasNumbers) score += 0.3
        else if (hasLetters || hasNumbers) score += 0.1

        // Reward solid uppercase alphabetic tokens of reasonable length
        val allLetters = code.all { it.isLetter() }
        if (allLetters && code.length in 6..12) {
            score += 0.2
        }

        // Penalize obvious non-codes
        val junkPatterns = listOf("VOUCHER", "COUPON", "OFFER", "DISCOUNT", "NEEDED", "USING")
        if (junkPatterns.any { code.contains(it) }) score -= 0.5

        // Penalize all same character
        if (code.toSet().size < 2) score -= 0.3
        
        // Reasonable dash/underscore usage
        val separatorCount = code.count { it == '-' || it == '_' }
        if (separatorCount > 3) score -= 0.2

        return maxOf(0.0, minOf(1.0, score))
    }

    private fun calculateReferenceBonus(token: String, reference: String): Double {
        val normalizedToken = token.normalize()

        if (normalizedToken == reference) {
            return 0.2
        }

        if (normalizedToken.contains(reference)) {
            val extraChars = normalizedToken.length - reference.length
            val hasLeadingPrefix = normalizedToken.endsWith(reference)
            val hasTrailingSuffix = normalizedToken.startsWith(reference)
            if (extraChars in 1..2 && (hasLeadingPrefix || hasTrailingSuffix)) {
                return 0.15
            }
        }

        val distance = editDistance(normalizedToken, reference)
        return if (distance <= 2) 0.05 else 0.0
    }
    
    /**
     * Universal cashback patterns - replaces brand-specific patterns
     */
    private fun getUniversalCashbackPatterns(): List<Regex> {
        return listOf(
            // Rupee amounts
            Regex("""(?:₹|INR|RS\.?\s*)\s*([0-9]{1,5}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?)(?:\s*(?:CASHBACK|BACK|OFF))?""", RegexOption.IGNORE_CASE),
            
            // Percentage off
            Regex("""([1-9][0-9]?)\s*%(?:\s*(?:OFF|CASHBACK))?""", RegexOption.IGNORE_CASE),
            
            // "Up to" patterns
            Regex("""(?:UP\s*TO|Upto)\s*(?:₹\s*([0-9]{1,5}(?:,[0-9]{3})*)|([1-9][0-9]?)\s*%)""", RegexOption.IGNORE_CASE),
            
            // Dollar amounts (for international)
            Regex("""\$\s*([0-9]{1,4}(?:\.[0-9]{1,2})?)(?:\s*(?:CASHBACK|BACK|OFF))?""", RegexOption.IGNORE_CASE),
            
            // Plain numbers with context
            Regex("""(?:SAVE|GET|WIN|EARN)\s+(?:₹\s*)?([0-9]{1,5}(?:,[0-9]{3})*)""", RegexOption.IGNORE_CASE)
        )
    }

    /**
     * Calculate edit distance between two strings
     */
    private fun editDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        
        return dp[s1.length][s2.length]
    }
}

/**
 * Text span with position information
 */
data class TextSpan(
    val text: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

/**
 * Extension functions for text span processing
 */
private fun List<TextSpan>.nearKeywords(keywords: List<String>, maxDistance: Int = 200): List<TextSpan> {
    return this.filter { span ->
        keywords.any { keyword ->
            span.text.contains(keyword, ignoreCase = true) ||
            this.any { otherSpan ->
                otherSpan.text.contains(keyword, ignoreCase = true) &&
                kotlin.math.abs(span.x - otherSpan.x) + kotlin.math.abs(span.y - otherSpan.y) <= maxDistance
            }
        }
    }
}

private fun String.normalize(): String {
    return this.trim().uppercase().replace(Regex("\\s+"), "")
}

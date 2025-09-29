package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.util.CouponInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ROI-guided fusion of LLM and OCR results
 * Implements the fusion logic from the analysis document
 */
class LlmOcrFusionService(
    private val context: android.content.Context
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
        brand: String?
    ): CouponInfo = withContext(Dispatchers.IO) {
        
        Log.d(TAG, "Starting LLM-OCR fusion for brand: $brand")
        
        try {
            // Get OCR text spans
            val ocrSpans = extractOcrTextSpans(bitmap)
            
            // Fuse each field independently
            val fusedCode = fuseCode(llmResult.redeemCode, ocrSpans, brand)
            val fusedExpiry = fuseExpiryDate(llmResult.expiryDate, ocrSpans)
            val fusedAmount = fuseCashbackAmount(llmResult.cashbackAmount, ocrSpans, brand)
            
            // Return fused result
            llmResult.copy(
                redeemCode = fusedCode,
                expiryDate = fusedExpiry, // Already a Date object, no conversion needed
                cashbackAmount = fusedAmount?.let { amount ->
                    // Extract numeric value from the amount string
                    val numericValue = Regex("\\d+(\\.\\d+)?").find(amount)?.value
                    numericValue?.toDoubleOrNull() ?: llmResult.cashbackAmount
                } ?: llmResult.cashbackAmount
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
        val ocrCandidates = ocrSpans
            .nearKeywords(CODE_KEYWORDS)
            .map { it.text.normalize() }
            .filter { it.isNotBlank() }
        
        return when {
            // LLM has no code, but OCR found candidates
            llmCode == null && ocrCandidates.isNotEmpty() -> {
                val rankedCodes = BrandAwareCouponValidator.rankCodes(brand, ocrCandidates)
                val best = rankedCodes.firstOrNull()
                if (best != null && best.score > 0.6) {
                    Log.d(TAG, "Using OCR code (no LLM): ${best.text} (score: ${best.score})")
                    best.text
                } else {
                    null
                }
            }
            
            // LLM has code, check if OCR has better match
            llmCode != null && ocrCandidates.isNotEmpty() -> {
                val closeMatches = ocrCandidates.filter { 
                    editDistance(it, llmCode.normalize()) <= 2 
                }
                
                if (closeMatches.isNotEmpty()) {
                    val rankedMatches = BrandAwareCouponValidator.rankCodes(brand, closeMatches)
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
     * Fuse expiry dates with OCR validation, preserving LLM confidence
     */
    private fun fuseExpiryDate(llmExpiry: java.util.Date?, ocrSpans: List<TextSpan>): java.util.Date? {
        // Extract OCR date candidates near expiry keywords
        val ocrDateCandidates = ocrSpans
            .nearKeywords(EXPIRY_KEYWORDS, maxDistance = 300)
            .mapNotNull { span ->
                // Extract potential date strings near expiry keywords
                val text = span.text
                val datePatterns = listOf(
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
                    IndianDateParser.parseExpiryIST(candidate).confidence
                }
                
                if (bestCandidate != null) {
                    val parseResult = IndianDateParser.parseExpiryIST(bestCandidate)
                    if (parseResult.confidence > 0.7f && parseResult.date != null) {
                        Log.d(TAG, "Using OCR expiry (no LLM): $bestCandidate")
                        java.util.Date.from(parseResult.date.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
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
                        IndianDateParser.parseExpiryIST(candidate).confidence
                    }
                    
                    if (bestOcrCandidate != null) {
                        val ocrParseResult = IndianDateParser.parseExpiryIST(bestOcrCandidate)
                        
                        // Only replace if OCR is significantly better
                        if (ocrParseResult.confidence > llmConfidence + 0.3f && ocrParseResult.date != null) {
                            Log.d(TAG, "Using OCR expiry (much better confidence): $bestOcrCandidate (${ocrParseResult.confidence}) vs LLM (${llmConfidence})")
                            java.util.Date.from(ocrParseResult.date.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
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
     * Fuse cashback amounts with brand-aware validation
     */
    private fun fuseCashbackAmount(llmAmount: Double?, ocrSpans: List<TextSpan>, brand: String?): String? {
        val ocrAmountCandidates = ocrSpans
            .nearKeywords(AMOUNT_KEYWORDS)
            .mapNotNull { span ->
                val patterns = BrandAwareCouponValidator.getBrandCashbackPatterns(brand)
                patterns.firstNotNullOfOrNull { pattern ->
                    pattern.find(span.text)?.value
                }
            }
            .distinct()
        
        return when {
            llmAmount == null && ocrAmountCandidates.isNotEmpty() -> {
                val bestCandidate = ocrAmountCandidates.first()
                Log.d(TAG, "Using OCR amount (no LLM): $bestCandidate")
                bestCandidate
            }
            
            llmAmount != null && ocrAmountCandidates.isNotEmpty() -> {
                // Validate LLM amount against OCR
                val llmAmountStr = llmAmount.toString()
                val hasOcrSupport = ocrAmountCandidates.any { candidate ->
                    val normalizedOcr = candidate.replace(Regex("\\s+"), "")
                    llmAmountStr.contains(normalizedOcr) || normalizedOcr.contains(llmAmountStr)
                }
                
                if (hasOcrSupport) {
                    Log.d(TAG, "LLM amount validated by OCR: $llmAmount")
                    llmAmountStr
                } else {
                    Log.w(TAG, "LLM amount not supported by OCR, using OCR: ${ocrAmountCandidates.first()}")
                    ocrAmountCandidates.first()
                }
            }
            
            else -> llmAmount?.toString()
        }
    }
    
    /**
     * Extract OCR text spans from bitmap using real ML Kit OCR
     */
    private suspend fun extractOcrTextSpans(bitmap: Bitmap): List<TextSpan> {
        return withContext(Dispatchers.IO) {
            try {
                // Use actual ML Kit OCR for text detection
                val inputImage = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
                val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
                
                // Run OCR and extract text with coordinates
                val result = kotlinx.coroutines.suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { continuation ->
                    recognizer.process(inputImage)
                        .addOnSuccessListener { text ->
                            continuation.resume(text)
                        }
                        .addOnFailureListener { exception ->
                            continuation.resumeWithException(exception)
                        }
                }
                
                // Convert ML Kit text blocks to TextSpan objects with real coordinates
                val textSpans = mutableListOf<TextSpan>()
                
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val boundingBox = line.boundingBox
                        if (boundingBox != null) {
                            textSpans.add(
                                TextSpan(
                                    text = line.text,
                                    x = boundingBox.left,
                                    y = boundingBox.top,
                                    width = boundingBox.width(),
                                    height = boundingBox.height()
                                )
                            )
                        }
                    }
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


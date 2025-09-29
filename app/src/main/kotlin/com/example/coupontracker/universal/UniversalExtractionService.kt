package com.example.coupontracker.universal

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.CashbackInfo
import com.example.coupontracker.data.model.CashbackType
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.IndianCurrencyParser
import com.example.coupontracker.util.IndianDateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Universal extraction service that orchestrates the universal extraction pipeline
 * without brand-specific hardcoding, using learned patterns and adaptive scoring.
 */
@Singleton
class UniversalExtractionService @Inject constructor(
    private val fieldDetector: UniversalFieldDetector,
    private val patternLearner: PatternLearningEngine,
    private val confidenceScorer: AdaptiveConfidenceScorer
) {
    companion object {
        private const val TAG = "UniversalExtractionService"
        private const val MIN_EXTRACTION_CONFIDENCE = 0.4f
    }

    /**
     * Extract coupon information using universal patterns
     */
    suspend fun extractCoupon(
        image: Bitmap,
        ocrText: String,
        context: ExtractionContext = ExtractionContext()
    ): UniversalExtractionResult = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Starting universal extraction")
        
        try {
            // Detect all fields using universal patterns
            val detectedFields = fieldDetector.detectFields(image, ocrText, context)
            
            // Extract best candidates for each field
            val extractedFields = mutableMapOf<FieldType, ExtractionCandidate>()
            
            for ((fieldType, candidates) in detectedFields) {
                val bestCandidate = candidates
                    .filter { it.confidence >= MIN_EXTRACTION_CONFIDENCE }
                    .maxByOrNull { it.confidence }
                
                if (bestCandidate != null) {
                    extractedFields[fieldType] = bestCandidate
                    Log.d(TAG, "Best $fieldType: '${bestCandidate.text}' (confidence: ${bestCandidate.confidence})")
                }
            }
            
            // Convert to Coupon object
            val coupon = buildCouponFromFields(extractedFields, image.toString())
            
            // Calculate overall extraction confidence
            val overallConfidence = calculateOverallConfidence(extractedFields)
            
            UniversalExtractionResult(
                coupon = coupon,
                confidence = overallConfidence,
                extractedFields = extractedFields,
                allCandidates = detectedFields,
                success = extractedFields.isNotEmpty()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Universal extraction failed", e)
            UniversalExtractionResult(
                coupon = createFallbackCoupon(),
                confidence = 0.0f,
                extractedFields = emptyMap(),
                allCandidates = emptyMap(),
                success = false,
                error = e.message
            )
        }
    }

    /**
     * Learn from successful extraction
     */
    suspend fun learnFromSuccess(
        extractionResult: UniversalExtractionResult,
        originalText: String,
        context: ExtractionContext
    ) {
        for ((fieldType, candidate) in extractionResult.extractedFields) {
            patternLearner.learnFromSuccess(
                fieldType = fieldType,
                extractedValue = candidate.text,
                originalText = originalText,
                context = context
            )
            
            confidenceScorer.updateFromFeedback(
                candidate = candidate,
                fieldType = fieldType,
                wasCorrect = true
            )
        }
    }

    /**
     * Learn from user correction
     */
    suspend fun learnFromCorrection(
        extractionResult: UniversalExtractionResult,
        correctedCoupon: Coupon,
        originalText: String,
        context: ExtractionContext
    ) {
        // Learn from corrections for each field
        correctedCoupon.redeemCode?.let { correctCode ->
            val extractedCandidate = extractionResult.extractedFields[FieldType.COUPON_CODE]
            if (extractedCandidate != null && extractedCandidate.text != correctCode) {
                patternLearner.learnFromCorrection(
                    fieldType = FieldType.COUPON_CODE,
                    incorrectValue = extractedCandidate.text,
                    correctValue = correctCode,
                    originalText = originalText,
                    context = context
                )
                
                confidenceScorer.updateFromFeedback(
                    candidate = extractedCandidate,
                    fieldType = FieldType.COUPON_CODE,
                    wasCorrect = false
                )
            }
        }
        
        // Similar learning for other fields...
        correctedCoupon.expiryDate?.let { correctDate ->
            val extractedCandidate = extractionResult.extractedFields[FieldType.EXPIRY_DATE]
            if (extractedCandidate != null) {
                val correctDateString = correctDate.toString()
                if (extractedCandidate.text != correctDateString) {
                    patternLearner.learnFromCorrection(
                        fieldType = FieldType.EXPIRY_DATE,
                        incorrectValue = extractedCandidate.text,
                        correctValue = correctDateString,
                        originalText = originalText,
                        context = context
                    )
                }
            }
        }
    }

    /**
     * Get extraction statistics for monitoring
     */
    fun getExtractionStats(): ExtractionStats {
        val patternStats = patternLearner.getPatternStats()
        val featureImportance = confidenceScorer.getFeatureImportance()
        
        return ExtractionStats(
            patternStats = patternStats,
            featureImportance = featureImportance,
            totalPatternsLearned = patternStats.values.sumOf { it.totalPatterns }
        )
    }

    // Private helper methods

    private fun buildCouponFromFields(
        extractedFields: Map<FieldType, ExtractionCandidate>,
        imageUri: String
    ): Coupon {
        
        // Extract store name
        val storeName = extractedFields[FieldType.STORE_NAME]?.text ?: "Unknown Store"
        
        // Extract coupon code
        val redeemCode = extractedFields[FieldType.COUPON_CODE]?.text
        
        // Extract and parse expiry date
        val expiryDate = extractedFields[FieldType.EXPIRY_DATE]?.let { candidate ->
            parseExpiryDate(candidate.text)
        }
        
        // Extract and parse amount
        val amountCandidate = extractedFields[FieldType.AMOUNT]
        val (cashbackAmount, cashbackInfo) = if (amountCandidate != null) {
            parseCashbackAmount(amountCandidate.text)
        } else {
            Pair(0.0, CashbackInfo(CashbackType.AMOUNT, 0.0))
        }
        
        // Build description from available information
        val description = buildDescription(extractedFields)
        
        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount, // Legacy field
            redeemCode = redeemCode,
            imageUri = imageUri,
            
            // New typed cashback fields
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency,
            offerText = amountCandidate?.text,
            
            category = null,
            rating = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    private fun parseExpiryDate(dateText: String): Date? {
        return try {
            val parseResult = IndianDateParser.extractExpiryFromText(dateText)
            if (parseResult.date != null && parseResult.confidence > 0.5f) {
                // Convert LocalDate to Date
                Date.from(parseResult.date.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
            } else {
                // Fallback to simple date parsing
                IndianDateParser.parseExpiryIST(dateText).date?.let { localDate ->
                    Date.from(localDate.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse expiry date: $dateText", e)
            null
        }
    }

    private fun parseCashbackAmount(amountText: String): Pair<Double, CashbackInfo> {
        return try {
            // Use Indian currency parser for robust parsing
            val numericValue = IndianCurrencyParser.parseAmount(amountText) ?: 0.0
            
            // Determine cashback type
            val cashbackInfo = when {
                amountText.contains("%") -> CashbackInfo(CashbackType.PERCENT, numericValue)
                amountText.contains("₹") || amountText.contains("Rs") -> CashbackInfo(CashbackType.AMOUNT, numericValue, "INR")
                numericValue <= 100 && (amountText.contains("off", ignoreCase = true) || amountText.contains("discount", ignoreCase = true)) -> {
                    // Likely a percentage without % symbol
                    CashbackInfo(CashbackType.PERCENT, numericValue)
                }
                else -> CashbackInfo(CashbackType.AMOUNT, numericValue, "INR")
            }
            
            Pair(numericValue, cashbackInfo)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cashback amount: $amountText", e)
            Pair(0.0, CashbackInfo(CashbackType.AMOUNT, 0.0))
        }
    }

    private fun buildDescription(extractedFields: Map<FieldType, ExtractionCandidate>): String {
        val parts = mutableListOf<String>()
        
        extractedFields[FieldType.AMOUNT]?.text?.let { parts.add(it) }
        extractedFields[FieldType.STORE_NAME]?.text?.let { store ->
            if (parts.isEmpty()) {
                parts.add("Coupon for $store")
            }
        }
        
        return if (parts.isNotEmpty()) {
            parts.joinToString(" ")
        } else {
            "Coupon extracted using universal patterns"
        }
    }

    private fun calculateOverallConfidence(extractedFields: Map<FieldType, ExtractionCandidate>): Float {
        if (extractedFields.isEmpty()) return 0.0f
        
        val confidences = extractedFields.values.map { it.confidence }
        val averageConfidence = confidences.average().toFloat()
        
        // Boost confidence based on number of fields extracted
        val fieldBonus = when (extractedFields.size) {
            1 -> 0.0f
            2 -> 0.1f
            3 -> 0.2f
            else -> 0.3f
        }
        
        return (averageConfidence + fieldBonus).coerceAtMost(1.0f)
    }

    private fun createFallbackCoupon(): Coupon {
        return Coupon(
            id = 0,
            storeName = "Extraction Failed",
            description = "Universal extraction could not process this coupon",
            expiryDate = null,
            cashbackAmount = 0.0,
            redeemCode = null,
            imageUri = null,
            category = null,
            rating = null,
            status = "NEEDS_REVIEW",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
}

/**
 * Result of universal extraction
 */
data class UniversalExtractionResult(
    val coupon: Coupon,
    val confidence: Float,
    val extractedFields: Map<FieldType, ExtractionCandidate>,
    val allCandidates: Map<FieldType, List<ExtractionCandidate>>,
    val success: Boolean,
    val error: String? = null
)

/**
 * Statistics about extraction patterns and performance
 */
data class ExtractionStats(
    val patternStats: Map<FieldType, PatternFieldStats>,
    val featureImportance: Map<String, Float>,
    val totalPatternsLearned: Int
)

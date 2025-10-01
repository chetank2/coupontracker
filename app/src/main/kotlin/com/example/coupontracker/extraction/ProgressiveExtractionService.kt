package com.example.coupontracker.extraction

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.CashbackInfo
import com.example.coupontracker.data.model.CashbackType
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.util.IndianCurrencyParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progressive Extraction Service - Multi-pass extraction pipeline.
 * Implements 5 passes with graceful degradation:
 * 1. Structured Pattern Matching
 * 2. Semantic Analysis
 * 3. Heuristic Extraction
 * 4. Learned Patterns
 * 5. Conservative Defaults
 * 
 * NEVER returns "Error processing coupon" - always returns meaningful data.
 */
@Singleton
class ProgressiveExtractionService @Inject constructor(
    private val structuredExtractor: StructuredFieldExtractor,
    private val semanticExtractor: SemanticFieldExtractor,
    private val heuristicExtractor: HeuristicFieldExtractor,
    private val learnedPatternEngine: PatternLearningEngine,
    private val defaultProvider: DefaultFieldProvider
) {
    companion object {
        private const val TAG = "ProgressiveExtractionService"
        
        // Define critical fields that should be extracted
        private val CRITICAL_FIELDS = setOf(FieldType.STORE_NAME, FieldType.DESCRIPTION)
        private val IMPORTANT_FIELDS = setOf(FieldType.AMOUNT, FieldType.COUPON_CODE)
    }
    
    /**
     * Extract coupon using progressive refinement pipeline
     */
    suspend fun extractCoupon(
        image: Bitmap,
        ocrText: String,
        ocrBlocks: List<TextBlock> = emptyList(),
        imageUri: String
    ): ProgressiveExtractionResult = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "Starting progressive extraction pipeline")
        Log.d(TAG, "OCR text length: ${ocrText.length} characters")
        
        val context = ExtractionContext(
            imageUri = imageUri,
            ocrText = ocrText,
            ocrBlocks = ocrBlocks,
            metadata = emptyMap(),
            attempts = mutableListOf()
        )
        
        val extractedFields = mutableMapOf<FieldType, FieldCandidate>()
        
        // ====== PASS 1: Structured Pattern Matching ======
        Log.d(TAG, "▶ Pass 1: Structured extraction")
        val structuredResults = structuredExtractor.detectFieldsStructured(context, minConfidence = 0.4f)
        mergeResults(extractedFields, structuredResults)
        logPassResults(1, extractedFields)
        
        // Check if we have all critical fields
        var missingCritical = CRITICAL_FIELDS - extractedFields.keys
        if (missingCritical.isEmpty()) {
            Log.d(TAG, "✅ All critical fields found in Pass 1")
            return@withContext buildFinalResult(context, extractedFields, image, imageUri)
        }
        
        // ====== PASS 2: Semantic Analysis ======
        Log.d(TAG, "▶ Pass 2: Semantic analysis for ${missingCritical.size} missing critical fields")
        val semanticResults = semanticExtractor.extractFieldsSemantic(context, missingCritical)
        mergeResults(extractedFields, semanticResults, replaceIfBetter = false)
        logPassResults(2, extractedFields)
        
        missingCritical = CRITICAL_FIELDS - extractedFields.keys
        if (missingCritical.isEmpty()) {
            Log.d(TAG, "✅ All critical fields found after Pass 2")
            return@withContext buildFinalResult(context, extractedFields, image, imageUri)
        }
        
        // ====== PASS 3: Heuristic Extraction ======
        Log.d(TAG, "▶ Pass 3: Heuristic extraction for ${missingCritical.size} still-missing fields")
        val heuristicResults = heuristicExtractor.extractFieldsHeuristic(context, missingCritical)
        mergeResults(extractedFields, heuristicResults, replaceIfBetter = false)
        logPassResults(3, extractedFields)
        
        // ====== PASS 4: Learned Patterns ======
        // TODO: Enable learned patterns once LearnedPattern integration is complete
        Log.d(TAG, "▶ Pass 4: Learned patterns (skipped for now)")
        
        // ====== PASS 5: Conservative Defaults ======
        Log.d(TAG, "▶ Pass 5: Applying conservative defaults")
        val finalMissing = FieldType.values().toSet() - extractedFields.keys
        val defaults = defaultProvider.provideDefaults(context, finalMissing)
        for ((fieldType, candidate) in defaults) {
            if (fieldType !in extractedFields) {
                extractedFields[fieldType] = candidate
            }
        }
        logPassResults(5, extractedFields)
        
        Log.d(TAG, "✅ Extraction complete after ${context.attempts.size} passes")
        buildFinalResult(context, extractedFields, image, imageUri)
    }
    
    /**
     * Merge results from a pass into the target map
     */
    private fun mergeResults(
        target: MutableMap<FieldType, FieldCandidate>,
        source: Map<FieldType, List<FieldCandidate>>,
        replaceIfBetter: Boolean = true
    ) {
        for ((fieldType, candidates) in source) {
            if (candidates.isEmpty()) continue
            
            val bestCandidate = candidates.maxByOrNull { it.confidence } ?: continue
            
            if (fieldType !in target) {
                target[fieldType] = bestCandidate
            } else if (replaceIfBetter && bestCandidate.confidence > target[fieldType]!!.confidence) {
                target[fieldType] = bestCandidate
                Log.d(TAG, "Replaced $fieldType with better candidate (${bestCandidate.confidence} > ${target[fieldType]!!.confidence})")
            }
        }
    }
    
    /**
     * Log results after each pass
     */
    private fun logPassResults(passNumber: Int, fields: Map<FieldType, FieldCandidate>) {
        Log.d(TAG, "Pass $passNumber results: ${fields.size} fields extracted")
        for ((fieldType, candidate) in fields) {
            Log.d(TAG, "  $fieldType: '${candidate.value.take(50)}...' (conf: ${candidate.confidence}, source: ${candidate.source})")
        }
    }
    
    /**
     * Build final result with coupon object
     */
    private fun buildFinalResult(
        context: ExtractionContext,
        extractedFields: Map<FieldType, FieldCandidate>,
        image: Bitmap,
        imageUri: String
    ): ProgressiveExtractionResult {
        
        val coupon = buildCouponFromFields(extractedFields, imageUri, context)
        val overallConfidence = extractedFields.values.map { it.confidence }.average().toFloat()
        
        Log.d(TAG, "Built coupon: store='${coupon.storeName}', desc='${coupon.description.take(50)}...', amount=${coupon.cashbackAmount}")
        
        return ProgressiveExtractionResult(
            coupon = coupon,
            confidence = overallConfidence,
            extractedFields = extractedFields,
            success = true,
            extractionAttempts = context.attempts,
            passesUsed = context.attempts.size
        )
    }
    
    /**
     * Build Coupon object from extracted fields
     */
    private fun buildCouponFromFields(
        extractedFields: Map<FieldType, FieldCandidate>,
        imageUri: String,
        context: ExtractionContext
    ): Coupon {
        
        // Store Name
        val storeName = extractedFields[FieldType.STORE_NAME]?.value ?: "Unknown Store"
        
        // Description: ALWAYS use OCR text as fallback (never "Error processing coupon")
        val description = extractedFields[FieldType.DESCRIPTION]?.value 
            ?: context.ocrText.take(200).trim().ifBlank { "Coupon offer" }
        
        // Redeem Code
        val redeemCode = extractedFields[FieldType.COUPON_CODE]?.value
            ?.takeIf { it != "NO_CODE_NEEDED" }
        
        // Expiry Date
        val expiryDate = extractedFields[FieldType.EXPIRY_DATE]?.value?.let { parseDate(it) }
        
        // Amount and Cashback Info
        val (cashbackAmount, cashbackInfo) = extractedFields[FieldType.AMOUNT]?.value?.let {
            parseCashbackAmount(it)
        } ?: Pair(0.0, CashbackInfo(CashbackType.AMOUNT, 0.0))
        
        return Coupon(
            id = 0,
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode,
            imageUri = imageUri,
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency,
            offerText = extractedFields[FieldType.AMOUNT]?.value,
            category = null,
            rating = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
    
    /**
     * Parse date string to Date object
     */
    private fun parseDate(dateString: String): Date? {
        // Try ISO format first (from relative date conversion)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        try {
            return isoFormat.parse(dateString)
        } catch (e: Exception) {
            // Not ISO format
        }
        
        // Try common Indian date formats
        val formats = listOf(
            "dd/MM/yyyy",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "dd MMMM yyyy"
        )
        
        for (formatStr in formats) {
            try {
                val format = SimpleDateFormat(formatStr, Locale.US)
                return format.parse(dateString)
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        Log.w(TAG, "Could not parse date: $dateString")
        return null
    }
    
    /**
     * Parse cashback amount string to amount and type
     */
    private fun parseCashbackAmount(amountText: String): Pair<Double, CashbackInfo> {
        try {
            // Check for percentage
            if (amountText.contains("%")) {
                val percentPattern = Regex("""([0-9]+(?:\.[0-9]{1,2})?)""")
                val match = percentPattern.find(amountText)
                val value = match?.value?.toDoubleOrNull() ?: 0.0
                return Pair(value, CashbackInfo(CashbackType.PERCENT, value))
            }
            
            // Check for rupee amount
            val numericValue = IndianCurrencyParser.parseAmount(amountText) ?: 0.0
            
            // Determine type based on keywords
            val cashbackInfo = when {
                amountText.contains("cashback", ignoreCase = true) -> 
                    CashbackInfo(CashbackType.AMOUNT, numericValue, "INR")
                amountText.contains("off", ignoreCase = true) || 
                amountText.contains("discount", ignoreCase = true) -> 
                    CashbackInfo(CashbackType.AMOUNT, numericValue, "INR")
                else -> 
                    CashbackInfo(CashbackType.AMOUNT, numericValue, "INR")
            }
            
            return Pair(numericValue, cashbackInfo)
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cashback amount: $amountText", e)
            return Pair(0.0, CashbackInfo(CashbackType.AMOUNT, 0.0))
        }
    }
}

/**
 * Result of progressive extraction
 */
data class ProgressiveExtractionResult(
    val coupon: Coupon,
    val confidence: Float,
    val extractedFields: Map<FieldType, FieldCandidate>,
    val success: Boolean,
    val extractionAttempts: List<ExtractionAttempt>,
    val passesUsed: Int,
    val error: String? = null
)


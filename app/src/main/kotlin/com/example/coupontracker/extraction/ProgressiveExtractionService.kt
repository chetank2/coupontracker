package com.example.coupontracker.extraction

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.CashbackInfo
import com.example.coupontracker.data.model.CashbackType
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.IndianCurrencyParser
import com.example.coupontracker.util.IndianDateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progressive Extraction Service - Multi-pass extraction pipeline.
 * MiniCPM-FIRST STRATEGY: Uses vision AI as primary method, patterns as fallback.
 * 
 * NEW ORDER (October 2, 2025):
 * 1. MiniCPM Vision AI (PRIMARY - if available and high confidence, stop here)
 * 2. Structured Pattern Matching (fallback/supplement)
 * 3. Semantic Analysis (refinement)
 * 4. Learned Patterns (database)
 * 5. Heuristic Extraction (last resort)
 * 6. Conservative Defaults (minimal info)
 * 
 * CRITICAL: Learning ALWAYS runs after extraction, regardless of which pass succeeded.
 * NEVER returns "Error processing coupon" - always returns meaningful data.
 */
@Singleton
class ProgressiveExtractionService @Inject constructor(
    private val structuredExtractor: StructuredFieldExtractor,
    private val semanticExtractor: SemanticFieldExtractor,
    private val heuristicExtractor: HeuristicFieldExtractor,
    private val learnedPatternEngine: PatternLearningEngine,
    private val defaultProvider: DefaultFieldProvider,
    private val llmService: com.example.coupontracker.util.LocalLlmOcrService? = null,
    private val extractionLearningIntegration: com.example.coupontracker.learning.ExtractionLearningIntegration? = null
) {
    // V2: Validation components for multi-coupon extraction
    private val confidenceScorer = ConfidenceScorer()
    private val extractionValidator = ExtractionValidator(confidenceScorer)
    
    companion object {
        private const val TAG = "ProgressiveExtractionService"
        
        // Define critical fields that should be extracted
        private val CRITICAL_FIELDS = setOf(FieldType.STORE_NAME, FieldType.DESCRIPTION)
        private val IMPORTANT_FIELDS = setOf(FieldType.AMOUNT, FieldType.COUPON_CODE)
    }
    
    /**
     * Extract coupon using progressive refinement pipeline
     * NEW: MiniCPM-FIRST STRATEGY
     */
    suspend fun extractCoupon(
        androidContext: Context,
        image: Bitmap,
        ocrText: String,
        ocrBlocks: List<TextBlock> = emptyList(),
        imageUri: String,
        captureTimestamp: Date? = null  // FIXED: Accept timestamp as parameter
    ): ProgressiveExtractionResult = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "🚀 Starting MiniCPM-FIRST extraction pipeline")
        Log.d(TAG, "OCR text length: ${ocrText.length} characters")
        
        // FIXED: Use provided timestamp if available, otherwise try to extract from URI
        val effectiveCaptureTimestamp = captureTimestamp ?: try {
            ImageMetadataExtractor.extractCaptureTimestamp(androidContext, Uri.parse(imageUri))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract capture timestamp: ${e.message}")
            null
        }
        
        if (effectiveCaptureTimestamp != null) {
            Log.d(TAG, "📸 Screenshot timestamp: $effectiveCaptureTimestamp (${if (captureTimestamp != null) "provided" else "extracted"})")
        } else {
            Log.w(TAG, "⚠️ No screenshot timestamp found, relative dates will use current time")
        }
        
        // CRITICAL: Clean OCR text to remove UI chrome (battery, time, status bar)
        val cleanedOcr = com.example.coupontracker.util.OcrTextCleaner.cleanOcrText(ocrText)
        val finalOcr = cleanedOcr.ifBlank { ocrText }  // Fallback if cleaning too aggressive
        
        Log.d(TAG, "OCR cleaning: ${ocrText.length} → ${finalOcr.length} chars")
        
        val context = ExtractionContext(
            imageUri = imageUri,
            ocrText = finalOcr,  // Use cleaned OCR text
            ocrBlocks = ocrBlocks,
            metadata = emptyMap(),
            attempts = mutableListOf(),
            captureTimestamp = effectiveCaptureTimestamp
        )
        
        val extractedFields = mutableMapOf<FieldType, FieldCandidate>()
        
        // ====== PASS 1: MiniCPM Vision AI (PRIMARY METHOD) ======
        Log.d(TAG, "▶ Pass 1: MiniCPM Vision AI (PRIMARY extraction method)")
        var miniCpmConfidence = 0f
        var passesUsed = 1
        
        if (llmService != null) {
            try {
                Log.d(TAG, "✅ MiniCPM LLM available - using vision AI")
                val llmInfo = llmService.processCouponImage(image, effectiveCaptureTimestamp)
                
                if (llmInfo != null) {
                    // Convert ALL fields from MiniCPM (not just missing ones)
                    val llmResults = convertCouponInfoToFieldCandidates(llmInfo, FieldType.values().toSet())
                    mergeResults(extractedFields, llmResults, replaceIfBetter = true)
                    
                    miniCpmConfidence = calculateOverallConfidence(extractedFields)
                    Log.d(TAG, "  MiniCPM extracted ${extractedFields.size} fields (confidence: $miniCpmConfidence)")
                    logPassResults(1, extractedFields)
                    
                    // HIGH CONFIDENCE? We're done! 🎯
                    if (miniCpmConfidence >= 0.85f && CRITICAL_FIELDS.all { it in extractedFields }) {
                        Log.d(TAG, "✅ HIGH confidence from MiniCPM (${miniCpmConfidence}) - stopping here!")
                        return@withContext finishExtraction(context, extractedFields, image, imageUri, passesUsed, "MiniCPM Vision AI")
                    }
                    
                    // Medium confidence - continue to supplement with patterns
                    Log.d(TAG, "  Medium confidence from MiniCPM - supplementing with pattern-based extraction")
                } else {
                    Log.w(TAG, "⚠️  MiniCPM returned null - falling back to patterns")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ MiniCPM error: ${e.message} - falling back to patterns", e)
            }
        } else {
            Log.w(TAG, "⚠️  MiniCPM LLM NOT available - using pattern-based extraction")
        }
        
        // ====== PASS 2: Structured Pattern Matching (Fallback/Supplement) ======
        Log.d(TAG, "▶ Pass 2: Structured pattern extraction (supplement)")
        passesUsed++
        val structuredResults = structuredExtractor.detectFieldsStructured(context, minConfidence = 0.4f)
        // Only merge if it improves confidence (don't replace good MiniCPM results)
        mergeResults(extractedFields, structuredResults, replaceIfBetter = false)
        logPassResults(2, extractedFields)
        
        // ====== PASS 3: Semantic Analysis (Refinement) ======
        val stillMissing = CRITICAL_FIELDS - extractedFields.keys
        if (stillMissing.isNotEmpty()) {
            Log.d(TAG, "▶ Pass 3: Semantic analysis for ${stillMissing.size} missing critical fields")
            passesUsed++
            val semanticResults = semanticExtractor.extractFieldsSemantic(context, stillMissing)
            mergeResults(extractedFields, semanticResults, replaceIfBetter = false)
            logPassResults(3, extractedFields)
        }
        
        // ====== PASS 4: Learned Patterns ======
        val remainingMissing = FieldType.values().toSet() - extractedFields.keys
        if (remainingMissing.isNotEmpty()) {
            Log.d(TAG, "▶ Pass 4: Applying learned patterns for ${remainingMissing.size} fields")
            passesUsed++
            try {
                // Create universal context for pattern learning (adapter)
                val universalContext = com.example.coupontracker.universal.ExtractionContext(
                    brandHint = extractedFields[FieldType.STORE_NAME]?.value
                )
                
                // Query learned patterns from database
                val learnedResults = mutableMapOf<FieldType, List<FieldCandidate>>()
                for (fieldType in remainingMissing) {
                    val patterns = learnedPatternEngine.getRelevantPatterns(fieldType, universalContext)
                    if (patterns.isNotEmpty()) {
                        val candidates = patterns.map { learnedPattern ->
                            FieldCandidate(
                                value = extractValueUsingPattern(learnedPattern.pattern, context.ocrText),
                                confidence = learnedPattern.confidence,
                                source = "learned_pattern",
                                context = "Pattern: ${learnedPattern.pattern} (conf: ${learnedPattern.confidence})"
                            )
                        }.filter { it.value.isNotBlank() }
                        
                        if (candidates.isNotEmpty()) {
                            learnedResults[fieldType] = candidates
                        }
                    }
                }
                
                if (learnedResults.isNotEmpty()) {
                    mergeResults(extractedFields, learnedResults, replaceIfBetter = false)
                    Log.d(TAG, "  Learned patterns found ${learnedResults.size} fields")
                } else {
                    Log.d(TAG, "  No applicable learned patterns found")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error applying learned patterns", e)
            }
            logPassResults(4, extractedFields)
        }
        
        // ====== PASS 5: Heuristic Extraction (Last Resort) ======
        val finalMissing = CRITICAL_FIELDS - extractedFields.keys
        if (finalMissing.isNotEmpty()) {
            Log.d(TAG, "▶ Pass 5: Heuristic extraction for ${finalMissing.size} still-missing fields")
            passesUsed++
            val heuristicResults = heuristicExtractor.extractFieldsHeuristic(context, finalMissing)
            mergeResults(extractedFields, heuristicResults, replaceIfBetter = false)
            logPassResults(5, extractedFields)
        }
        
        // ====== PASS 6: Conservative Defaults ======
        Log.d(TAG, "▶ Pass 6: Applying conservative defaults")
        passesUsed++
        val stillNeedDefaults = FieldType.values().toSet() - extractedFields.keys
        val defaults = defaultProvider.provideDefaults(context, stillNeedDefaults)
        for ((fieldType, candidate) in defaults) {
            if (fieldType !in extractedFields) {
                extractedFields[fieldType] = candidate
            }
        }
        logPassResults(6, extractedFields)
        
        // Determine primary method used
        val primaryMethod = if (miniCpmConfidence > 0.4f) {
            "MiniCPM Vision AI + Patterns"
        } else {
            "Pattern-based"
        }
        
        return@withContext finishExtraction(context, extractedFields, image, imageUri, passesUsed, primaryMethod)
    }
    
    /**
     * Convert CouponInfo from LLM to FieldCandidates for specific missing fields
     */
    private fun convertCouponInfoToFieldCandidates(
        couponInfo: com.example.coupontracker.util.CouponInfo,
        missingFields: Set<FieldType>
    ): Map<FieldType, List<FieldCandidate>> {
        val results = mutableMapOf<FieldType, MutableList<FieldCandidate>>()
        
        if (FieldType.STORE_NAME in missingFields && couponInfo.storeName.isNotBlank()) {
            results.getOrPut(FieldType.STORE_NAME) { mutableListOf() }.add(
                FieldCandidate(
                    value = couponInfo.storeName,
                    confidence = 0.75f,
                    source = "minicpm_llm",
                    context = "Extracted by MiniCPM LLM"
                )
            )
        }
        
        if (FieldType.DESCRIPTION in missingFields && couponInfo.description.isNotBlank()) {
            results.getOrPut(FieldType.DESCRIPTION) { mutableListOf() }.add(
                FieldCandidate(
                    value = couponInfo.description,
                    confidence = 0.75f,
                    source = "minicpm_llm",
                    context = "Extracted by MiniCPM LLM"
                )
            )
        }
        
        if (FieldType.COUPON_CODE in missingFields && couponInfo.redeemCode != null) {
            results.getOrPut(FieldType.COUPON_CODE) { mutableListOf() }.add(
                FieldCandidate(
                    value = couponInfo.redeemCode,
                    confidence = 0.75f,
                    source = "minicpm_llm",
                    context = "Extracted by MiniCPM LLM"
                )
            )
        }
        
        if (FieldType.EXPIRY_DATE in missingFields && couponInfo.expiryDate != null) {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            results.getOrPut(FieldType.EXPIRY_DATE) { mutableListOf() }.add(
                FieldCandidate(
                    value = dateFormat.format(couponInfo.expiryDate),
                    confidence = 0.75f,
                    source = "minicpm_llm",
                    context = "Extracted by MiniCPM LLM"
                )
            )
        }
        
        return results
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
        val cashbackAmount = 0.0
        val cashbackInfo = CashbackInfo(CashbackType.AMOUNT, 0.0)
        
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
            offerText = null,
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
    internal fun parseDate(dateString: String): Date? {
        val sanitized = dateString.trim()
            .replace(Regex("(?i)(\\d{1,2})(st|nd|rd|th)"), "\\1")
            .replace(",", "")
            .replace(Regex("\\s+"), " ")

        // Try ISO format first (from relative date conversion)
        val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        try {
            return isoFormat.parse(sanitized)
        } catch (ignored: Exception) {
            // Not ISO format
        }

        // Try common Indian date formats with both single and double digit days
        val formats = listOf(
            "dd/MM/yyyy",
            "d/M/yyyy",
            "dd-MM-yyyy",
            "d-M-yyyy",
            "dd MMM yyyy",
            "d MMM yyyy",
            "dd MMMM yyyy",
            "d MMMM yyyy"
        )

        for (formatStr in formats) {
            try {
                val format = SimpleDateFormat(formatStr, Locale.US)
                return format.parse(sanitized)
            } catch (ignored: Exception) {
                // Try next format
            }
        }

        // Fallback to the shared Indian date parser for harder cases
        val fallback = IndianDateParser.parseExpiryIST(dateString).date
        if (fallback != null) {
            val zone = ZoneId.systemDefault()
            return Date.from(fallback.atStartOfDay(zone).toInstant())
        }

        Log.w(TAG, "Could not parse date: $dateString")
        return null
    }
    
    /**
     * Extract value from text using a learned pattern (regex)
     */
    private fun extractValueUsingPattern(pattern: String, text: String): String {
        return try {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            val match = regex.find(text)
            match?.value ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Error applying learned pattern: $pattern", e)
            ""
        }
    }
    
    /**
     * Calculate overall confidence from extracted fields (weighted average)
     */
    private fun calculateOverallConfidence(fields: Map<FieldType, FieldCandidate>): Float {
        if (fields.isEmpty()) return 0f
        
        // Weighted confidence: critical fields count more
        val weights = mapOf(
            FieldType.STORE_NAME to 2.0f,      // Critical
            FieldType.DESCRIPTION to 2.0f,     // Critical
            FieldType.COUPON_CODE to 1.5f,     // Important
            FieldType.AMOUNT to 1.5f,          // Important
            FieldType.EXPIRY_DATE to 1.0f      // Nice to have
        )
        
        val weightedSum = fields.entries.sumOf { (type, candidate) ->
            (candidate.confidence * (weights[type] ?: 1.0f)).toDouble()
        }
        
        val totalWeight = fields.keys.sumOf { (weights[it] ?: 1.0f).toDouble() }
        
        return (weightedSum / totalWeight).toFloat()
    }
    
    /**
     * Finish extraction: build result + validate + ALWAYS trigger learning
     * V2: Enhanced with confidence scoring and validation
     * This ensures learning runs regardless of which pass succeeded
     */
    private suspend fun finishExtraction(
        context: ExtractionContext,
        extractedFields: Map<FieldType, FieldCandidate>,
        image: Bitmap,
        imageUri: String,
        passesUsed: Int,
        primaryMethod: String
    ): ProgressiveExtractionResult {
        
        val overallConfidence = calculateOverallConfidence(extractedFields)
        
        Log.d(TAG, """
            ┌─────────────────────────────────────────────────────────
            │ EXTRACTION COMPLETE
            ├─────────────────────────────────────────────────────────
            │ Method: $primaryMethod
            │ Confidence: $overallConfidence
            │ Passes Used: $passesUsed
            │ 
            │ Fields Extracted:
            ${extractedFields.entries.joinToString("\n") { (type, candidate) ->
            "│   - $type: '${candidate.value.take(30)}...' (${candidate.confidence}, ${candidate.source})"
            }}
            │ 
            │ Success: ${CRITICAL_FIELDS.all { it in extractedFields }}
            └─────────────────────────────────────────────────────────
        """.trimIndent())
        
        val result = buildFinalResult(context, extractedFields, image, imageUri)
        
        // V2: Validate extracted coupon with confidence scoring
        try {
            val validationResult = extractionValidator.validate(result.coupon)
            
            Log.d(TAG, """
                ┌─────────────────────────────────────────────────────────
                │ VALIDATION RESULT
                ├─────────────────────────────────────────────────────────
                │ Quality: ${validationResult.extractionQuality}
                │ Confidence: ${validationResult.validationResult.overallConfidence}
                │ Action: ${validationResult.validationResult.suggestedAction}
                │ 
                │ Recommendations:
                ${validationResult.actionableRecommendations.joinToString("\n") { "│   - $it" }}
                └─────────────────────────────────────────────────────────
            """.trimIndent())
            
            // Log warnings if validation suggests issues
            if (validationResult.validationResult.warnings.isNotEmpty()) {
                Log.w(TAG, "Validation warnings: ${validationResult.validationResult.warnings.joinToString("; ")}")
            }
            
            // If extraction quality is too poor, log recommendation but still return result
            // (Let caller decide whether to use it or retry)
            if (validationResult.extractionQuality == ExtractionValidator.ExtractionQuality.FAILED) {
                Log.e(TAG, "Extraction quality FAILED - consider retrying with different strategy")
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Error during validation (non-critical): ${e.message}", e)
        }
        
        // CRITICAL: ALWAYS trigger learning (no matter which pass succeeded)
        extractionLearningIntegration?.let { learning ->
            try {
                learning.learnFromExtraction(result, context)
            } catch (e: Exception) {
                Log.w(TAG, "Error in learning integration", e)
            }
        }
        
        return result
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


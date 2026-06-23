package com.example.coupontracker.universal

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.ProgressiveExtractionResult
import com.example.coupontracker.extraction.ProgressiveExtractionService
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.validation.CouponFieldBundleValidator
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.extraction.validation.SpatialFieldConsistencyValidator
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.util.IndianDateParser
import com.example.coupontracker.util.OcrTextCleaner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Universal extraction orchestrator that blends deterministic patterns,
 * LLM-driven semantics, learned patterns, heuristics, and contextual defaults.
 * The cleaned OCR transcript is preserved end-to-end so every fallback stage
 * has access to the same canonical text.
 */
@Singleton
class UniversalExtractionService @Inject constructor(
    @ApplicationContext private val androidContext: Context,
    private val fieldDetector: UniversalFieldDetector,
    private val patternLearner: PatternLearningEngine,
    private val confidenceScorer: AdaptiveConfidenceScorer,
    private val progressiveExtractionService: ProgressiveExtractionService
) {
    private val spatialValidator = SpatialFieldConsistencyValidator()
    private val bundleValidator = CouponFieldBundleValidator(spatialValidator)

    companion object {
        private const val TAG = "UniversalExtractionService"
        private const val DEFAULT_THRESHOLD = 0.35f
        private val FIELD_THRESHOLDS = mapOf(
            FieldType.STORE_NAME to 0.45f,
            FieldType.DESCRIPTION to 0.40f,
            FieldType.COUPON_CODE to 0.55f,
            FieldType.EXPIRY_DATE to 0.50f,
            FieldType.AMOUNT to 0.42f
        )
        private val SUPPORTED_FIELDS = setOf(
            FieldType.STORE_NAME,
            FieldType.DESCRIPTION,
            FieldType.COUPON_CODE,
            FieldType.EXPIRY_DATE,
            FieldType.AMOUNT
        )
        private val GENERIC_STORE_NAMES = setOf(
            "store",
            "shop",
            "brand",
            "seller",
            "minimum",
            "minimum order",
            "minimum order value",
            "order value",
            "value",
            "validity",
            "details"
        )
        private val GENERIC_DESCRIPTION_PHRASES = setOf(
            "coupon offer",
            "coupon extracted",
            "no description",
            "error processing coupon"
        )
    }

    suspend fun extractCoupon(
        image: Bitmap,
        ocrText: String,
        context: ExtractionContext = ExtractionContext(),
        ocrBlocks: List<TextBlock> = emptyList()
    ): UniversalExtractionResult = withContext(Dispatchers.Default) {
        val cleanedOcr = OcrTextCleaner.cleanOcrText(ocrText).trim().ifBlank { ocrText }
        val contextWithText = context.copy(
            cleanedOcrText = cleanedOcr,
            originalOcrText = ocrText,
            ocrBlocks = ocrBlocks.ifEmpty { context.ocrBlocks }
        )

        val candidateBuckets = mutableMapOf<FieldType, MutableList<ExtractionCandidate>>()
        val imageUri = image.toString()

        return@withContext try {
            Log.d(TAG, "Starting universal extraction with cleaned OCR length=${cleanedOcr.length}")
            runDeterministicPass(image, cleanedOcr, contextWithText, candidateBuckets)
            runProgressivePass(image, cleanedOcr, imageUri, contextWithText, candidateBuckets)
            runLearnedPatternPass(cleanedOcr, contextWithText, candidateBuckets)
            runHeuristicPass(cleanedOcr, contextWithText, candidateBuckets)
            runDefaultPass(cleanedOcr, contextWithText, candidateBuckets)

            val allCandidates = candidateBuckets.mapValues { it.value.toList() }
            val blendedFields = blendCandidates(allCandidates, cleanedOcr, contextWithText)
            val coupon = buildCouponFromFields(blendedFields, imageUri, cleanedOcr, contextWithText)
            val confidence = calculateOverallConfidence(blendedFields, candidateBuckets)
            val fieldCandidates = blendedFields.mapValues { (_, candidate) ->
                FieldCandidate(
                    value = candidate.text,
                    confidence = candidate.confidence,
                    source = candidate.source.name,
                    context = candidate.context["source"] ?: candidate.context["pass"]
                )
            }
            val bundleValidation = bundleValidator.validate(
                bundle = FieldValueBundle(
                    storeName = coupon.storeName,
                    description = coupon.description,
                    redeemCode = coupon.redeemCode,
                    expiryDateText = blendedFields[FieldType.EXPIRY_DATE]?.text
                ),
                fields = fieldCandidates,
                rawOcrText = cleanedOcr,
                ocrBlocks = contextWithText.ocrBlocks,
                imageHeight = image.height
            )
            val spatialResult = bundleValidation.spatialResult
            if (bundleValidation.needsAttention) {
                Log.w(TAG, "Final bundle validation needs review: ${bundleValidation.reason}")
                return@withContext UniversalExtractionResult(
                    coupon = coupon.copy(needsAttention = true),
                    confidence = confidence.coerceAtMost(if (bundleValidation.trusted) 0.6f else 0.35f),
                    extractedFields = blendedFields,
                    allCandidates = allCandidates,
                    success = bundleValidation.trusted && blendedFields.isNotEmpty(),
                    error = bundleValidation.reason
                )
            }

            if (!spatialResult.consistent) {
                Log.w(TAG, "Spatial validation failed: ${spatialResult.reason}")
                return@withContext UniversalExtractionResult(
                    coupon = coupon.copy(needsAttention = true),
                    confidence = confidence.coerceAtMost(0.35f),
                    extractedFields = blendedFields,
                    allCandidates = allCandidates,
                    success = false,
                    error = spatialResult.reason
                )
            }

            UniversalExtractionResult(
                coupon = coupon,
                confidence = confidence,
                extractedFields = blendedFields,
                allCandidates = allCandidates,
                success = blendedFields.isNotEmpty()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Universal extraction failed", e)
            UniversalExtractionResult(
                coupon = createReviewOnlyCoupon(cleanedOcr),
                confidence = 0f,
                extractedFields = emptyMap(),
                allCandidates = candidateBuckets.mapValues { it.value.toList() },
                success = false,
                error = e.message
            )
        }
    }

    private suspend fun runDeterministicPass(
        image: Bitmap,
        cleanedOcr: String,
        context: ExtractionContext,
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>
    ) {
        val deterministic = fieldDetector.detectFields(image, cleanedOcr, context)
        appendCandidates(accumulator, deterministic, "deterministic_patterns")
    }

    private suspend fun runProgressivePass(
        image: Bitmap,
        cleanedOcr: String,
        imageUri: String,
        context: ExtractionContext,
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>
    ) {
        val progressiveResult = try {
            progressiveExtractionService.extractCoupon(
                androidContext = androidContext,
                image = image,
                ocrText = cleanedOcr,
                ocrBlocks = context.ocrBlocks,
                imageUri = imageUri
            )
        } catch (e: Exception) {
            Log.w(TAG, "Progressive pipeline failed: ${e.message}")
            return
        }

        val progressiveCandidates = convertProgressiveCandidates(progressiveResult)
        val rescored = progressiveCandidates.mapValues { (field, candidates) ->
            candidates.map { scoreCandidate(field, it) }
        }
        appendCandidates(accumulator, rescored, "progressive_pipeline")
    }

    private suspend fun runLearnedPatternPass(
        cleanedOcr: String,
        context: ExtractionContext,
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>
    ) {
        val missingFields = SUPPORTED_FIELDS.filter { accumulator[it].isNullOrEmpty() }
        if (missingFields.isEmpty()) return

        val learned = mutableMapOf<FieldType, List<ExtractionCandidate>>()
        for (field in missingFields) {
            val patterns = patternLearner.getRelevantPatterns(field, context)
            if (patterns.isEmpty()) continue

            val candidates = patterns.mapNotNull { learnedPattern ->
                val extracted = applyLearnedPattern(learnedPattern.pattern, cleanedOcr)
                if (extracted.isBlank()) {
                    null
                } else {
                    ExtractionCandidate(
                        text = extracted,
                        confidence = learnedPattern.confidence,
                        source = ExtractionSource.LEARNED_PATTERN,
                        context = mapOf(
                            "pattern" to learnedPattern.pattern,
                            "pass" to "learned_pattern"
                        )
                    )
                }
            }

            if (candidates.isNotEmpty()) {
                learned[field] = candidates.map { scoreCandidate(field, it) }
            }
        }

        appendCandidates(accumulator, learned, "learned_patterns")
    }

    private suspend fun runHeuristicPass(
        cleanedOcr: String,
        context: ExtractionContext,
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>
    ) {
        val missingFields = SUPPORTED_FIELDS.filter { accumulator[it].isNullOrEmpty() }.toSet()
        if (missingFields.isEmpty()) return

        val heuristics = mutableMapOf<FieldType, List<ExtractionCandidate>>()

        if (FieldType.EXPIRY_DATE in missingFields) {
            val expirySourceText = listOfNotNull(
                cleanedOcr,
                context.originalOcrText
            ).joinToString("\n")
            val parseResult = IndianDateParser.extractExpiryFromText(
                expirySourceText,
                context.baseLocalDate()
            )
            if (parseResult.date != null) {
                val isoDate = parseResult.date.toString()
                val candidate = ExtractionCandidate(
                    text = isoDate,
                    confidence = parseResult.confidence,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf(
                        "reason" to parseResult.reason,
                        "pass" to "heuristic_expiry"
                    )
                )
                heuristics[FieldType.EXPIRY_DATE] = listOf(scoreCandidate(FieldType.EXPIRY_DATE, candidate))
            }
        }

        if (FieldType.AMOUNT in missingFields) {
            detectCompoundAmount(cleanedOcr)?.let { amountText ->
                val candidate = ExtractionCandidate(
                    text = amountText,
                    confidence = 0.52f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "heuristic_amount", "compound" to "true")
                )
                heuristics[FieldType.AMOUNT] = listOf(scoreCandidate(FieldType.AMOUNT, candidate))
            }
        }

        if (FieldType.COUPON_CODE in missingFields) {
            detectCouponCode(cleanedOcr)?.let { code ->
                val candidate = ExtractionCandidate(
                    text = code,
                    confidence = 0.55f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "heuristic_code")
                )
                heuristics[FieldType.COUPON_CODE] = listOf(scoreCandidate(FieldType.COUPON_CODE, candidate))
            }
        }

        if (FieldType.STORE_NAME in missingFields) {
            extractStoreFromContext(cleanedOcr, context)?.let { store ->
                val candidate = ExtractionCandidate(
                    text = store,
                    confidence = 0.48f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "heuristic_store")
                )
                heuristics[FieldType.STORE_NAME] = listOf(scoreCandidate(FieldType.STORE_NAME, candidate))
            }
        }

        if (FieldType.DESCRIPTION in missingFields) {
            extractMeaningfulSnippet(cleanedOcr)?.let { snippet ->
                val candidate = ExtractionCandidate(
                    text = snippet,
                    confidence = 0.45f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "heuristic_description")
                )
                heuristics[FieldType.DESCRIPTION] = listOf(scoreCandidate(FieldType.DESCRIPTION, candidate))
            }
        }

        appendCandidates(accumulator, heuristics, "heuristic_fallbacks")
    }

    private suspend fun runDefaultPass(
        cleanedOcr: String,
        context: ExtractionContext,
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>
    ) {
        val defaults = mutableMapOf<FieldType, List<ExtractionCandidate>>()

        if (accumulator[FieldType.DESCRIPTION].isNullOrEmpty()) {
            extractMeaningfulSnippet(cleanedOcr)?.let { snippet ->
                val candidate = ExtractionCandidate(
                    text = snippet,
                    confidence = 0.38f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "context_default")
                )
                defaults[FieldType.DESCRIPTION] = listOf(scoreCandidate(FieldType.DESCRIPTION, candidate))
            }
        }

        if (accumulator[FieldType.COUPON_CODE].isNullOrEmpty()) {
            if (cleanedOcr.contains("no code", ignoreCase = true)) {
                val candidate = ExtractionCandidate(
                    text = "NO_CODE_NEEDED",
                    confidence = 0.4f,
                    source = ExtractionSource.CONTEXT_CLUES,
                    context = mapOf("pass" to "context_default_code")
                )
                defaults[FieldType.COUPON_CODE] = listOf(scoreCandidate(FieldType.COUPON_CODE, candidate))
            }
        }

        appendCandidates(accumulator, defaults, "context_defaults")
    }

    private fun appendCandidates(
        accumulator: MutableMap<FieldType, MutableList<ExtractionCandidate>>,
        newCandidates: Map<FieldType, List<ExtractionCandidate>>,
        passName: String
    ) {
        for ((field, candidates) in newCandidates) {
            if (candidates.isEmpty()) continue
            val bucket = accumulator.getOrPut(field) { mutableListOf() }
            bucket.addAll(candidates)
            for (candidate in candidates) {
                Log.d(TAG, "Pass $passName produced $field='${candidate.text}' (conf=${candidate.confidence})")
            }
        }
    }

    private suspend fun scoreCandidate(
        fieldType: FieldType,
        candidate: ExtractionCandidate
    ): ExtractionCandidate {
        return try {
            val calibrated = confidenceScorer.scoreCandidate(candidate, fieldType)
            candidate.copy(confidence = calibrated)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to score candidate for $fieldType", e)
            candidate
        }
    }

    private fun blendCandidates(
        candidateBuckets: Map<FieldType, List<ExtractionCandidate>>,
        cleanedOcr: String,
        context: ExtractionContext
    ): Map<FieldType, ExtractionCandidate> {
        val blended = mutableMapOf<FieldType, ExtractionCandidate>()
        for ((fieldType, candidates) in candidateBuckets) {
            if (candidates.isEmpty()) continue
            val normalizedCounts = candidates.groupingBy { normalizeForField(fieldType, it.text) }.eachCount()
            var bestCandidate: ExtractionCandidate? = null
            var bestScore = 0f

            for (candidate in candidates.sortedByDescending { it.confidence }) {
                val normalized = normalizeForField(fieldType, candidate.text)
                val consensusBoost = ((normalizedCounts[normalized] ?: 1) - 1) * 0.08f
                val validated = applyFieldValidation(
                    fieldType = fieldType,
                    text = candidate.text,
                    baseConfidence = candidate.confidence + consensusBoost,
                    cleanedOcr = cleanedOcr,
                    context = context
                )
                val threshold = FIELD_THRESHOLDS[fieldType] ?: DEFAULT_THRESHOLD

                if (validated >= threshold && validated >= bestScore) {
                    bestCandidate = candidate.copy(confidence = validated.coerceAtMost(1f))
                    bestScore = validated
                } else if (bestCandidate == null && candidate.text.isNotBlank()) {
                    bestCandidate = candidate.copy(confidence = validated.coerceAtMost(1f))
                    bestScore = validated
                }
            }

            bestCandidate?.let { blended[fieldType] = it }
        }
        return blended
    }

    private fun calculateOverallConfidence(
        blendedFields: Map<FieldType, ExtractionCandidate>,
        accumulator: Map<FieldType, MutableList<ExtractionCandidate>>
    ): Float {
        if (blendedFields.isEmpty()) return 0f

        var weightedSum = 0f
        var totalWeight = 0f

        for ((field, candidate) in blendedFields) {
            val baseWeight = when (field) {
                FieldType.STORE_NAME -> 1.3f
                FieldType.DESCRIPTION -> 1.4f
                FieldType.COUPON_CODE -> 1.2f
                FieldType.EXPIRY_DATE -> 1.1f
                FieldType.AMOUNT -> 1.0f
                else -> 0.8f
            }
            val normalized = normalizeForField(field, candidate.text)
            val consensus = accumulator[field]
                ?.count { normalizeForField(field, it.text) == normalized }
                ?.coerceAtLeast(1) ?: 1
            val consensusBoost = (consensus - 1) * 0.05f
            val adjusted = (candidate.confidence + consensusBoost).coerceAtMost(1f)
            weightedSum += adjusted * baseWeight
            totalWeight += baseWeight
        }

        return (weightedSum / totalWeight).coerceIn(0f, 1f)
    }

    private fun applyFieldValidation(
        fieldType: FieldType,
        text: String,
        baseConfidence: Float,
        cleanedOcr: String,
        context: ExtractionContext
    ): Float {
        var confidence = baseConfidence
        when (fieldType) {
            FieldType.COUPON_CODE -> {
                val normalized = text.trim().uppercase(Locale.ROOT)
                if (normalized.contains("NO_CODE")) {
                    confidence = min(confidence, 0.45f)
                } else if (!normalized.matches(Regex("[A-Z0-9-]{4,}"))) {
                    confidence *= 0.6f
                }
            }
            FieldType.AMOUNT -> {
                val value = parseCompoundAmountValue(text)
                if (value == null || value <= 0.0) {
                    confidence *= 0.5f
                } else if (text.contains("+")) {
                    confidence = max(confidence, 0.55f)
                }
            }
            FieldType.EXPIRY_DATE -> {
                val baseDate = context.baseLocalDate()
                val parseResult = IndianDateParser.extractExpiryFromText(text, baseDate)
                if (parseResult.date != null) {
                    confidence = max(confidence, parseResult.confidence)
                } else {
                    val fallback = IndianDateParser.extractExpiryFromText(cleanedOcr, baseDate)
                    if (fallback.date == null) {
                        confidence *= 0.6f
                    }
                }
            }
            FieldType.STORE_NAME -> {
                if (isGenericStoreName(text)) {
                    confidence *= 0.5f
                }
            }
            FieldType.DESCRIPTION -> {
                if (isGenericDescription(text)) {
                    confidence *= 0.5f
                }
            }
            else -> {}
        }
        return confidence.coerceIn(0f, 1f)
    }

    private fun parseCompoundAmountValue(text: String): Double? {
        val normalized = text.lowercase(Locale.ROOT)
        val percentMatch = Regex("(\\d+(?:\\.\\d+)?)%").find(normalized)
        if (percentMatch != null) {
            return percentMatch.groupValues[1].toDoubleOrNull()
        }
        val amountMatch = Regex("(?:₹|rs\\.?|inr|usd|eur|gbp|\\$|€|£)?\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)").find(normalized)
        if (amountMatch != null) {
            return amountMatch.groupValues[1].replace(",", "").toDoubleOrNull()
        }
        return null
    }

    private fun normalizeForField(fieldType: FieldType, text: String): String {
        return when (fieldType) {
            FieldType.COUPON_CODE -> text.trim().uppercase(Locale.ROOT)
            FieldType.AMOUNT -> text.replace("\\s".toRegex(), "").lowercase(Locale.ROOT)
            FieldType.STORE_NAME -> text.trim().lowercase(Locale.ROOT)
            FieldType.EXPIRY_DATE -> text.trim().lowercase(Locale.ROOT)
            else -> text.trim().lowercase(Locale.ROOT)
        }
    }

    private fun buildCouponFromFields(
        extractedFields: Map<FieldType, ExtractionCandidate>,
        imageUri: String,
        cleanedOcr: String,
        context: ExtractionContext
    ): Coupon {
        val storeNameCandidate = extractedFields[FieldType.STORE_NAME]
        val storeName = storeNameCandidate?.text?.takeIf { it.isNotBlank() }
            ?: extractStoreFromContext(cleanedOcr, context)
            ?: "Needs review"

        val redeemCode = extractedFields[FieldType.COUPON_CODE]
            ?.text
            ?.takeIf { !it.equals("NO_CODE_NEEDED", ignoreCase = true) }

        val expiryFallbackText = listOfNotNull(
            cleanedOcr,
            context.originalOcrText
        ).joinToString("\n")
        val expiryDate = extractedFields[FieldType.EXPIRY_DATE]?.text
            ?.let { parseExpiryDate(it, context.captureTimestamp, expiryFallbackText) }
            ?: parseExpiryDate(expiryFallbackText, context.captureTimestamp, expiryFallbackText)

        val amountCandidate = extractedFields[FieldType.AMOUNT]
        val cashbackDetail = amountCandidate
            ?.text
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { DescriptionUtils.formatCashbackDetail(it) ?: it }

        val description = buildDescription(extractedFields, cleanedOcr, storeName)

        val confidenceBreakdown = extractedFields.entries.associate { (fieldType, candidate) ->
            fieldType.name.lowercase(Locale.ROOT) to candidate.confidence
        }

        val storeSource = storeNameCandidate?.context?.get("source")
            ?: storeNameCandidate?.source?.name
        val storeEvidence = storeNameCandidate?.text?.takeIf { it.isNotBlank() }
            ?.let { listOf(it) }
            ?: emptyList()

        val needsAttention = storeName == "Needs review" ||
            storeNameCandidate == null ||
            storeNameCandidate.confidence < 0.5f

        val baseCoupon = Coupon(
            storeName = storeName,
            description = description,
            redeemCode = redeemCode,
            imageUri = imageUri,
            expiryDate = expiryDate,
            storeNameSource = storeSource,
            storeNameEvidence = storeEvidence,
            extractionConfidenceBreakdown = confidenceBreakdown,
            needsAttention = needsAttention
        )
        return if (cashbackDetail != null) {
            baseCoupon.withAdditionalDetails(cashbackDetail)
        } else {
            baseCoupon
        }
    }

    private fun buildDescription(
        extractedFields: Map<FieldType, ExtractionCandidate>,
        cleanedOcr: String,
        storeName: String
    ): String {
        val descriptionCandidate = extractedFields[FieldType.DESCRIPTION]
            ?.text
            ?.takeIf { !isGenericDescription(it) }
        if (descriptionCandidate != null) {
            return descriptionCandidate
        }

        extractMeaningfulSnippet(cleanedOcr)?.let { return it }

        return buildString {
            append("Offer from $storeName")
        }
    }

    private fun parseExpiryDate(
        dateText: String,
        captureTimestamp: Date?,
        fallbackText: String
    ): Date? {
        return try {
            val baseDate = captureTimestamp?.toInstant()
                ?.atZone(ZoneId.of("Asia/Kolkata"))
                ?.toLocalDate()
                ?: LocalDate.now()
            val parseResult = IndianDateParser.extractExpiryFromText(dateText, baseDate)
            val fallback = IndianDateParser.extractExpiryFromText(fallbackText, baseDate)
            val localDate = parseResult.date
                ?: IndianDateParser.parseExpiryIST(dateText, baseDate).date
                ?: fallback.date
            localDate?.let {
                val zone = ZoneId.of("Asia/Kolkata")
                val endOfDay = it.atTime(LocalTime.of(23, 59, 59))
                Date.from(endOfDay.atZone(zone).toInstant())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse expiry date: $dateText", e)
            null
        }
    }

    private fun ExtractionContext.baseLocalDate(): LocalDate {
        return captureTimestamp
            ?.toInstant()
            ?.atZone(ZoneId.of("Asia/Kolkata"))
            ?.toLocalDate()
            ?: LocalDate.now()
    }

    private fun convertProgressiveCandidates(
        result: ProgressiveExtractionResult
    ): Map<FieldType, List<ExtractionCandidate>> {
        return result.extractedFields.mapValues { (field, candidate) ->
            val source = when (candidate.source) {
                "explicit_pattern", "all_caps", "title_case_early", "repeated_word" -> ExtractionSource.PATTERN_MATCHING
                "compound_cashback", "simple_amount", "percentage", "upto_amount" -> ExtractionSource.PATTERN_MATCHING
                "relative_date", "absolute_date", "valid_until" -> ExtractionSource.PATTERN_MATCHING
                "context_code", "generic_code", "no_code_indicator" -> ExtractionSource.CONTEXT_CLUES
                "semantic_from", "semantic_cashback", "semantic_via" -> ExtractionSource.CONTEXT_CLUES
                "semantic_cashback_amount", "semantic_discount_percent", "semantic_discount_amount" -> ExtractionSource.CONTEXT_CLUES
                "semantic_last_amount", "semantic_offer_sentence", "semantic_substantial_sentence" -> ExtractionSource.CONTEXT_CLUES
                "heuristic_capital", "heuristic_number", "heuristic_first_sentence" -> ExtractionSource.PATTERN_MATCHING
                "default_first_line", "default_ocr_text", "default_zero", "default_no_code" -> ExtractionSource.PATTERN_MATCHING
                "learned_pattern" -> ExtractionSource.LEARNED_PATTERN
                else -> ExtractionSource.PATTERN_MATCHING
            }
            val extractionCandidate = ExtractionCandidate(
                text = candidate.value,
                confidence = candidate.confidence,
                source = source,
                context = buildMap {
                    put("source", candidate.source)
                    candidate.context?.let { put("context", it) }
                    put("pass", "progressive")
                }
            )
            listOf(extractionCandidate)
        }
    }

    private fun applyLearnedPattern(pattern: String, text: String): String {
        return try {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            regex.find(text)?.value ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply learned pattern $pattern", e)
            ""
        }
    }

    private fun detectCompoundAmount(text: String): String? {
        val regex = Regex("""₹\s*\d[\d,]*(?:\s*\+\s*₹?\s*\d[\d,]*\s*(?:cashback|back)?)""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value?.trim()
    }

    private fun detectCouponCode(text: String): String? {
        val regex = Regex("""(?i)(?:code|apply|use)[:\s-]*([A-Z0-9]{4,})""")
        val match = regex.find(text) ?: return null
        return match.groupValues.getOrNull(1)?.uppercase(Locale.ROOT)
    }

    private fun extractStoreFromContext(text: String, context: ExtractionContext): String? {
        val prioritized = context.brandHint?.takeIf { it.isNotBlank() }
        if (prioritized != null && !isGenericStoreName(prioritized)) return prioritized

        val pattern = Regex("""(?i)(?:from|at|by)\s+([A-Za-z0-9&' ]{3,30})""")
        val match = pattern.find(text)
        val candidate = match?.groupValues?.getOrNull(1)?.trim()
        if (candidate != null && !isGenericStoreName(candidate)) {
            return candidate.split(" ").joinToString(" ") { token ->
                token.lowercase(Locale.ROOT).replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
            }
        }

        val firstLine = text.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && it.any { ch -> ch.isLetter() } }
        return firstLine?.takeIf { !isGenericStoreName(it) }
    }

    private fun extractMeaningfulSnippet(text: String): String? {
        val candidates = text.lines()
            .map { it.trim() }
            .filter { it.length >= 6 && !it.startsWith("http", ignoreCase = true) }
            .filterNot { line ->
                val lower = line.lowercase(Locale.ROOT)
                lower.contains("terms and conditions") ||
                    lower.contains("valid on all platforms") ||
                    lower.startsWith("tnc", ignoreCase = true)
            }
        val snippet = candidates.firstOrNull()
        return snippet?.take(180)
    }

    private fun isGenericStoreName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (normalized.matches(Regex("store\\d*"))) return true

        return GENERIC_STORE_NAMES.any { generic ->
            normalized == generic || normalized.startsWith("$generic ")
        }
    }

    private fun isGenericDescription(description: String?): Boolean {
        if (description.isNullOrBlank()) return true
        val normalized = description.trim().lowercase(Locale.ROOT)
        return GENERIC_DESCRIPTION_PHRASES.any { normalized.contains(it) }
    }

    private fun createReviewOnlyCoupon(cleanedOcr: String): Coupon {
        val description = extractMeaningfulSnippet(cleanedOcr)
            ?: cleanedOcr.take(160).ifBlank { "Review required" }

        return Coupon(
            storeName = Coupon.Defaults.UNKNOWN_STORE,
            description = description,
            redeemCode = null,
            imageUri = null,
            status = "NEEDS_REVIEW",
            needsAttention = true
        )
    }

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

    suspend fun learnFromCorrection(
        extractionResult: UniversalExtractionResult,
        correctedCoupon: Coupon,
        originalText: String,
        context: ExtractionContext
    ) {
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

    suspend fun getExtractionStats(): ExtractionStats {
        val patternStats = patternLearner.getPatternStats()
        val featureImportance = confidenceScorer.getFeatureImportance()
        return ExtractionStats(
            patternStats = patternStats,
            featureImportance = featureImportance,
            totalPatternsLearned = patternStats.values.sumOf { it.totalPatterns }
        )
    }
}

/**
 * Result of universal extraction.
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
 * Aggregated statistics for monitoring.
 */
data class ExtractionStats(
    val patternStats: Map<FieldType, PatternFieldStats>,
    val featureImportance: Map<String, Float>,
    val totalPatternsLearned: Int
)

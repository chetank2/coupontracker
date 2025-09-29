package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Local LLM OCR Service using MiniCPM-Llama3-V2.5
 * Provides structured coupon extraction using on-device vision-language model
 */
class LocalLlmOcrService(
    private val context: Context,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null,
    private val injectedTelemetryService: LlmTelemetryService? = null,
    private val customOcrTextProvider: (suspend (Bitmap) -> String?)? = null
) {
    
    companion object {
        private const val TAG = "LocalLlmOcrService"

        // Inference timeout (30 seconds)
        private const val INFERENCE_TIMEOUT_MS = 30000L

        // Model version tracking
        private const val SERVICE_VERSION = "1.0.0"
        private const val SUPPORTED_MODEL_VERSION = "v2.5-q4-android"

        private val RUPEE_VARIANT_PATTERN = Regex(
            pattern = """(^|\s+|[^\w₹])((?:₹|₨|૱|रु|रू|rs\.?))[\s:=-]*([+-]?\d[\d,]*(?:\.\d+)?)""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        private val STORE_PREFIX_EXTRACTION_PATTERN = Regex(
            pattern = """(?i)^([A-Za-z][\w'&@.]*?(?:\s+[A-Za-z][\w'&@.]*){0,3})\s*[:\-\|–—]+\s*(.+)$"""
        )

        fun cleanDescription(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return ""
            }

            val timestampPattern = Regex("^\\d{1,2}:\\d{2}")
            val singleLetterPattern = Regex("""^[A-Za-z]$""")

            val cleanedLines = raw.lines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) {
                    return@mapNotNull null
                }

                if (timestampPattern.containsMatchIn(trimmed)) {
                    return@mapNotNull null
                }

                if (singleLetterPattern.matches(trimmed)) {
                    return@mapNotNull null
                }

                if (trimmed.equals("x", ignoreCase = true)) {
                    return@mapNotNull null
                }

                if (isNoisyLine(trimmed)) {
                    return@mapNotNull null
                }

                val uppercaseLetters = trimmed.count { it.isLetter() && it.isUpperCase() }
                val lowercaseLetters = trimmed.count { it.isLetter() && it.isLowerCase() }

                val normalized = if (uppercaseLetters > 0 && uppercaseLetters >= lowercaseLetters && trimmed.contains(' ')) {
                    trimmed.lowercase(Locale.getDefault()).replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
                    }
                } else {
                    trimmed
                }

                normalized
            }

            val tokens = mutableListOf<String>()
            var previousTokenKey: String? = null

            cleanedLines.forEach { cleanedLine ->
                cleanedLine.split(Regex("\\s+")).forEach { rawToken ->
                    val candidate = normalizeTokenForOutput(rawToken)
                    if (candidate.isEmpty() || !shouldKeepToken(candidate)) {
                        return@forEach
                    }

                    val key = buildTokenKey(candidate)
                    if (key.isEmpty() || key == previousTokenKey) {
                        return@forEach
                    }

                    previousTokenKey = key
                    tokens.add(candidate)
                }
            }

            val joined = tokens.joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()

            val withoutStorePrefix = stripLikelyStorePrefix(joined)
            val normalizedRupees = normalizeRupeeVariants(withoutStorePrefix)
            return ensureLeadingCapital(normalizedRupees)
        }

        private fun shouldKeepToken(token: String): Boolean {
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            val effectiveLength = token.count { !it.isWhitespace() }

            if (letters + digits == 0) {
                return false
            }

            if (letters > 0) {
                val nonLetter = effectiveLength - letters - digits
                if (nonLetter >= letters) {
                    return false
                }
            }

            return true
        }

        private fun buildTokenKey(token: String): String {
            val alphanumeric = token.filter { it.isLetterOrDigit() }
            if (alphanumeric.isNotEmpty()) {
                return alphanumeric.lowercase(Locale.getDefault())
            }
            return token.lowercase(Locale.getDefault())
        }

        private fun normalizeTokenForOutput(token: String): String {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) {
                return ""
            }

            val sanitized = trimmed.trim(*TRIMMABLE_TOKEN_CHARS)
            return if (sanitized.isNotEmpty()) sanitized else trimmed
        }

        private fun isNoisyLine(line: String): Boolean {
            val compact = line.filterNot { it.isWhitespace() }
            if (compact.isEmpty()) {
                return true
            }

            val letters = compact.count { it.isLetter() }
            val digits = compact.count { it.isDigit() }
            val symbols = compact.length - letters - digits

            if (letters == 0 && digits == 0) {
                return true
            }

            if (symbols.toDouble() > compact.length * 0.45) {
                return true
            }

            if (letters == 0 && digits > 0) {
                return false
            }

            val vowels = line.count { it.lowercaseChar() in VOWELS }
            if (letters >= 6 && vowels == 0) {
                return true
            }

            val tokens = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                return true
            }

            val gibberishTokens = tokens.count { token ->
                val tokenLetters = token.count { it.isLetter() }
                if (tokenLetters == 0) {
                    return@count token.count { it.isDigit() } == 0
                }

                val tokenVowels = token.count { it.lowercaseChar() in VOWELS }
                val alphaNumeric = token.count { it.isLetterOrDigit() }

                tokenLetters < alphaNumeric / 2 || (tokenVowels == 0 && tokenLetters >= 4)
            }

            if (gibberishTokens == tokens.size) {
                return true
            }

            val letterRatio = letters.toDouble() / compact.length
            if (letterRatio < 0.3 && digits.toDouble() / compact.length < 0.2) {
                return true
            }

            return false
        }

        private val TRIMMABLE_TOKEN_CHARS = charArrayOf(
            '.', ',', ';', ':', '!', '?', '\'', '"', '-', '_', '•', '·', '–', '—', '…', '*', '#', '|', '/', '\\'
        )

        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

        private fun normalizeRupeeVariants(text: String): String {
            if (text.isEmpty()) {
                return text
            }

            return RUPEE_VARIANT_PATTERN.replace(text) { matchResult ->
                val leading = matchResult.groupValues[1]
                val marker = matchResult.groupValues[2]
                val amount = matchResult.groupValues[3]

                val markerStart = matchResult.range.first + leading.length
                val markerEnd = markerStart + marker.length
                val precedingChar = text.getOrNull(markerStart - 1)
                val followingChar = text.getOrNull(markerEnd)

                if (marker.any { it.isLetter() }) {
                    val precedingIsLetter = precedingChar?.isLetter() == true
                    val followingIsLetter = followingChar?.isLetter() == true
                    if (precedingIsLetter || followingIsLetter) {
                        return@replace matchResult.value
                    }
                }

                val cleanedAmount = amount.replaceFirst(Regex("^([+-]?)0+(?=\\d)"), "$1")
                leading + "₹" + cleanedAmount
            }
        }

        private fun stripLikelyStorePrefix(text: String): String {
            if (text.isBlank()) {
                return text
            }

            val match = STORE_PREFIX_EXTRACTION_PATTERN.find(text) ?: return text
            val remainder = match.groupValues[2].trim()
            if (remainder.isEmpty()) {
                return text
            }

            val lowerRemainder = remainder.lowercase(Locale.getDefault())
            val indicatorKeywords = listOf(
                "coupon", "coupons", "cashback", "discount", "discounts", "offer", "offers",
                "deal", "deals", "sale", "save", "savings", "reward", "rewards", "code", "promo",
                "%", "₹"
            )

            val hasKeyword = indicatorKeywords.any { keyword ->
                when (keyword) {
                    "%" -> remainder.contains('%')
                    "₹" -> remainder.contains('₹')
                    else -> lowerRemainder.contains(keyword)
                }
            }

            val hasDigits = remainder.any { it.isDigit() }

            return if (hasKeyword || hasDigits) remainder else text
        }

        private fun ensureLeadingCapital(text: String): String {
            if (text.isBlank()) {
                return text
            }

            val firstLetterIndex = text.indexOfFirst { it.isLetter() }
            if (firstLetterIndex == -1) {
                return text
            }

            val firstLetter = text[firstLetterIndex]
            return if (firstLetter.isLowerCase()) {
                val builder = StringBuilder(text)
                builder.replace(firstLetterIndex, firstLetterIndex + 1, firstLetter.titlecase(Locale.getDefault()))
                builder.toString()
            } else {
                text
            }
        }
    }
    
    // Dependencies
    private val llmRuntime = injectedLlmRuntimeManager ?: LlmRuntimeManager.getInstance(context)
    private val telemetryService = injectedTelemetryService ?: LlmTelemetryService.getInstance(context)
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor() // Fallback
    
    /**
     * Check if the LLM service is available
     */
    fun isServiceAvailable(): Boolean {
        return llmRuntime.isModelAvailable()
    }
    
    /**
     * Get service status information
     */
    fun getServiceStatus(): LlmServiceStatus {
        val modelInfo = llmRuntime.getModelInfo()
        val memoryStats = llmRuntime.getMemoryStats()
        
        return LlmServiceStatus(
            isAvailable = modelInfo.isAvailable,
            isModelLoaded = modelInfo.isLoaded,
            modelVersion = modelInfo.version,
            serviceVersion = SERVICE_VERSION,
            modelSizeMB = modelInfo.sizeMB,
            memoryUsageMB = memoryStats.modelLoadedMemoryMB,
            referenceCount = modelInfo.referenceCount
        )
    }
    
    /**
     * Process coupon image using local LLM with typed results
     * New entry point that returns ExtractResult for better error handling
     */
    suspend fun processCouponImageTyped(bitmap: Bitmap, captureTimestamp: Date? = null): ExtractResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        var memoryUsage = 0L
        var extractedFieldCount = 0
        val triedStages = mutableListOf<String>()
        
        try {
            Log.d(TAG, "Processing coupon with MiniCPM-Llama3-V2.5 (typed)")
            triedStages.add("LLM")
            
            // Step 1: Validate input
            if (bitmap.isRecycled) {
                return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = IllegalArgumentException("Input bitmap is recycled")
                )
            }
            
            // Step 2: Check service availability
            if (!isServiceAvailable()) {
                return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = IllegalStateException("LLM model not available on device")
                )
            }
            
            // Step 3: Preprocess image for MiniCPM
            val preprocessedBitmap = preprocessForMiniCPM(bitmap)
            Log.d(TAG, "Preprocessed image for MiniCPM: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
            
            // Step 4: Create structured extraction prompt
            val prompt = createCouponExtractionPrompt()
            
            // Step 5: Run LLM inference with timeout and memory tracking
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runInference(preprocessedBitmap, prompt)
            }

            // Step 6: Parse and validate response
            if (llmResponse != null) {
                val rawOcrText = captureRawOcrText(preprocessedBitmap)
                val couponInfo = parseLlmResponseToCouponInfo(llmResponse, rawOcrText)
                extractedFieldCount = countExtractedFields(couponInfo)
                
                // Step 7: Quality validation
                val qualityScore = calculateQualityScore(couponInfo)
                val signals = ExtractionSignals(
                    qualityScore = qualityScore,
                    fieldConfidences = calculateFieldConfidences(couponInfo),
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    memoryUsageMB = memoryUsage.toFloat(),
                    stage = ExtractionStage.LLM,
                    nativeAvailable = llmRuntime.isModelAvailable(),
                    modelVersion = SUPPORTED_MODEL_VERSION
                )
                
                // Determine result based on quality
                return@coroutineScope when {
                    qualityScore >= 70 -> ExtractResult.Good(couponInfo, signals)
                    qualityScore >= 40 -> {
                        val reason = determineQualityReason(couponInfo)
                        ExtractResult.LowQuality(couponInfo, reason, signals)
                    }
                    else -> {
                        val reason = determineQualityReason(couponInfo)
                        ExtractResult.LowQuality(couponInfo, reason, signals)
                    }
                }
            } else {
                // Timeout occurred
                return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = Exception("LLM inference timeout after ${INFERENCE_TIMEOUT_MS}ms")
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)
            
            val signals = ExtractionSignals(
                qualityScore = 0,
                fieldConfidences = emptyMap(),
                processingTimeMs = System.currentTimeMillis() - startTime,
                memoryUsageMB = memoryUsage.toFloat(),
                stage = ExtractionStage.LLM,
                nativeAvailable = llmRuntime.isModelAvailable()
            )
            
            return@coroutineScope ExtractResult.Failed(
                stage = ExtractionStage.LLM,
                error = e,
                signals = signals
            )
        }
    }
    
    /**
     * Process coupon image using local LLM
     * Main entry point that mirrors ModelBasedOCRService.processCouponImage
     */
    suspend fun processCouponImage(bitmap: Bitmap, captureTimestamp: Date? = null): CouponInfo = coroutineScope {
        val startTime = System.currentTimeMillis()
        var memoryUsage = 0L
        var extractedFieldCount = 0
        var fallbackUsed: String? = null
        
        try {
            Log.d(TAG, "Processing coupon with MiniCPM-Llama3-V2.5")
            
            // Step 1: Validate input
            if (bitmap.isRecycled) {
                throw IllegalArgumentException("Input bitmap is recycled")
            }
            
            // Step 2: Check service availability
            if (!isServiceAvailable()) {
                throw IllegalStateException("LLM model not available on device")
            }
            
            // Step 3: Preprocess image for MiniCPM (768px long side, RGB format)
            val preprocessedBitmap = preprocessForMiniCPM(bitmap)
            Log.d(TAG, "Preprocessed image for MiniCPM: ${preprocessedBitmap.width}x${preprocessedBitmap.height}")
            
            // Step 4: Create structured extraction prompt
            val prompt = createCouponExtractionPrompt()
            
            // Step 5: Run LLM inference with timeout and memory tracking
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runInference(preprocessedBitmap, prompt)
            }

            // Step 6: Parse and validate response
            val couponInfo = if (llmResponse != null) {
                val rawOcrText = try {
                    captureRawOcrText(bitmap).also {
                        if (!it.isNullOrBlank()) {
                            Log.d(TAG, "Captured OCR text sample: ${it.take(80)}")
                        }
                    }
                } catch (ocrError: Exception) {
                    Log.w(TAG, "Failed to capture supporting OCR text", ocrError)
                    null
                }

                parseLlmResponseToCouponInfo(llmResponse, rawOcrText)
            } else {
                // Record timeout and throw
                val duration = System.currentTimeMillis() - startTime
                telemetryService.recordTimeout(duration, memoryUsage)
                throw Exception("LLM inference timed out or returned null")
            }
            
            // Step 7: Validate extraction quality and count fields
            validateExtractionQuality(couponInfo)
            extractedFieldCount = countExtractedFields(couponInfo)
            
            // Record successful inference
            val duration = System.currentTimeMillis() - startTime
            telemetryService.recordInference(
                durationMs = duration,
                success = true,
                extractedFieldCount = extractedFieldCount,
                memoryUsageMB = memoryUsage
            )
            
            Log.d(TAG, "MiniCPM extraction completed successfully in ${duration}ms")
            couponInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)
            
            // Record failure
            val duration = System.currentTimeMillis() - startTime
            val errorType = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> "TIMEOUT"
                e.message?.contains("memory", ignoreCase = true) == true -> "MEMORY"
                e.message?.contains("model", ignoreCase = true) == true -> "MODEL_ERROR"
                else -> "PROCESSING_ERROR"
            }
            
            // Fallback to traditional OCR
            Log.d(TAG, "Falling back to traditional OCR")
            val fallbackResult = fallbackToTraditionalOCR(bitmap, captureTimestamp)
            
            // Determine which fallback was used based on result quality
            fallbackUsed = if (fallbackResult.storeName != "Unknown Store" || 
                             !fallbackResult.redeemCode.isNullOrBlank()) {
                "ML_KIT"
            } else {
                "MODEL_BASED"
            }
            
            extractedFieldCount = countExtractedFields(fallbackResult)
            
            telemetryService.recordInference(
                durationMs = duration,
                success = false,
                errorType = errorType,
                fallbackUsed = fallbackUsed,
                extractedFieldCount = extractedFieldCount,
                memoryUsageMB = memoryUsage
            )
            
            fallbackResult
        }
    }
    
    private fun countExtractedFields(couponInfo: CouponInfo): Int {
        var count = 0
        if (couponInfo.storeName != "Unknown Store") count++
        if (!couponInfo.redeemCode.isNullOrBlank()) count++
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) count++
        if (couponInfo.expiryDate != null) count++
        if (couponInfo.description != "Coupon offer") count++
        if (couponInfo.minimumPurchase != null && couponInfo.minimumPurchase > 0) count++
        return count
    }
    
    /**
     * Create optimized structured prompt for coupon extraction with strict schema
     */
    private fun createCouponExtractionPrompt(): String {
        return """
        You are a strict coupon extractor. Output ONLY valid JSON with this exact schema:
        {
            "storeName": string|null,
            "description": string|null,
            "cashbackAmount": string|null,
            "redeemCode": string|null,
            "expiryDate": string|null,
            "minOrderAmount": string|null
        }

        RULES:
        - If a field is not explicitly present, output null
        - Do not invent values. Do not include notes
        - Preserve exact text as shown in image
        - For codes: use exact alphanumeric text (SAVE20, FLAT50)
        - For amounts: include currency (₹100, 20%, $50 off)
        - For dates: use format shown (31 Dec 2024, 31/12/2024)

        Example:
        {"storeName":"Myntra","description":"Flat 20% off on fashion","cashbackAmount":"20% off","redeemCode":"SAVE20","expiryDate":"31 Dec 2024","minOrderAmount":"₹999"}

        Extract from the coupon image:
        """.trimIndent()
    }

    private suspend fun captureRawOcrText(bitmap: Bitmap): String? {
        customOcrTextProvider?.let { provider ->
            return runCatching { provider(bitmap) }.onFailure {
                Log.w(TAG, "Custom OCR text provider failed", it)
            }.getOrNull()
        }

        return try {
            val mlKitText = performMlKitOcr(bitmap)
            if (mlKitText.isBlank()) {
                Log.w(TAG, "ML Kit OCR returned blank text during capture")
                null
            } else {
                mlKitText
            }
        } catch (mlKitError: Exception) {
            Log.w(TAG, "ML Kit OCR capture failed, attempting fallback", mlKitError)
            runCatching {
                val fallbackService = ModelBasedOCRService(context)
                val fallbackInfo = fallbackService.processCouponImage(bitmap)
                listOfNotNull(
                    fallbackInfo.storeName.takeIf { !it.equals("Unknown Store", ignoreCase = true) && it.isNotBlank() },
                    fallbackInfo.description.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) },
                    fallbackInfo.redeemCode
                ).joinToString(" ").ifBlank { null }
            }.onFailure {
                Log.w(TAG, "Fallback extraction failed for OCR capture", it)
            }.getOrNull()
        }
    }

    /**
     * Parse LLM JSON response to CouponInfo object with strict validation
     */
    private fun parseLlmResponseToCouponInfo(response: String, rawOcrText: String?): CouponInfo {
        return try {
            // Clean response (remove any markdown formatting)
            val cleanResponse = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            // Use strict JSON validation
            val json = CouponJsonValidator.parseStrict(cleanResponse)
            if (json == null) {
                Log.w(TAG, "JSON failed strict validation, falling back to OCR")
                throw IllegalArgumentException("Invalid JSON schema")
            }
            
            // Validate field constraints
            val validation = CouponJsonValidator.validateFieldConstraints(json)
            if (validation is JsonValidationResult.Invalid) {
                Log.w(TAG, "Field validation failed: ${validation.issues}")
                // Continue with warnings but don't fail completely
            }
            
            // Extract fields with fallbacks and generic filtering
            val storeName = json.optString("storeName", "Unknown Store").let {
                when {
                    it.isBlank() || it == "Unknown" -> "Unknown Store"
                    GenericFieldHeuristics.isGenericOrMissing(it) -> "Unknown Store"
                    else -> it
                }
            }
            
            val rawDescription = json.optString("description", "")
            val cleanedDescription = cleanDescription(rawDescription)
            val description = when {
                cleanedDescription.isBlank() -> "Coupon offer"
                cleanedDescription.equals("Unknown", ignoreCase = true) -> "Coupon offer"
                GenericFieldHeuristics.isGenericOrMissing(cleanedDescription) -> "Coupon offer"
                else -> cleanedDescription.take(100)
            }
            
            // Note: 'amount' field is handled by cashbackAmount, keeping for future use
            // val amount = json.optString("amount").let {
            //     if (it.isBlank() || it == "Unknown") null else it
            // }
            
            // Extract brand for code validation
            val detectedBrand = BrandAwareCouponValidator.extractBrand(
                json.optString("storeName").takeIf { it.isNotBlank() },
                json.optString("description").takeIf { it.isNotBlank() }
            )
            
            // Try both 'redeemCode' (from prompt) and 'code' (fallback) to handle both field names
            val code = (json.optString("redeemCode").takeIf { it.isNotBlank() && it != "Unknown" }
                ?: json.optString("code").takeIf { it.isNotBlank() && it != "Unknown" })
                ?.let { rawCode ->
                    // Use brand-aware validation instead of basic sanitization
                    val sanitized = RedeemCodeSanitizer.sanitize(rawCode)
                    if (sanitized != null && !BrandAwareCouponValidator.isJunkCode(sanitized)) {
                        val candidates = BrandAwareCouponValidator.rankCodes(detectedBrand, listOf(sanitized))
                        val bestCandidate = candidates.firstOrNull()
                        
                        if (bestCandidate != null && bestCandidate.score > 0.5) {
                            Log.d(TAG, "Validated code: $sanitized (brand: $detectedBrand, score: ${bestCandidate.score})")
                            bestCandidate.text
                        } else {
                            Log.w(TAG, "Low confidence code: $sanitized (score: ${bestCandidate?.score ?: 0.0})")
                            null
                        }
                    } else {
                        null
                    }
                }
                ?.takeIf { !GenericFieldHeuristics.isGenericOrMissing(it) }
            
            val expiryDate = json.optString("expiryDate").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            val cashbackAmountRaw = json.optString("cashbackAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }

            val validatedCashback = cashbackAmountRaw?.takeIf {
                hasSupportingDigits(it, rawOcrText, cleanedDescription)
            } ?: run {
                if (!cashbackAmountRaw.isNullOrBlank()) {
                    Log.w(TAG, "Rejecting cashback amount '$cashbackAmountRaw' due to missing OCR support")
                }
                null
            }
            
            val minOrderAmount = json.optString("minOrderAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            // Check for duplicate values between fields (common LLM issue)
            val finalStoreName = if (GenericFieldHeuristics.areDuplicateFields(storeName, code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$storeName' - downgrading store name")
                "Unknown Store"
            } else storeName
            
            val finalCode = if (GenericFieldHeuristics.areDuplicateFields(storeName, code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$code' - clearing redeem code")
                null
            } else code
            
            // Parse expiry date with IST-first parser
            val parsedExpiryDate = expiryDate?.let { dateStr ->
                try {
                    val parseResult = IndianDateParser.parseExpiryIST(dateStr)
                    if (parseResult.date != null && parseResult.confidence > 0.5f) {
                        Log.d(TAG, "Parsed expiry date: $dateStr -> ${parseResult.date} (confidence: ${parseResult.confidence})")
                        java.util.Date.from(parseResult.date.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
                    } else {
                        Log.w(TAG, "Low confidence date parse: $dateStr (confidence: ${parseResult.confidence})")
                        // Fallback to original parser
                        DateParser.parseDate(dateStr)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse expiry date: $dateStr", e)
                    null
                }
            }
            
            return CouponInfo(
                storeName = finalStoreName,
                description = description,
                expiryDate = parsedExpiryDate,
                cashbackAmount = parseNumericValue(validatedCashback),
                redeemCode = finalCode,
                minimumPurchase = parseNumericValue(minOrderAmount)
            )
            
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse LLM JSON response: $response", e)
            throw IllegalStateException("Invalid JSON response from LLM: ${e.message}")
        }
    }

    private fun hasSupportingDigits(amountText: String, rawOcrText: String?, cleanedDescription: String): Boolean {
        val numericTokens = Regex("""\d[\d,]*(?:\.\d+)?""").findAll(amountText)
            .map { it.value }
            .filter { it.isNotBlank() }
            .toList()

        if (numericTokens.isEmpty()) {
            Log.w(TAG, "No digit sequences found in cashback amount '$amountText'")
            return false
        }

        val searchSpaces = mutableListOf<String>()
        rawOcrText?.let { searchSpaces.add(it) }
        if (cleanedDescription.isNotBlank()) {
            searchSpaces.add(cleanedDescription)
        }

        if (searchSpaces.isEmpty()) {
            Log.w(TAG, "No OCR text available to validate cashback amount '$amountText'")
            return false
        }

        val missingTokens = numericTokens.filterNot { token ->
            searchSpaces.any { text -> containsDigitSequence(text, token) }
        }

        if (missingTokens.isNotEmpty()) {
            Log.w(TAG, "Missing numeric support for tokens $missingTokens in amount '$amountText'")
            return false
        }

        return true
    }

    private fun containsDigitSequence(text: String?, token: String): Boolean {
        if (text.isNullOrBlank() || token.isBlank()) {
            return false
        }

        if (text.contains(token)) {
            return true
        }

        val normalizedToken = token.replace(Regex("[^0-9]"), "")
        if (normalizedToken.isBlank()) {
            return false
        }

        val normalizedText = text.replace(Regex("[^0-9]"), "")
        if (normalizedText.contains(normalizedToken)) {
            return true
        }

        val trimmedToken = normalizedToken.trimStart('0')
        return trimmedToken.isNotBlank() && normalizedText.contains(trimmedToken)
    }

    /**
     * Parse numeric value from currency/percentage strings
     * Handles formats like: ₹150, $25, 25%, 150.50, etc.
     */
     private fun parseNumericValue(value: String?): Double? {
        if (value.isNullOrBlank() || value == "Unknown") return null
        
        return try {
            // Remove currency symbols and extract numeric value
            val numericString = value
                .replace(Regex("[₹$£€¥%,\\s]"), "") // Remove common currency symbols, %, commas, spaces
                .replace(Regex("[^0-9.]"), "") // Keep only digits and decimal points
                .trim()
            
            if (numericString.isBlank()) {
                Log.w(TAG, "No numeric value found in: '$value'")
                null
            } else {
                val parsed = numericString.toDoubleOrNull()
                Log.d(TAG, "Parsed '$value' → $parsed")
                parsed
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse numeric value: '$value'", e)
            null
        }
    }
    
    /**
     * Preprocess bitmap specifically for MiniCPM-Llama3-V2.5 vision model
     * Ensures optimal input format: 768px long side, RGB format, proper aspect ratio
     */
    private fun preprocessForMiniCPM(bitmap: Bitmap): Bitmap {
        val targetLongSide = 768
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        
        // Calculate scaling to fit target long side
        val longSide = maxOf(originalWidth, originalHeight)
        val scale = if (longSide > targetLongSide) {
            targetLongSide.toFloat() / longSide
        } else {
            1.0f // Don't upscale small images
        }
        
        val newWidth = (originalWidth * scale).toInt()
        val newHeight = (originalHeight * scale).toInt()
        
        // Create scaled bitmap with RGB_565 format (efficient for inference)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Ensure RGB format for MiniCPM
        val rgbBitmap = if (scaledBitmap.config != Bitmap.Config.RGB_565) {
            scaledBitmap.copy(Bitmap.Config.RGB_565, false)
        } else {
            scaledBitmap
        }
        
        Log.d(TAG, "MiniCPM preprocessing: ${originalWidth}x${originalHeight} -> ${newWidth}x${newHeight} (scale: $scale)")
        
        return rgbBitmap
    }
    
    /**
     * Validate extraction quality and completeness
     */
    private fun validateExtractionQuality(couponInfo: CouponInfo) {
        var qualityScore = 0
        var failureReason: String? = null
        
        // Check essential fields
        if (couponInfo.storeName != "Unknown Store") qualityScore += 30
        if (!couponInfo.redeemCode.isNullOrBlank()) qualityScore += 25
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) qualityScore += 25
        if (couponInfo.expiryDate != null) qualityScore += 10
        if (couponInfo.description != "Coupon offer") qualityScore += 10
        
        // Additional quality checks for boilerplate/generic content
        val hasGenericStoreName = GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)
        val hasGenericDescription = GenericFieldHeuristics.isGenericOrMissing(couponInfo.description)
        val hasGenericCode = GenericFieldHeuristics.isGenericOrMissing(couponInfo.redeemCode)
        val hasMeaninglessAmount = GenericFieldHeuristics.isZeroOrMeaningless(couponInfo.cashbackAmount)
        
        // Check for duplicate fields (already handled in parsing, but validate here too)
        val hasDuplicateFields = GenericFieldHeuristics.areDuplicateFields(
            couponInfo.storeName, couponInfo.redeemCode
        )
        
        Log.d(TAG, "Extraction quality score: $qualityScore/100")
        Log.d(TAG, "Generic checks - Store: $hasGenericStoreName, Desc: $hasGenericDescription, Code: $hasGenericCode, Amount: $hasMeaninglessAmount, Duplicates: $hasDuplicateFields")
        
        // Determine failure reasons for better telemetry
        when {
            // Complete failure - no meaningful data at all
            couponInfo.storeName == "Unknown Store" && 
            couponInfo.redeemCode.isNullOrBlank() && 
            hasMeaninglessAmount -> {
                failureReason = "COMPLETE_EXTRACTION_FAILURE"
            }
            
            // Generic/boilerplate content detected
            hasGenericStoreName && hasGenericCode && hasGenericDescription -> {
                failureReason = "ALL_GENERIC_CONTENT"
            }
            
            // Duplicate field values
            hasDuplicateFields -> {
                failureReason = "DUPLICATE_FIELD_VALUES"
            }
            
            // Low quality score but some data present
            qualityScore < 40 -> {
                failureReason = "LOW_QUALITY_EXTRACTION"
            }
        }
        
        // Log warning for low quality extractions
        if (qualityScore < 40 || failureReason != null) {
            Log.w(TAG, "Low quality extraction detected (score: $qualityScore, reason: $failureReason)")
        }
        
        // Throw exception to trigger fallback OCR if quality is insufficient
        if (failureReason != null) {
            throw IllegalStateException("Insufficient extraction quality: $failureReason (score: $qualityScore)")
        }
    }
    
    /**
     * Fallback to traditional OCR when LLM fails
     */
    private suspend fun fallbackToTraditionalOCR(bitmap: Bitmap, captureTimestamp: Date? = null): CouponInfo = withContext(Dispatchers.IO) {
        Log.d(TAG, "Using traditional OCR fallback")
        
        try {
            // Use Google ML Kit for text recognition
            val mlKitText = performMlKitOcr(bitmap)
            Log.d(TAG, "ML Kit OCR extracted text: ${mlKitText.take(100)}...")
            
            // Validate that we got real text, not empty/whitespace
            if (mlKitText.isBlank()) {
                throw Exception("ML Kit OCR returned blank text")
            }
            
            // Use existing TextExtractor to parse the OCR text
            val textExtractor = TextExtractor()
            val extractedInfo = textExtractor.extractCouponInfoSync(mlKitText, captureTimestamp)
                .let { info ->
                    info.copy(description = cleanDescription(info.description))
                }
            
            // Validate that we got meaningful extraction results
            if (extractedInfo.storeName == "Unknown Store" && 
                extractedInfo.redeemCode.isNullOrBlank() && 
                (extractedInfo.cashbackAmount == null || extractedInfo.cashbackAmount <= 0)) {
                throw Exception("ML Kit OCR produced insufficient coupon data")
            }
            
            Log.d(TAG, "Traditional OCR fallback result: $extractedInfo")
            return@withContext extractedInfo
            
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit OCR fallback failed: ${e.message}", e)
            
            // Try using ModelBasedOCRService as final fallback
            try {
                val modelBasedService = ModelBasedOCRService(context)
                val result = modelBasedService.processCouponImage(bitmap)
                val cleanedResult = result.copy(description = cleanDescription(result.description))
                Log.d(TAG, "Model-based OCR fallback result: $cleanedResult")
                return@withContext cleanedResult
            } catch (e2: Exception) {
                Log.e(TAG, "All OCR methods failed", e2)

                // Return minimal CouponInfo as last resort
                CouponInfo(
                    storeName = "Unknown Store",
                    description = cleanDescription("All OCR methods failed - please try again")
                )
            }
        }
    }
    
    /**
     * Perform ML Kit OCR on bitmap
     */
    private suspend fun performMlKitOcr(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val extractedText = visionText.text
                        Log.d(TAG, "ML Kit OCR success: ${extractedText.length} chars")
                        continuation.resume(extractedText)
                    }
                    .addOnFailureListener { exception ->
                        Log.e(TAG, "ML Kit OCR failed", exception)
                        continuation.resumeWithException(exception)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up ML Kit OCR", e)
                continuation.resumeWithException(e)
            }
        }
    }
    
    /**
     * Warm up the model (preload for faster inference)
     */
    suspend fun warmUpModel(): Boolean {
        return try {
            Log.d(TAG, "Warming up LLM model...")
            llmRuntime.loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to warm up model", e)
            false
        }
    }
    
    /**
     * Release model resources
     */
    suspend fun releaseResources() {
        Log.d(TAG, "Releasing LLM resources")
        llmRuntime.releaseModel()
    }
    
    /**
     * Get performance metrics
     */
    fun getPerformanceMetrics(): LlmPerformanceMetrics {
        val memoryStats = llmRuntime.getMemoryStats()
        val modelInfo = llmRuntime.getModelInfo()
        
        return LlmPerformanceMetrics(
            modelSizeMB = modelInfo.sizeMB,
            memoryUsageMB = memoryStats.modelLoadedMemoryMB.toFloat(),
            isModelLoaded = modelInfo.isLoaded,
            referenceCount = modelInfo.referenceCount,
            serviceVersion = SERVICE_VERSION
        )
    }
    
    /**
     * Calculate quality score for extracted coupon information
     */
    private fun calculateQualityScore(couponInfo: CouponInfo): Int {
        var qualityScore = 0
        
        // Score based on field completeness and quality
        if (!GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)) qualityScore += 25
        if (!couponInfo.redeemCode.isNullOrBlank()) qualityScore += 30
        if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) qualityScore += 20
        if (couponInfo.expiryDate != null) qualityScore += 15
        if (!GenericFieldHeuristics.isGenericOrMissing(couponInfo.description)) qualityScore += 10
        
        return qualityScore
    }
    
    /**
     * Calculate field-level confidences
     */
    private fun calculateFieldConfidences(couponInfo: CouponInfo): Map<String, Float> {
        return mapOf(
            "storeName" to if (GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)) 0.3f else 0.9f,
            "description" to if (GenericFieldHeuristics.isGenericOrMissing(couponInfo.description)) 0.3f else 0.8f,
            "redeemCode" to if (couponInfo.redeemCode.isNullOrBlank()) 0.0f else 0.9f,
            "cashbackAmount" to if (couponInfo.cashbackAmount != null && couponInfo.cashbackAmount > 0) 0.8f else 0.2f,
            "expiryDate" to if (couponInfo.expiryDate != null) 0.7f else 0.1f
        )
    }
    
    /**
     * Determine the specific quality failure reason
     */
    private fun determineQualityReason(couponInfo: CouponInfo): QualityReason {
        val hasGenericStoreName = GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)
        val hasGenericDescription = GenericFieldHeuristics.isGenericOrMissing(couponInfo.description)
        val hasGenericCode = GenericFieldHeuristics.isGenericOrMissing(couponInfo.redeemCode)
        val hasMeaninglessAmount = GenericFieldHeuristics.isZeroOrMeaningless(couponInfo.cashbackAmount)
        val hasDuplicateFields = GenericFieldHeuristics.areDuplicateFields(
            couponInfo.storeName, couponInfo.redeemCode
        )
        
        return when {
            couponInfo.storeName == "Unknown Store" &&
            couponInfo.redeemCode.isNullOrBlank() &&
            hasMeaninglessAmount -> QualityReason.COMPLETE_EXTRACTION_FAILURE
            
            hasGenericStoreName && hasGenericCode && hasGenericDescription -> 
                QualityReason.ALL_GENERIC_CONTENT
            
            hasDuplicateFields -> QualityReason.DUPLICATE_FIELD_VALUES
            
            couponInfo.redeemCode.isNullOrBlank() && hasMeaninglessAmount -> 
                QualityReason.MISSING_CRITICAL_FIELDS
            
            else -> QualityReason.LOW_QUALITY_EXTRACTION
        }
    }
}

/**
 * LLM service status data class
 */
data class LlmServiceStatus(
    val isAvailable: Boolean,
    val isModelLoaded: Boolean,
    val modelVersion: String,
    val serviceVersion: String,
    val modelSizeMB: Float,
    val memoryUsageMB: Int,
    val referenceCount: Int
)

/**
 * LLM performance metrics data class
 */
data class LlmPerformanceMetrics(
    val modelSizeMB: Float,
    val memoryUsageMB: Float,
    val isModelLoaded: Boolean,
    val referenceCount: Int,
    val serviceVersion: String
)

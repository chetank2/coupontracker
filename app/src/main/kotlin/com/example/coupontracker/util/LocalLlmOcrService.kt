package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.PromptGenerator
import com.example.coupontracker.schema.SchemaValidator
import com.example.coupontracker.schema.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * Local LLM OCR Service using Qwen2-1.5B (text-only)
 * Provides structured coupon extraction using on-device LLM with OCR input
 */
class LocalLlmOcrService(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null,
    private val injectedTelemetryService: LlmTelemetryService? = null,
    private val customOcrTextProvider: (suspend (Bitmap) -> String?)? = null
) {
    
    companion object {
        private const val TAG = "LocalLlmOcrService"

        // Inference timeout (180 seconds - accommodates warmup + generation)
        // First run: ~68s (model warmup), subsequent runs: ~10-20s
        // Increased from 120s after observing 138s timeouts on device
        private const val INFERENCE_TIMEOUT_MS = 180_000L

        // Model version tracking
        private const val SERVICE_VERSION = "1.4.0"  // Qwen2.5 migration
        private const val SUPPORTED_MODEL_VERSION = "qwen25_1.5b_instruct_q4"

        private const val OCR_SNIPPET_MAX_CHARS = 2000
        
        // Feature flags for schema-driven architecture
        // Set to true to enable schema-driven prompts/validation, false to use manual/legacy
        private const val USE_SCHEMA_PROMPTS = true
        private const val USE_SCHEMA_VALIDATION = true
        
        // Prompt optimization: Use compact prompts to reduce token count (920 → ~350 tokens)
        // Compact prompts rely on grammar enforcement for structure, reducing verbosity
        private const val USE_COMPACT_PROMPTS = true

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
            
            // Handle JSON null serialized as literal "null" string
            if (raw.equals("null", ignoreCase = true)) {
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
    
    init {
        Log.d(TAG, "🔍 LocalLlmOcrService initialization started")
        
        // Check if model is available
        val modelInfo = llmRuntime.getModelInfo()
        if (modelInfo.isAvailable) {
            Log.d(TAG, "✅ MiniCPM model available:")
            Log.d(TAG, "   Version: ${modelInfo.version}")
            Log.d(TAG, "   Size: ${modelInfo.sizeMB}MB")
            Log.d(TAG, "   Loaded: ${modelInfo.isLoaded}")
        } else {
            Log.w(TAG, "⚠️  MiniCPM model NOT available - extraction will use pattern fallbacks")
            Log.w(TAG, "   Download the model from Settings to enable AI-powered extraction")
        }
    }
    
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
            Log.d(TAG, "Processing coupon with Qwen2-1.5B (text-only, typed)")
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
            
            // Step 3: Capture OCR text (used for prompt and post-processing)
            val rawOcrText = captureRawOcrText(bitmap)
                ?: return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = IllegalStateException("OCR returned blank text; cannot build prompt")
                )
            val prompt = createCouponExtractionPrompt(rawOcrText)
            
            // Step 4: Run LLM inference with timeout and memory tracking
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runTextInference(rawOcrText, prompt)
            }

            // Step 5: Parse and validate response
            if (llmResponse != null) {
                val trimmedResponse = llmResponse.trimStart()
                if (!trimmedResponse.startsWith("{")) {
                    Log.e(TAG, "LLM response did not start with '{': ${trimmedResponse.take(200)}")
                    throw IllegalStateException("LLM response not JSON (missing opening brace)")
                }
                val couponInfo = parseLlmResponseToCouponInfo(llmResponse, rawOcrText)
                
                // CRITICAL: Detect mock responses and reject them
                if (isMockLlmResponse(couponInfo)) {
                    Log.w(TAG, "⚠️ MOCK LLM RESPONSE DETECTED - Falling back to OCR")
                    return@coroutineScope ExtractResult.Failed(
                        stage = ExtractionStage.LLM,
                        error = IllegalStateException("Mock LLM response detected (Example Store / MOCK123)")
                    )
                }
                
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
            Log.d(TAG, "Processing coupon with Qwen2-1.5B (text-only)")
            
            // Step 1: Validate input
            if (bitmap.isRecycled) {
                throw IllegalArgumentException("Input bitmap is recycled")
            }
            
            // Step 2: Check service availability
            if (!isServiceAvailable()) {
                throw IllegalStateException("LLM model not available on device")
            }
            
            // Step 3: Extract OCR text from image
            Log.d(TAG, "Extracting OCR text from image...")
            val ocrText = captureRawOcrText(bitmap)
            if (ocrText.isNullOrBlank()) {
                throw IllegalStateException("OCR text extraction failed or returned empty text")
            }
            Log.d(TAG, "OCR extracted ${ocrText.length} chars: ${ocrText.take(200)}...")
            
            // Step 4: Create structured extraction prompt
            val prompt = createCouponExtractionPrompt(ocrText)
            
            // Step 5: Run TEXT-ONLY LLM inference with OCR text
            Log.d(TAG, "========================================")
            Log.d(TAG, "🤖 Running MiniCPM TEXT-ONLY inference...")
            Log.d(TAG, "⏱️  First run: ~60s (model warmup)")
            Log.d(TAG, "⏱️  Subsequent runs: ~10s")
            Log.d(TAG, "⏳ Please wait... (max ${INFERENCE_TIMEOUT_MS / 1000}s)")
            Log.d(TAG, "========================================")
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val inferenceStartTime = System.currentTimeMillis()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runTextInference(ocrText, prompt)
            }
            val inferenceElapsed = System.currentTimeMillis() - inferenceStartTime
            Log.d(TAG, "⏱️  Inference completed in ${inferenceElapsed / 1000}s")

            // Step 6: Parse and validate response
            val couponInfo = if (llmResponse != null) {
                // We already have OCR text from Step 3, no need to extract again
                val parsedInfo = parseLlmResponseToCouponInfo(llmResponse, ocrText)
                
                // CRITICAL: Detect mock responses and fall back to OCR
                if (isMockLlmResponse(parsedInfo)) {
                    Log.w(TAG, "⚠️ MOCK LLM RESPONSE DETECTED - Falling back to OCR")
                    throw IllegalStateException("Mock LLM response detected (Example Store / MOCK123)")
                }
                
                parsedInfo
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
                e.message?.contains("too short", ignoreCase = true) == true -> "SHORT_RESPONSE"
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
     * Detect mock/placeholder responses from stub JNI
     * Returns true if the response matches known mock patterns
     */
    private fun isMockLlmResponse(couponInfo: CouponInfo): Boolean {
        // Check for exact mock patterns from mlc_llm_jni.cpp
        val isMockStore = couponInfo.storeName.equals("Example Store", ignoreCase = true)
        val isMockCode = couponInfo.redeemCode?.let {
            it.equals("MOCK123", ignoreCase = true) ||
            it.equals("EXAMPLE123", ignoreCase = true) ||
            it.startsWith("MOCK", ignoreCase = true)
        } ?: false
        
        // Check for generic placeholder patterns
        val isPlaceholderDescription = couponInfo.description.let {
            it.equals("Sample coupon offer", ignoreCase = true) ||
            it.equals("Placeholder offer", ignoreCase = true) ||
            it.contains("example", ignoreCase = true) && it.contains("offer", ignoreCase = true)
        }
        
        // Detect if it's a mock response (any two indicators match)
        val mockIndicators = listOf(isMockStore, isMockCode, isPlaceholderDescription).count { it }
        
        if (mockIndicators >= 2) {
            Log.w(TAG, "Mock response detected: store='${couponInfo.storeName}', code='${couponInfo.redeemCode}', desc='${couponInfo.description}'")
            return true
        }
        
        return false
    }
    
    /**
     * Create optimized structured prompt for coupon extraction with strict schema and typed cashback
     */
    private fun createCouponExtractionPrompt(ocrText: String): String {
        // CRITICAL: Clean OCR text first to remove UI chrome (battery, signal, status bar)
        val cleanedOcr = com.example.coupontracker.util.OcrTextCleaner.cleanOcrText(ocrText)
        val finalOcr = cleanedOcr.ifBlank { ocrText }  // Fallback to raw if cleaning too aggressive
        
        val sanitizedOcr = sanitizeOcrSnippet(finalOcr)
        return if (USE_SCHEMA_PROMPTS) {
            // Schema-driven prompt generation
            if (USE_COMPACT_PROMPTS) {
                // Compact: ~350 tokens (relies on grammar for structure)
                com.example.coupontracker.schema.CompactPromptGenerator.generateCompletePrompt(
                    CouponSchema.SCHEMA, 
                    sanitizedOcr
                )
            } else {
                // Verbose: ~920 tokens (includes all examples and hints)
                PromptGenerator.generateCompletePrompt(CouponSchema.SCHEMA, sanitizedOcr)
            }
        } else {
            // Manual prompt (legacy)
            buildQwenPromptManual(sanitizedOcr)
        }
    }

    /**
     * Qwen2.5-optimized prompt for structured JSON extraction (MANUAL/LEGACY)
     * Qwen2.5 has better instruction-following than Qwen2
     * 
     * NOTE: This is the manual/legacy prompt. When USE_SCHEMA_PROMPTS=true,
     * the prompt is generated from CouponSchema instead.
     */
    private fun buildQwenPromptManual(sanitizedOcr: String): String = """<|im_start|>system
You are a JSON extractor. Extract coupon data and output ONLY valid JSON.

🚨 MANDATORY FIELDS (MUST ALWAYS INCLUDE):
1. "redeemCode" - THE coupon code (search for "Code:" in OCR)
2. "expiryDate" - THE expiry date (search for "Expires on" in OCR)
3. "storeName" - Store/brand name
4. "description" - Offer description

⚠️ CRITICAL: All 7 JSON keys MUST be present! Never skip redeemCode or expiryDate!

CRITICAL RULES:
1. ONLY extract data that EXISTS in the OCR text
2. DO NOT invent, generate, or hallucinate ANY data
3. COPY dates EXACTLY as written - do NOT change format or create timestamps
4. If data is missing, use null (NOT -1, NOT 0, NOT empty object)
5. NEVER use negative numbers or placeholder values like -1
6. Output ONLY the JSON object, NO explanations, NO extra text after JSON

Schema (all 6 keys MUST be present):
{"storeName":str|null,"description":str|null,"cashback":obj|null,"redeemCode":str|null,"expiryDate":str|null,"minOrderAmount":str|null}

EXTRACTION GUIDE:

storeName:
- Brand name ONLY (e.g., "PUMA", "Amazon", "Flipkart")
- Must appear in OCR text

redeemCode:
🚨 CRITICAL - MUST ALWAYS INCLUDE THIS KEY!
- Search for: "Code:", "Coupon:", or standalone alphanumeric codes
- Strip prefixes: "Code: KAPW1M3LAfAhSe" → "KAPW1M3LAfAhSe"
- Examples: "SAVE50", "BTXS5T13LI9V5", "KAPW1M3LAfAhSe"
- If NO code in OCR, use null (BUT key must be present!)
- DO NOT invent codes
- NEVER output "NULL" as a string - use null instead

expiryDate:
🚨 CRITICAL - MOST IMPORTANT FOR APP REMINDERS! MUST ALWAYS INCLUDE THIS KEY!
⚠️ Extract EXACTLY what you see in OCR. DO NOT change the date!

STEP 1: Find expiry text in OCR
- Look for: "Expires on", "Valid till", "Expires:", "Expiry:", "EXPIRES IN"

STEP 2: Extract ONLY day, month, year (remove time)
- Input: "Expires on 31 May, 2025, 11:59 PM"
- Output: "31 May 2025"
  
- Input: "Expires on 05 May, 2025, 11:59 PM"
- Output: "05 May 2025"

- Input: "Valid till 15 Dec 2025"
- Output: "15 Dec 2025"

STEP 3: Remove ALL extra text
- ❌ WRONG: "May,25|31" (mangled format!)
- ❌ WRONG: "May/16th @ 11.59 PM IST / End of the OFFER."
- ❌ WRONG: "May-31-2025T23:59Z"
- ❌ WRONG: Put it in the WRONG field
- ✅ CORRECT: "31 May 2025" in "expiryDate" field
- ✅ CORRECT: "05 May 2025" in "expiryDate" field

🚨 CRITICAL RULES:
1. MUST include "expiryDate" key (even if null)!
2. DO NOT change the date numbers (31 May → May,25|31 is WRONG!)
3. DO NOT add "@", "PM", "IST", "|", weird symbols
4. DO NOT put expiry in other fields - it goes in "expiryDate"!
5. ONLY output: day month year (e.g., "31 May 2025")

If NO date in OCR → use null (BUT key must be present!)

cashback:
⚠️ IMPORTANT: If you cannot CLEARLY identify the discount amount, set cashback to null
- Only extract if discount is EXPLICIT: "50% off", "₹200 off", "Flat 11% Off"
- If amount is unclear, misprinted, or ambiguous → use null
- If multiple amounts are confusing → use null
- The description field is more important than getting amount wrong

- Convert clear discounts to object:
  * Percentage: {"type":"percent","valueNum":50,"currency":null}
  * Amount: {"type":"amount","valueNum":200,"currency":"INR"}
- CRITICAL: valueNum must be a POSITIVE number (1 or greater)
- NEVER use: -1, 0, negative numbers, or placeholder values
- If NO discount exists in OCR → cashback must be null (NOT an object)

⚠️ SKIP CASHBACK IF:
- OCR text is garbled/unclear around numbers
- Multiple discount amounts are present (confusing)
- Numbers don't have clear "off"/"discount" keywords nearby
- Small numbers (< 5) near "Details", "Redeem Now" = APP RATINGS
- Numbers with stars (⭐) or near store names = RATINGS

description:
⭐ MOST IMPORTANT FIELD - Focus on getting this right!
- ❗ NEVER leave this empty if any offer text exists in OCR.
- ❗ Include the main discount sentence even if extra details exist.
- DO NOT return an empty string. If no offer text exists, use null (NOT "").
- Extract the FULL offer text from the coupon
- Combine multi-line text to form complete sentences
- Examples:
  * "Flat 50% off\non orders" → "Flat 50% off on orders"
  * "you won flat ₹100 off + ₹50 cashback\non your next order" → "Flat ₹100 Off + ₹50 Cashback on your next order"
  * "Flat 75% Off on Radiance Kit from beminimalist.co" → "Flat 75% Off on Radiance Kit from beminimalist.co"
- Include ALL key details: discount, product, conditions
- Clean up OCR noise but keep the offer intact
- DO NOT leave empty - if there's ANY offer text, extract it
- Only use null if absolutely no offer information exists

minOrderAmount:
- Minimum order value (e.g., "₹999", "₹500")
- If missing, use null

REMEMBER: ONLY extract data that you can SEE in the OCR text. DO NOT make up data.<|im_end|>
<|im_start|>user
Extract coupon from OCR:
$sanitizedOcr<|im_end|>
<|im_start|>assistant
{""".trimIndent()

    private suspend fun captureRawOcrText(bitmap: Bitmap): String? {
        customOcrTextProvider?.let { provider ->
            return runCatching { provider(bitmap) }.onFailure {
                Log.w(TAG, "Custom OCR text provider failed", it)
            }.getOrNull()
        }

        return try {
            val ocrText = performOfflineOcr(bitmap)
            if (ocrText.isBlank()) {
                Log.w(TAG, "OCR returned blank text during capture")
                null
            } else {
                ocrText
            }
        } catch (ocrError: Exception) {
            Log.w(TAG, "OCR capture failed, attempting fallback", ocrError)
            runCatching {
                val fallbackService = ModelBasedOCRService(context, ocrEngine)
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

    private fun sanitizeOcrSnippet(ocrText: String): String {
        if (ocrText.isBlank()) return "(no OCR text captured)"
        val normalized = ocrText.trim().replace("\r", "")
        return if (normalized.length <= OCR_SNIPPET_MAX_CHARS) normalized
        else normalized.substring(0, OCR_SNIPPET_MAX_CHARS).trimEnd() + "…"
    }

    private fun extractJsonSlice(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun removeDeprecatedKeys(jsonString: String): String {
        return try {
            val jsonObject = JSONObject(jsonString)
            jsonObject.remove("offerText")
            jsonObject.toString()
        } catch (parseError: JSONException) {
            var sanitized = jsonString.replace(Regex("\"offerText\"\\s*:\\s*\".*?\"\\s*,?"), "")
            sanitized = sanitized.replace(Regex(",\\s*,"), ",")
            sanitized = sanitized.replace(Regex("\\{\\s*,"), "{")
            sanitized = sanitized.replace(Regex(",\\s*\\}"), "}")
            sanitized
        }
    }

    /**
     * Parse LLM JSON response to CouponInfo object with strict validation
     */
    private fun parseLlmResponseToCouponInfo(response: String, rawOcrText: String?): CouponInfo {
        return try {
            // Clean response (remove any markdown formatting)
            var cleanResponse = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            // Prepend the assistant primer if response doesn't start with {
            // (we prime with `{"storeName":` in the prompt)
            if (!cleanResponse.startsWith("{")) {
                cleanResponse = """{"storeName":$cleanResponse"""
            }
            
            if (cleanResponse.length < 20) {
                throw IllegalStateException("LLM response too short to contain JSON (got ${cleanResponse.length} chars)")
            }
            
            val jsonCandidate = extractJsonSlice(cleanResponse)
                ?: throw IllegalStateException("No JSON object found in LLM output")
            val sanitizedJsonCandidate = removeDeprecatedKeys(jsonCandidate)
            
            // JSON validation: use schema-driven or legacy validator
            val json = if (USE_SCHEMA_VALIDATION) {
                // Schema-driven validation
                val validationResult = SchemaValidator.validate(sanitizedJsonCandidate, CouponSchema.SCHEMA)
                when (validationResult) {
                    is ValidationResult.Valid -> {
                        Log.d(TAG, "Schema validation passed")
                        JSONObject(sanitizedJsonCandidate)
                    }
                    is ValidationResult.Invalid -> {
                        Log.w(TAG, "Schema validation failed: ${validationResult.issues}")
                        throw IllegalArgumentException("Invalid JSON schema: ${validationResult.issues.joinToString(", ")}")
                    }
                }
            } else {
                // Legacy validation
                val parsedJson = CouponJsonValidator.parseStrict(sanitizedJsonCandidate)
                if (parsedJson == null) {
                    Log.w(TAG, "JSON failed strict validation, falling back to OCR")
                    throw IllegalArgumentException("Invalid JSON schema")
                }
                
                // Validate field constraints
                val validation = CouponJsonValidator.validateFieldConstraints(parsedJson)
                if (validation is JsonValidationResult.Invalid) {
                    Log.w(TAG, "Field validation failed: ${validation.issues}")
                    // Continue with warnings but don't fail completely
                }
                
                parsedJson
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
                cleanedDescription.isBlank() -> selectDescriptionFallback(rawOcrText)
                cleanedDescription.equals("Unknown", ignoreCase = true) -> selectDescriptionFallback(rawOcrText)
                GenericFieldHeuristics.isGenericOrMissing(cleanedDescription) -> selectDescriptionFallback(rawOcrText)
                else -> cleanedDescription.trim().replace(Regex("\\s+"), " ")
            }
            
        // Use universal code validation instead of brand-specific patterns
            val code = (json.optString("redeemCode").takeIf { it.isNotBlank() && it != "Unknown" }
                ?: json.optString("code").takeIf { it.isNotBlank() && it != "Unknown" })
                ?.let { rawCode ->
                    // Basic sanitization and universal validation
                    val sanitized = RedeemCodeSanitizer.sanitizePreserve(rawCode)
                    val repaired = sanitized ?: RedeemCodeRepair.repair(rawCode)
                    repaired?.takeIf { isValidUniversalCode(it) }?.also {
                        Log.d(TAG, "Validated universal code: $it")
                    }
                }
                ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            
            val expiryDate = json.optString("expiryDate").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
        val cashbackData = json.optJSONObject("cashback")
        val cashbackTriple = cashbackData?.let {
            val type = it.optString("type", "text")
            val valueNum = it.optDouble("valueNum", 0.0)
            val currency = it.optString("currency", "").takeIf { c -> c.isNotBlank() }
            Triple(type, valueNum, currency)
        }
        val validatedCashback = cashbackTriple?.takeIf { (type, valueNum, _) ->
            when (type) {
                "percent" -> valueNum > 0 && hasSupportingDigits("${valueNum}", rawOcrText, cleanedDescription)
                "amount" -> valueNum > 0 && hasSupportingDigits("${valueNum}", rawOcrText, cleanedDescription)
                else -> true
            }
        }
            
            val minOrderAmount = json.optString("minOrderAmount").let {
                if (it.isBlank() || it == "Unknown") null else it
            }
            
            // Check for duplicate values between fields (common LLM issue)
            val finalStoreName = if (GenericFieldHeuristics.areDuplicateFields(storeName, code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$storeName' - downgrading store name")
                "Unknown Store"
            } else storeName
            
            val finalCode = if (GenericFieldHeuristics.areDuplicateFields(storeName, code) || GenericFieldHeuristics.isGenericOrMissingCode(code)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '$code' - clearing redeem code")
                null
            } else code
            
            // Parse expiry date with enhanced pattern matching
            val parsedExpiryDate = expiryDate?.let { dateStr ->
                try {
                    // First try extracting from the full text (in case LLM gave us a sentence)
                    var parseResult = IndianDateParser.extractExpiryFromText(dateStr)
                    
                    // If that fails, try parsing the string directly
                    if (parseResult.date == null) {
                        parseResult = IndianDateParser.parseExpiryIST(dateStr)
                    }
                    
                    // Also try extracting from the full description as a fallback
                    if (parseResult.date == null || parseResult.confidence < 0.5f) {
                        val descriptionResult = IndianDateParser.extractExpiryFromText(description)
                        if (descriptionResult.confidence > parseResult.confidence) {
                            parseResult = descriptionResult
                        }
                    }
                    
                    val parsedDate = parseResult.date
                    if (parsedDate != null) {
                        if (parseResult.confidence >= 0.2f) {
                            if (parseResult.confidence < 0.5f) {
                                Log.w(TAG, "Accepting low-confidence expiry: $dateStr -> $parsedDate (confidence: ${parseResult.confidence})")
                            } else {
                                Log.d(TAG, "Parsed expiry date: $dateStr -> $parsedDate (confidence: ${parseResult.confidence})")
                            }
                            java.util.Date.from(parsedDate.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant())
                        } else {
                            Log.w(TAG, "Discarding very low confidence expiry: $dateStr (confidence: ${parseResult.confidence})")
                            DateParser.parseDate(dateStr)
                        }
                    } else {
                        Log.w(TAG, "Failed to resolve expiry date after parsing attempts: $dateStr")
                        DateParser.parseDate(dateStr)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse expiry date: $dateStr", e)
                    null
                }
            }
            
            val (cashbackType, cashbackValue, _) = validatedCashback ?: Triple("text", 0.0, null)
            val normalizedCashback = when (cashbackType) {
                "percent" -> cashbackValue
                "amount" -> cashbackValue
                else -> null
            }

            return CouponInfo(
                storeName = finalStoreName,
                description = description,
                expiryDate = parsedExpiryDate,
                cashbackAmount = normalizedCashback,
                redeemCode = finalCode,
                minimumPurchase = parseNumericValue(minOrderAmount),
                discountType = when (cashbackType) {
                    "percent" -> "PERCENTAGE"
                    "amount" -> "AMOUNT"
                    else -> null
                }
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
     * Universal code validation - replaces brand-specific patterns
     * Uses learned patterns and general validation rules
     */
    private fun isValidUniversalCode(code: String): Boolean {
        if (code.isBlank()) return false
        
        // Basic format validation
        val basePattern = Regex("^[A-Z0-9][A-Z0-9_-]{3,23}$")
        if (!basePattern.matches(code)) {
            return false
        }
        
        // Reject obvious non-codes
        val junkPatterns = listOf(
            "VOUCHER", "COUPON", "OFFER", "DISCOUNT", "NEEDED", "USING", 
            "CODE", "PROMO", "SAVE", "GET", "BUY", "USE", "APPLY"
        )
        
        if (junkPatterns.any { code.contains(it, ignoreCase = true) }) {
            return false
        }
        
        // Must have some variety (not all same character)
        if (code.toSet().size < 2) {
            return false
        }
        
        // Reasonable length check
        if (code.length < 4 || code.length > 24) {
            return false
        }
        
        if ((code.count { it == '-' } + code.count { it == '_' }) > 6) {
            return false
        }
        
        return true
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
     * Repair incomplete JSON that was truncated due to token limits
     * Adds missing closing braces and quotes to make it parseable
     */
    private fun repairIncompleteJson(jsonStr: String): String {
        var repaired = jsonStr.trim()
        
        // Count opening and closing braces
        val openBraces = repaired.count { it == '{' }
        val closeBraces = repaired.count { it == '}' }
        val openBrackets = repaired.count { it == '[' }
        val closeBrackets = repaired.count { it == ']' }
        
        // Check if JSON is incomplete
        if (openBraces > closeBraces || openBrackets > closeBrackets) {
            Log.w(TAG, "⚠️ Incomplete JSON detected: { open=$openBraces, close=$closeBraces }")
            
            // Close any unclosed strings
            val quoteCount = repaired.count { it == '"' && (repaired.indexOf(it) == 0 || repaired[repaired.indexOf(it) - 1] != '\\') }
            if (quoteCount % 2 != 0) {
                repaired += "\""
                Log.d(TAG, "  → Added closing quote")
            }
            
            // Close any unclosed arrays
            repeat(openBrackets - closeBrackets) {
                repaired += "]"
                Log.d(TAG, "  → Added closing bracket")
            }
            
            // Close any unclosed objects
            repeat(openBraces - closeBraces) {
                repaired += "}"
                Log.d(TAG, "  → Added closing brace")
            }
            
            Log.i(TAG, "✅ Repaired JSON: $repaired")
        }
        
        return repaired
    }
    
    /**
     * CRITICAL FIX: Sanitize sentinel values that LLM uses as "missing data" placeholders
     * 
     * PROBLEMS:
     * 1. LLM generates {"cashback":{"valueNum":-1}} → validation fails → fallback
     * 2. LLM generates {"redeemCode":"NULL"} → wrong code shows in UI
     * 
     * STRATEGY: Convert sentinel values to null BEFORE validation
     */
    private fun sanitizeSentinelValues(json: org.json.JSONObject) {
        try {
            // 1. Sanitize cashback.valueNum: -1, 0, negative → null
            if (json.has("cashback") && !json.isNull("cashback")) {
                val cashback = json.optJSONObject("cashback")
                if (cashback != null) {
                    val valueNum = cashback.optDouble("valueNum", 0.0)
                    
                    if (valueNum <= 0) {
                        Log.w(TAG, "⚠️ Sanitizing invalid cashback.valueNum: $valueNum → null")
                        json.put("cashback", org.json.JSONObject.NULL)
                    }
                }
            }
            
            // 2. Sanitize redeemCode: "NULL", "null", "N/A", "NA" → null
            if (json.has("redeemCode") && !json.isNull("redeemCode")) {
                val code = json.optString("redeemCode", "")
                val sentinelCodes = setOf("NULL", "null", "Null", "N/A", "NA", "n/a", "na", "NONE", "None", "none")
                
                if (code in sentinelCodes) {
                    Log.w(TAG, "⚠️ Sanitizing sentinel redeemCode: '$code' → null")
                    json.put("redeemCode", org.json.JSONObject.NULL)
                }
            }
            
            // 3. Sanitize description: "null", "NULL" → null (already handled in cleanDescription)
            // No action needed here, cleanDescription handles it
            
        } catch (e: Exception) {
            Log.w(TAG, "Error sanitizing sentinel values", e)
        }
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
            // Use Tesseract for text recognition
            val ocrText = performOfflineOcr(bitmap)
            Log.d(TAG, "OCR extracted text: ${ocrText.take(100)}...")
            
            // Validate that we got real text, not empty/whitespace
            if (ocrText.isBlank()) {
                throw Exception("OCR returned blank text")
            }
            
            // Use existing TextExtractor to parse the OCR text
            val textExtractor = TextExtractor()
            val extractedInfo = textExtractor.extractCouponInfoSync(ocrText, captureTimestamp)
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
                val modelBasedService = ModelBasedOCRService(context, ocrEngine)
                val result = modelBasedService.processCouponImage(bitmap)
                val cleanedResult = result.copy(description = cleanDescription(result.description))
                Log.d(TAG, "Model-based OCR fallback result: $cleanedResult")
                return@withContext cleanedResult
            } catch (e2: Exception) {
                Log.e(TAG, "All OCR methods failed - propagating exception", e2)
                // Don't return hardcoded errors - propagate exception to allow progressive pipeline fallback
                throw e2
            }
        }
    }
    
    /**
     * Perform OCR on bitmap using Tesseract
     */
    private suspend fun performOfflineOcr(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val extractedText = ocrEngine.recognize(bitmap)
            Log.d(TAG, "Tesseract OCR success: ${extractedText.length} chars")
            extractedText
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract OCR failed", e)
            ""
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

    private fun selectDescriptionFallback(rawOcrText: String?): String {
        if (rawOcrText.isNullOrBlank()) {
            Log.w(TAG, "No OCR text available for description fallback")
            return "Coupon offer"
        }

        val lines = rawOcrText.lineSequence()
            .map { it.trim() }
            .filter { it.length in 12..220 }
            .filter { !GenericFieldHeuristics.isGenericOrMissing(it) }
            .toList()

        val scoredLines = lines.map { line ->
            var score = 0
            if (Regex("\\d").containsMatchIn(line)) score += 3
            if (Regex("%|₹|Rs|OFF|Flat|Buy|Cashback|Free|Code", RegexOption.IGNORE_CASE).containsMatchIn(line)) score += 3
            if (line.length > 60) score += 1
            if (line.contains("http", ignoreCase = true)) score -= 2
            score to line.replace(Regex("\\s+"), " ")
        }.sortedByDescending { it.first }

        val primary = scoredLines.firstOrNull { it.first > 0 }?.second
            ?: lines.firstOrNull()?.replace(Regex("\\s+"), " ")

        val description = primary ?: "Coupon offer"
        Log.d(TAG, "Using fallback description: '$description'")
        return description
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

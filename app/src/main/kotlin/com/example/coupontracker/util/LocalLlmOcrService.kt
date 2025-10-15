package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ExtractionContext
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.StructuredFieldExtractor
import com.example.coupontracker.extraction.validation.FieldValidationCoordinator
import com.example.coupontracker.extraction.validation.FieldValidationIssue
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.PromptGenerator
import com.example.coupontracker.schema.SchemaValidator
import com.example.coupontracker.schema.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
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

        fun cleanDescription(raw: String?): String {
            if (raw == null) {
                return ""
            }

            if (raw.equals("null", ignoreCase = true)) {
                return ""
            }

            // Preserve the verbatim coupon copy while normalizing line endings
            return raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        }

    }

    private fun CouponInfo.toCanonicalContract(): CouponInfo {
        return copy(
            cashbackAmount = null,
            category = null,
            rating = null,
            status = null,
            discountType = null,
            minimumPurchase = null,
            maximumDiscount = null,
            paymentMethod = null,
            platformType = null,
            usageLimit = null
        )
    }

    // Dependencies
    private val llmRuntime = injectedLlmRuntimeManager ?: LlmRuntimeManager.getInstance(context)
    private val telemetryService = injectedTelemetryService ?: LlmTelemetryService.getInstance(context)
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor() // Fallback
    private val structuredFieldExtractor = StructuredFieldExtractor()
    private val fieldValidationCoordinator = FieldValidationCoordinator(textExtractor)
    private var modelPinned = false
    
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
            val extractionContext = ExtractionContext(
                imageUri = "inline://llm",
                ocrText = rawOcrText,
                captureTimestamp = captureTimestamp
            )
            val structuredCandidatesDeferred = async(Dispatchers.Default) {
                structuredFieldExtractor.detectFieldsStructured(extractionContext)
            }
            
            // Step 4: Run LLM inference with timeout and memory tracking
            memoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val llmResponse = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) {
                llmRuntime.runTextInference(rawOcrText, prompt, keepLoaded = modelPinned)
            }

            // Step 5: Parse and validate response
            if (llmResponse != null) {
                val trimmedResponse = llmResponse.trimStart()
                if (!trimmedResponse.startsWith("{")) {
                    Log.e(TAG, "LLM response did not start with '{': ${trimmedResponse.take(200)}")
                    throw IllegalStateException("LLM response not JSON (missing opening brace)")
                }
                val structuredCandidates = runCatching { structuredCandidatesDeferred.await() }
                    .onFailure { error ->
                        Log.w(TAG, "Structured extraction failed: ${error.message}", error)
                    }
                    .getOrElse { emptyMap() }
                val couponInfo = parseLlmResponseToCouponInfo(
                    llmResponse,
                    rawOcrText,
                    captureTimestamp,
                    structuredCandidates
                )
                
                // CRITICAL: Detect mock responses and reject them
                if (MockLlmResponseDetector.isMockResponse(couponInfo)) {
                    Log.w(TAG, "Mock response signature: store='${couponInfo.storeName}', code='${couponInfo.redeemCode}', desc='${couponInfo.description}'")
                    Log.w(TAG, "⚠️ MOCK LLM RESPONSE DETECTED - Falling back to OCR")
                    return@coroutineScope ExtractResult.Failed(
                        stage = ExtractionStage.LLM,
                        error = IllegalStateException("Mock LLM response detected (placeholder runtime output)")
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
            val extractionContext = ExtractionContext(
                imageUri = "inline://llm",
                ocrText = ocrText,
                captureTimestamp = captureTimestamp
            )
            val structuredCandidatesDeferred = async(Dispatchers.Default) {
                structuredFieldExtractor.detectFieldsStructured(extractionContext)
            }
            
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
                llmRuntime.runTextInference(ocrText, prompt, keepLoaded = modelPinned)
            }
            val inferenceElapsed = System.currentTimeMillis() - inferenceStartTime
            Log.d(TAG, "⏱️  Inference completed in ${inferenceElapsed / 1000}s")

            // Step 6: Parse and validate response
            val couponInfo = if (llmResponse != null) {
                // We already have OCR text from Step 3, no need to extract again
                val structuredCandidates = runCatching { structuredCandidatesDeferred.await() }
                    .onFailure { error ->
                        Log.w(TAG, "Structured extraction failed: ${error.message}", error)
                    }
                    .getOrElse { emptyMap() }
                val parsedInfo = parseLlmResponseToCouponInfo(
                    llmResponse,
                    ocrText,
                    captureTimestamp,
                    structuredCandidates
                )
                
                // CRITICAL: Detect mock responses and fall back to OCR
                if (MockLlmResponseDetector.isMockResponse(parsedInfo)) {
                    Log.w(TAG, "Mock response signature: store='${parsedInfo.storeName}', code='${parsedInfo.redeemCode}', desc='${parsedInfo.description}'")
                    Log.w(TAG, "⚠️ MOCK LLM RESPONSE DETECTED - Falling back to OCR")
                    throw IllegalStateException("Mock LLM response detected (placeholder runtime output)")
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
        if (couponInfo.expiryDate != null) count++
        if (couponInfo.description.isNotBlank()) count++
        return count
    }
    
    /**
     * Detect mock/placeholder responses from stub JNI
     * Returns true if the response matches known mock patterns
     */
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
    private fun buildQwenPromptManual(sanitizedOcr: String): String = """
<|im_start|>system
You are a JSON extractor. Extract coupon data and output ONLY valid JSON.

🚨 REQUIRED JSON KEYS (always include these four keys):
1. "storeName" - Store/brand name exactly from the coupon
2. "redeemCode" - Coupon/promo code. Use null if no code is present
3. "expiryDate" - Expiry date text exactly as written (no reformatting)
4. "description" - Offer description verbatim, no extra math or commentary

CRITICAL RULES:
1. Output ONLY these four keys. No additional fields are allowed.
2. If data is missing, output null (never invent values or placeholders).
3. Preserve the coupon wording: do NOT add numbers together or rewrite text.
4. Do not change date formats. Copy the characters exactly as seen.
5. Output ONLY the JSON object. No explanations before or after the JSON.

Schema:
{"storeName":str|null,"redeemCode":str|null,"expiryDate":str|null,"description":str|null}

EXTRACTION GUIDE:

storeName:
- Brand name only (e.g., "PUMA", "Amazon", "Flipkart")
- Ignore partner logos or watermarks.

redeemCode:
- Search for "Code:", "Coupon:", or standalone alphanumeric codes.
- Strip prefixes and whitespace. Example: "Code: SAVE50" → "SAVE50".
- Use null when no code is visible.

expiryDate:
- Copy the date text exactly (e.g., "31 May 2025", "2025-12-31").
- If the coupon only says "Expires in 5 days", return null (the app will compute it).
- Never invent months or days.

description:
- Use the main offer sentence exactly as printed.
- Keep symbols like "₹", "+", "%".
- Do NOT append helper text like "(Hybrid)" or perform arithmetic.

Provide the JSON object now.
<|im_end|>
<|im_start|>user
OCR_TEXT:
$sanitizedOcr
<|im_end|>
""".trimIndent()

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

    private fun enforceCanonicalFields(jsonString: String): String {
        return try {
            val jsonObject = JSONObject(jsonString)
            val allowedKeys = setOf("storeName", "description", "redeemCode", "couponCode", "expiryDate")
            val keysToRemove = jsonObject.keys().asSequence()
                .filter { it !in allowedKeys }
                .toList()
            keysToRemove.forEach { jsonObject.remove(it) }

            if (jsonObject.has("couponCode") && !jsonObject.has("redeemCode")) {
                jsonObject.put("redeemCode", jsonObject.get("couponCode"))
            }
            jsonObject.remove("couponCode")

            jsonObject.toString()
        } catch (parseError: JSONException) {
            var sanitized = jsonString
            val removalPatterns = listOf(
                "\"offerText\"\\s*:\\s*\".*?\"\\s*,?",
                "\"cashback\"\\s*:\\s*\\{.*?\\}\\s*,?",
                "\"cashbackAmount\"\\s*:\\s*[^,{}]+,?",
                "\"minOrderAmount\"\\s*:\\s*[^,{}]+,?",
                "\"minimumPurchase\"\\s*:\\s*[^,{}]+,?",
                "\"maximumDiscount\"\\s*:\\s*[^,{}]+,?"
            )
            removalPatterns.forEach { pattern ->
                sanitized = sanitized.replace(Regex(pattern, RegexOption.DOT_MATCHES_ALL), "")
            }

            sanitized = sanitized.replace(Regex(",\\s*,+"), ",")
            sanitized = sanitized.replace(Regex("\\{\\s*,"), "{")
            sanitized = sanitized.replace(Regex(",\\s*\\}"), "}")
            sanitized = sanitized.replace(Regex("\"couponCode\""), "\"redeemCode\"")
            sanitized
        }
    }

    /**
     * Parse LLM JSON response to CouponInfo object with strict validation
     */
    private fun parseLlmResponseToCouponInfo(
        response: String,
        rawOcrText: String?,
        captureTimestamp: Date?,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>
    ): CouponInfo {
        return try {
            // Clean response (remove any markdown formatting)
            var cleanResponse = response.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            // Fix common JSON malformations from LLM
            // Step 1: Normalize mixed quotes to double quotes
            cleanResponse = cleanResponse
                .replace("'storeName'", "\"storeName\"")
                .replace("'description'", "\"description\"")
                .replace("'redeemCode'", "\"redeemCode\"")
                .replace("'expiryDate'", "\"expiryDate\"")
            
            // Step 2: Fix duplicate key like `{"storeName":"storeName": null`
            if (cleanResponse.contains("\"storeName\":\"storeName\":")) {
                cleanResponse = cleanResponse.replace("\"storeName\":\"storeName\":", "\"storeName\":")
            }
            
            // Step 3: Ensure opening brace exists
            if (!cleanResponse.trim().startsWith("{")) {
                cleanResponse = "{" + cleanResponse.trim()
            }
            
            // Step 4: Ensure closing brace exists
            if (!cleanResponse.trim().endsWith("}")) {
                cleanResponse = cleanResponse.trim() + "}"
            }
            
            if (cleanResponse.length < 20) {
                throw IllegalStateException("LLM response too short to contain JSON (got ${cleanResponse.length} chars)")
            }
            
            val jsonCandidate = extractJsonSlice(cleanResponse)
                ?: throw IllegalStateException("No JSON object found in LLM output")
            val sanitizedJsonCandidate = enforceCanonicalFields(jsonCandidate)
            
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
            
            sanitizeSentinelValues(json)

            val rawStoreName = json.optString("storeName").takeIf { it.isNotBlank() }
            val cleanedDescription = cleanDescription(json.optString("description", "")).takeIf { it.isNotBlank() }
            val codeCandidate = (json.optString("redeemCode").takeIf { it.isNotBlank() && it != "Unknown" }
                ?: json.optString("code").takeIf { it.isNotBlank() && it != "Unknown" })
                ?.let { rawCode ->
                    val sanitized = RedeemCodeSanitizer.sanitizePreserve(rawCode)
                    val repaired = sanitized ?: RedeemCodeRepair.repair(rawCode)
                    repaired?.takeIf { isValidUniversalCode(it) }
                }
            val sanitizedCodeCandidate = codeCandidate?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            val expiryCandidate = json.optString("expiryDate").takeIf { it.isNotBlank() && it != "Unknown" }

            val validationSummary = fieldValidationCoordinator.refine(
                initial = FieldValueBundle(
                    storeName = rawStoreName,
                    description = cleanedDescription,
                    redeemCode = sanitizedCodeCandidate,
                    expiryDateText = expiryCandidate
                ),
                rawOcrText = rawOcrText,
                captureTimestamp = captureTimestamp,
                structuredCandidates = structuredCandidates
            )

            validationSummary.issues.forEach { issue: FieldValidationIssue ->
                val sourceInfo = issue.replacementSource?.let { source -> " via $source" } ?: ""
                Log.w(TAG, "Field validation ${issue.severity} for ${issue.field}: ${issue.message}$sourceInfo")
            }

            val candidateStoreName = validationSummary.fields.storeName?.trim()
            val candidateDescription = validationSummary.fields.description
            val candidateCode = validationSummary.fields.redeemCode
            val candidateExpiry = validationSummary.fields.expiryDateText

            val cleanedCandidateDescription = cleanDescription(candidateDescription)
            val description = when {
                cleanedCandidateDescription.isBlank() -> selectDescriptionFallback(rawOcrText)
                cleanedCandidateDescription.equals("Unknown", ignoreCase = true) -> selectDescriptionFallback(rawOcrText)
                GenericFieldHeuristics.isGenericOrMissing(cleanedCandidateDescription) -> selectDescriptionFallback(rawOcrText)
                else -> cleanedCandidateDescription
            }

            val normalizedCode = candidateCode?.takeIf { isValidUniversalCode(it) }?.takeIf {
                !GenericFieldHeuristics.isGenericOrMissingCode(it)
            }

            val finalStoreCandidate = if (GenericFieldHeuristics.areDuplicateFields(candidateStoreName, normalizedCode)) {
                Log.w(TAG, "Detected duplicate store name and redeem code: '${candidateStoreName.orEmpty()}' - downgrading store name")
                null
            } else candidateStoreName

            val finalCode = if (
                GenericFieldHeuristics.areDuplicateFields(candidateStoreName, normalizedCode) ||
                GenericFieldHeuristics.isGenericOrMissingCode(normalizedCode)
            ) {
                if (!normalizedCode.isNullOrBlank()) {
                    Log.w(TAG, "Detected invalid or duplicate redeem code: '$normalizedCode' - clearing redeem code")
                }
                null
            } else normalizedCode

            val finalStoreName = finalStoreCandidate?.takeIf {
                it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it)
            } ?: "Unknown Store"

            // Parse expiry date with enhanced pattern matching
            val parsedExpiryDate = candidateExpiry?.let { dateStr ->
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

            val resolvedExpiry = parsedExpiryDate
                ?: resolveRelativeExpiry(rawOcrText, description, captureTimestamp)

            return CouponInfo(
                storeName = finalStoreName,
                description = description,
                expiryDate = resolvedExpiry,
                cashbackAmount = null,
                redeemCode = finalCode,
                minimumPurchase = null,
                discountType = null
            )

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse LLM JSON response: $response", e)
            throw IllegalStateException("Invalid JSON response from LLM: ${e.message}")
        }
    }

    private fun resolveRelativeExpiry(
        rawOcrText: String?,
        description: String,
        captureTimestamp: Date?
    ): Date? {
        if (captureTimestamp == null) return null

        val combinedText = listOfNotNull(rawOcrText, description)
            .joinToString("\n")
        if (combinedText.isBlank()) return null

        val relativePattern = Regex("(?i)(?:expire|expiring|valid)\\s+(?:in|within)\\s+(\\d+)\\s+days?")
        val match = relativePattern.find(combinedText) ?: return null
        val dayCount = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null

        return Calendar.getInstance().apply {
            time = captureTimestamp
            add(Calendar.DAY_OF_YEAR, dayCount)
        }.time.also {
            Log.d(TAG, "Resolved relative expiry using capture timestamp +$dayCount days → $it")
        }
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
                        .toCanonicalContract()
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
                    .toCanonicalContract()
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
            val loaded = llmRuntime.loadModel()
            if (loaded) {
                modelPinned = true
                Log.d(TAG, "LLM model pinned in memory for reuse")
            }
            loaded
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
        if (modelPinned) {
            llmRuntime.releaseModel()
            modelPinned = false
            Log.d(TAG, "LLM model reference released")
        }
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
            return ""
        }

        val normalized = rawOcrText.replace("\r\n", "\n").replace('\r', '\n')

        val lines = normalized.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .filter { !GenericFieldHeuristics.isGenericOrMissing(it) }
            .toList()

        val scoredLines = lines.map { line ->
            var score = 0
            if (Regex("\\d").containsMatchIn(line)) score += 3
            if (Regex("%|₹|Rs|OFF|Flat|Buy|Cashback|Free|Code", RegexOption.IGNORE_CASE).containsMatchIn(line)) score += 3
            if (line.length > 60) score += 1
            if (line.contains("http", ignoreCase = true)) score -= 2
            score to line
        }.sortedByDescending { it.first }

        val primary = scoredLines.firstOrNull { it.first > 0 }?.second
            ?: lines.firstOrNull()
            ?: normalized.trim()

        val description = primary ?: ""
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

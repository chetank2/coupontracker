package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ExtractionContext
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.StructuredFieldExtractor
import com.example.coupontracker.extraction.validation.BrandLexicon
import com.example.coupontracker.extraction.validation.FieldValidationCoordinator
import com.example.coupontracker.extraction.validation.FieldValidationIssue
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.extraction.validation.StoreNameResolver
import com.example.coupontracker.extraction.validation.StoreNameValidator
import com.example.coupontracker.extraction.validation.ValidationEventLogger
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.feedback.ValidatorFeedbackRecorder
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.PromptGenerator
import com.example.coupontracker.schema.SchemaValidator
import com.example.coupontracker.schema.ValidationResult
import com.example.coupontracker.llm.ModelInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local LLM OCR Service using Qwen2-1.5B (text-only)
 * Provides structured coupon extraction using on-device LLM with OCR input
 */
class LocalLlmOcrService(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val injectedLlmRuntimeManager: LlmRuntimeManager? = null,
    private val injectedTelemetryService: LlmTelemetryService? = null,
    private val customOcrTextProvider: (suspend (Bitmap) -> String?)? = null,
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder? = null
) {
    
    companion object {
        private const val TAG = "LocalLlmOcrService"

        // Inference timeout (90 seconds - matches production SLA with warmup)
        // First run: ~60s (model warmup), subsequent runs: ~10-20s
        // Increased from 60s after observing 68s warmups; stays aligned with docs
        private const val INFERENCE_TIMEOUT_MS = 90_000L
        private const val GRACE_PERIOD_AFTER_TIMEOUT_MS = 2_000L

        // Model version tracking
        private const val SERVICE_VERSION = "1.4.0"  // Qwen2.5 migration
        private const val SUPPORTED_MODEL_VERSION = "qwen25_1.5b_instruct_q4"

        // Shorten OCR snippet to keep prompts under ~300 tokens and reduce latency on Qwen
        private const val OCR_SNIPPET_MAX_CHARS = 1200
        
        // Feature flags for schema-driven architecture
        // Set to true to enable schema-driven prompts/validation, false to use manual/legacy
        private const val USE_SCHEMA_PROMPTS = true
        private const val USE_SCHEMA_VALIDATION = true

        // Prompt optimization: Use compact prompts to reduce token count (920 → ~350 tokens)
        // Compact prompts rely on grammar enforcement for structure, reducing verbosity
        private const val USE_COMPACT_PROMPTS = true

        private const val MAX_LLM_ATTEMPTS = 2

        private val STORE_CONTEXT_ANCHORS = listOf(
            "Claim Now",
            "Use Code",
            "Use coupon",
            "Shop Now",
            "Apply Code",
            "Apply coupon",
            "I'll use it later",
            "Get ",
            "Exclusive",
            "Limited time",
            "Reward"
        )

        private val STORE_FALLBACK_STOPWORDS = setOf(
            "ORDER",
            "RECEIVED",
            "ITEMS",
            "CLAIM",
            "NOW",
            "SAVE",
            "DISCOUNT",
            "OFF",
            "GET",
            "ADD",
            "DEL",
            "EXPLORE",
            "WHILE",
            "SCRATCH",
            "CARD",
            "GOOGLE",
            "ORDERED",
            "MINUTES"
        )

        fun cleanDescription(raw: String?): String {
            if (raw == null) {
                return ""
            }

            if (raw.equals("null", ignoreCase = true)) {
                return ""
            }

            val normalized = raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')

            val paragraphs = mutableListOf<String>()
            val currentLine = StringBuilder()

            fun flushCurrent() {
                if (currentLine.isNotEmpty()) {
                    paragraphs += currentLine.toString()
                    currentLine.setLength(0)
                }
            }

            normalized.split('\n').forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    flushCurrent()
                } else {
                    if (currentLine.isNotEmpty()) {
                        currentLine.append(' ')
                    }
                    currentLine.append(trimmed)
                }
            }

            flushCurrent()

            if (paragraphs.isEmpty()) {
                return ""
            }

            return paragraphs.joinToString(separator = "\n")
        }

    }

    private data class LlmInferenceOutcome(
        val response: String?,
        val memoryUsageMb: Long
    )

    private fun CouponInfo.toCanonicalContract(): CouponInfo {
        return copy(
            category = null,
            rating = null,
            status = null,
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
    private val brandLexicon = BrandLexicon.load(context)
    private val storeNameResolver = StoreNameResolver(
        StoreNameValidator(brandLexicon) { log ->
            runCatching {
                Log.d(
                    TAG,
                    "store-source=${log.source} candidate='${log.candidate}' issues=${log.issues}"
                )
            }
        }
    )
    private val validationEventLogger = ValidationEventLogger { event ->
        if (event.summary.issues.isEmpty()) {
            return@ValidationEventLogger
        }
        validatorFeedbackRecorder?.recordValidatorOverride(
            rawOcrText = event.rawOcrText,
            initialBundle = event.initial,
            summary = event.summary,
            structuredCandidates = event.structuredCandidates,
            metadata = mapOf(
                "serviceVersion" to SERVICE_VERSION,
                "validator" to "FieldValidationCoordinator",
                "modelVersion" to SUPPORTED_MODEL_VERSION
            )
        )
    }
    private val fieldValidationCoordinator = FieldValidationCoordinator(
        textExtractor,
        storeNameResolver,
        validationEventLogger = validationEventLogger
    )
    private var modelPinned = false
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmupMutex = Mutex()
    private val nativeSmokeTestTriggered = AtomicBoolean(false)
    
    init {
        Log.d(TAG, "🔍 LocalLlmOcrService initialization started")
        
        // Check if model is available
        val modelInfo = llmRuntime.getModelInfo()
        if (modelInfo.isAvailable) {
            Log.d(TAG, "✅ Local model detected: ${modelInfo.name}")
            Log.d(TAG, "   Version: ${modelInfo.version}")
            Log.d(TAG, "   Size: ${modelInfo.sizeMB}MB")
            Log.d(TAG, "   Loaded: ${modelInfo.isLoaded}")
            Log.d(TAG, "   JNI references: ${modelInfo.referenceCount}")
            if (modelInfo.isLoaded) {
                modelPinned = true
            } else {
                warmupScope.launch {
                    runCatching {
                        ensureModelWarm("service_init", null)
                    }.onFailure { warmupError ->
                        Log.w(TAG, "Deferred model warmup failed: ${warmupError.message}", warmupError)
                    }
                }
            }
            maybeScheduleNativeSmokeTest(modelInfo)
        } else {
            Log.w(TAG, "⚠️  Qwen model NOT available - extraction will use pattern fallbacks")
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

    private suspend fun ensureModelWarm(
        reason: String,
        progressCallback: ((LlmProgressUpdate) -> Unit)?
    ) {
        if (modelPinned || llmRuntime.getModelInfo().isLoaded) {
            return
        }

        warmupMutex.withLock {
            if (modelPinned || llmRuntime.getModelInfo().isLoaded) {
                return
            }

            notifyProgress(
                stage = LlmProgressStage.WARMING_UP,
                percent = 20,
                message = "Loading AI model…",
                progressCallback = progressCallback
            )

            val warmupStart = System.currentTimeMillis()
            val warmed = warmUpModel()
            val warmupDuration = System.currentTimeMillis() - warmupStart

            if (!warmed) {
                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = "AI model warmup failed",
                    progressCallback = progressCallback
                )
                throw IllegalStateException("Failed to warm up LLM model ($reason)")
            }

            notifyProgress(
                stage = LlmProgressStage.WARMING_UP,
                percent = 25,
                message = "AI model ready in ${(warmupDuration / 1000).coerceAtLeast(1)}s",
                progressCallback = progressCallback
            )
        }
    }

    private fun maybeScheduleNativeSmokeTest(modelInfo: ModelInfo) {
        if (!modelInfo.isAvailable) {
            return
        }
        if (!nativeSmokeTestTriggered.compareAndSet(false, true)) {
            return
        }

        warmupScope.launch {
            runCatching { runNativeSmokeTest(modelInfo) }
                .onFailure { error ->
                    Log.w(TAG, "JNI smoke test failed: ${error.message}", error)
                }
        }
    }

    private suspend fun runNativeSmokeTest(modelInfo: ModelInfo) {
        Log.i(TAG, "🧪 Running JNI smoke test for ${modelInfo.name} (${modelInfo.version})")
        val metadata = llmRuntime.getModelInfo()
        Log.i(
            TAG,
            "JNI metadata snapshot: name=${metadata.name}, version=${metadata.version}, available=${metadata.isAvailable}, loaded=${metadata.isLoaded}"
        )

        ensureModelWarm("jni_smoke", null)

        val smokePrompt = """
            You are CouponTracker's local Qwen assistant. Read the coupon text and return a single JSON object with the keys
            storeName, description, code, expiryDate. Put every savings, cashback, or amount detail into the description text.
            Ensure the response is valid JSON without comments or markdown.
        """.trimIndent()

        val smokeOcr = """
            Coupon Smoke Test: DemoMart promises ₹250 cashback on orders above ₹1,000. Use code DEMO250 before 25 Dec 2025.
        """.trimIndent()

        val response = withTimeoutOrNull(15_000L) {
            llmRuntime.runTextInference(smokeOcr, smokePrompt)
        }

        if (response == null) {
            Log.w(TAG, "JNI smoke test produced no response within timeout")
        } else {
            runCatching { JSONObject(response) }
                .onSuccess { json ->
                    val keys = mutableListOf<String>()
                    json.keys().forEachRemaining { keys += it }
                    Log.i(
                        TAG,
                        "JNI smoke test JSON keys=${keys.sorted()} preview=${response.take(160)}"
                    )
                }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "JNI smoke test response was not valid JSON: ${error.message}. payload=${response.take(160)}"
                    )
                }
        }

        if (!modelPinned) {
            runCatching { llmRuntime.releaseModel() }
                .onFailure { error -> Log.w(TAG, "Smoke test cleanup failed", error) }
        }
    }

    private suspend fun runLlmInferenceWithRetry(
        rawOcrText: String,
        prompt: String,
        progressCallback: ((LlmProgressUpdate) -> Unit)?
    ): LlmInferenceOutcome {
        var attempt = 0
        var lastMemoryUsage = 0L
        var response: String? = null

        while (attempt < MAX_LLM_ATTEMPTS && response == null) {
            lastMemoryUsage = llmRuntime.getMemoryStats().modelLoadedMemoryMB.toLong()
            val attemptStart = System.currentTimeMillis()

            var timedOut = false
            val candidateResponse = try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    llmRuntime.runTextInference(rawOcrText, prompt, keepLoaded = modelPinned)
                }
            } catch (timeout: TimeoutCancellationException) {
                timedOut = true
                Log.w(
                    TAG,
                    "LLM inference timed out after ${INFERENCE_TIMEOUT_MS}ms (attempt ${attempt + 1}/$MAX_LLM_ATTEMPTS)"
                )
                runCatching { llmRuntime.cancelOngoingInference() }
                    .onFailure { error -> Log.w(TAG, "Failed to cancel native inference", error) }
                runCatching { llmRuntime.resetAfterTimeout() }
                    .onFailure { error -> Log.w(TAG, "Failed to reset runtime after timeout", error) }
                null
            } catch (error: Exception) {
                throw error
            }

            val elapsed = System.currentTimeMillis() - attemptStart

            if (!timedOut && candidateResponse != null) {
                response = candidateResponse
                break
            }

            response = null
            telemetryService.recordTimeout(elapsed, lastMemoryUsage)
            val reason = if (timedOut) {
                "timed out after ${elapsed}ms (limit ${INFERENCE_TIMEOUT_MS}ms)"
            } else {
                "returned empty response (${elapsed}ms)"
            }
            Log.w(
                TAG,
                "LLM inference ${reason} (attempt ${attempt + 1}/$MAX_LLM_ATTEMPTS)"
            )

            modelPinned = false

            if (timedOut) {
                if (attempt + 1 < MAX_LLM_ATTEMPTS) {
                    Log.i(TAG, "Waiting ${GRACE_PERIOD_AFTER_TIMEOUT_MS}ms before retry to allow native threads to settle")
                    delaySafe(GRACE_PERIOD_AFTER_TIMEOUT_MS)
                }
            }

            if (attempt + 1 < MAX_LLM_ATTEMPTS) {
                notifyProgress(
                    stage = LlmProgressStage.WARMING_UP,
                    percent = 30,
                    message = "AI timeout – retrying…",
                    progressCallback = progressCallback
                )
                ensureModelWarm("timeout_retry", progressCallback)
            }

            attempt++
        }

        return LlmInferenceOutcome(response, lastMemoryUsage)
    }

    private suspend fun delaySafe(millis: Long) {
        if (millis <= 0) return
        runCatching { delay(millis) }
    }

    private fun notifyProgress(
        stage: LlmProgressStage,
        percent: Int?,
        message: String,
        progressCallback: ((LlmProgressUpdate) -> Unit)?
    ) {
        val update = LlmProgressUpdate(stage, percent?.coerceIn(0, 100), message)
        progressCallback?.invoke(update)
        telemetryService.recordProgress(update)
    }

    private fun shouldFallbackToLegacy(error: Throwable): Boolean {
        if (error is JSONException) {
            return true
        }

        val message = error.message?.lowercase(Locale.getDefault()) ?: return false
        return when (error) {
            is IllegalArgumentException -> message.contains("json") || message.contains("schema")
            is IllegalStateException -> message.contains("mock") ||
                message.contains("response") ||
                message.contains("json") ||
                message.contains("schema")
            else -> false
        }
    }
    
    /**
     * Process coupon image using local LLM with typed results
     * New entry point that returns ExtractResult for better error handling
     */
    suspend fun processCouponImageTyped(
        bitmap: Bitmap,
        captureTimestamp: Date? = null,
        progressCallback: ((LlmProgressUpdate) -> Unit)? = null
    ): ExtractResult = coroutineScope {
        val startTime = System.currentTimeMillis()
        var memoryUsage = 0L
        var extractedFieldCount = 0
        val triedStages = mutableListOf<String>()
        
        try {
            notifyProgress(
                stage = LlmProgressStage.PREPARING,
                percent = 5,
                message = "Preparing on-device AI extraction…",
                progressCallback = progressCallback
            )
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
                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = "AI model missing – download required",
                    progressCallback = progressCallback
                )
                return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = IllegalStateException("LLM model not available on device")
                )
            }

            ensureModelWarm("typed_inference", progressCallback)

            // Step 3: Capture OCR text (used for prompt and post-processing)
            notifyProgress(
                stage = LlmProgressStage.OCR,
                percent = 30,
                message = "Reading coupon text…",
                progressCallback = progressCallback
            )
            val rawOcrText = captureRawOcrText(bitmap)?.takeIf { it.isNotBlank() }
                ?: run {
                    notifyProgress(
                        stage = LlmProgressStage.FAILED,
                        percent = 100,
                        message = "OCR returned blank text",
                        progressCallback = progressCallback
                    )
                    return@coroutineScope ExtractResult.Failed(
                        stage = ExtractionStage.LLM,
                        error = IllegalStateException("OCR returned blank text; cannot build prompt")
                    )
                }
            notifyProgress(
                stage = LlmProgressStage.PROMPTING,
                percent = 45,
                message = "Asking the AI to structure the coupon…",
                progressCallback = progressCallback
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
            notifyProgress(
                stage = LlmProgressStage.INFERENCE,
                percent = 65,
                message = "Running on-device AI…",
                progressCallback = progressCallback
            )
            val llmOutcome = runLlmInferenceWithRetry(rawOcrText, prompt, progressCallback)
            memoryUsage = llmOutcome.memoryUsageMb
            val llmResponse = llmOutcome.response

            // Step 5: Parse and validate response
            if (llmResponse != null) {
                notifyProgress(
                    stage = LlmProgressStage.PARSING,
                    percent = 80,
                    message = "Interpreting AI response…",
                    progressCallback = progressCallback
                )
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
                    notifyProgress(
                        stage = LlmProgressStage.FAILED,
                        percent = 100,
                        message = "Detected placeholder AI response",
                        progressCallback = progressCallback
                    )
                    return@coroutineScope ExtractResult.Failed(
                        stage = ExtractionStage.LLM,
                        error = IllegalStateException("Mock LLM response detected (placeholder runtime output)")
                    )
                }
                
                extractedFieldCount = countExtractedFields(couponInfo)

                // Step 7: Quality validation
                notifyProgress(
                    stage = LlmProgressStage.VALIDATING,
                    percent = 90,
                    message = "Validating coupon fields…",
                    progressCallback = progressCallback
                )
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
                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = "AI inference exceeded 90s timeout",
                    progressCallback = progressCallback
                )
                return@coroutineScope ExtractResult.Failed(
                    stage = ExtractionStage.LLM,
                    error = Exception("LLM inference timeout after ${INFERENCE_TIMEOUT_MS}ms")
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)
            notifyProgress(
                stage = LlmProgressStage.FAILED,
                percent = 100,
                message = "AI extraction failed – please retry",
                progressCallback = progressCallback
            )

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
    }.also { result ->
        if (result is ExtractResult.Good || result is ExtractResult.LowQuality) {
            notifyProgress(
                stage = LlmProgressStage.COMPLETE,
                percent = 100,
                message = "Coupon extracted successfully",
                progressCallback = progressCallback
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

            ensureModelWarm("legacy_entry", null)

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
            Log.d(TAG, "🤖 Running Qwen text-only inference...")
            Log.d(TAG, "⏱️  First run: ~60s (model warmup)")
            Log.d(TAG, "⏱️  Subsequent runs: ~10s")
            Log.d(TAG, "⏳ Please wait... (max ${INFERENCE_TIMEOUT_MS / 1000}s)")
            Log.d(TAG, "========================================")
            val inferenceStartTime = System.currentTimeMillis()
            val llmOutcome = runLlmInferenceWithRetry(ocrText, prompt, progressCallback = null)
            val inferenceElapsed = System.currentTimeMillis() - inferenceStartTime
            memoryUsage = llmOutcome.memoryUsageMb
            val llmResponse = llmOutcome.response
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
            
            Log.d(TAG, "Qwen extraction completed successfully in ${duration}ms")
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
            val allowFallback = shouldFallbackToLegacy(e)

            if (!allowFallback) {
                telemetryService.recordInference(
                    durationMs = duration,
                    success = false,
                    errorType = errorType,
                    extractedFieldCount = extractedFieldCount,
                    memoryUsageMB = memoryUsage
                )
                throw e
            }

            Log.d(TAG, "Falling back to traditional OCR after invalid LLM response")
            val fallbackResult = fallbackToTraditionalOCR(bitmap, captureTimestamp)
            // TODO: revisit ML Kit fallback semantics now that LLm path is the primary extractor

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
     * Create optimized structured prompt for coupon extraction with strict schema that funnels savings into description only
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
            val allowedKeys = setOf(
                "storeName",
                "description",
                "redeemCode",
                "couponCode",
                "expiryDate",
                "storeNameSource",
                "storeNameEvidence",
                "needsAttention"
            )
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
            val baseJson = try {
                JSONObject(sanitizedJsonCandidate)
            } catch (jsonException: JSONException) {
                Log.w(TAG, "Unable to parse LLM JSON payload", jsonException)
                throw IllegalArgumentException("Invalid JSON schema: ${jsonException.message}")
            }
            
            // JSON validation: use schema-driven or legacy validator
            val json = if (USE_SCHEMA_VALIDATION) {
                enforceSchemaWithFallback(
                    jsonObject = baseJson,
                    rawOcrText = rawOcrText,
                    captureTimestamp = captureTimestamp,
                    structuredCandidates = structuredCandidates
                )
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

            val storeResolution = validationSummary.storeResolution
            val candidateStoreName = storeResolution.value?.trim()
            val candidateDescription = validationSummary.fields.description
            val candidateCode = validationSummary.fields.redeemCode
            val candidateExpiry = validationSummary.fields.expiryDateText

            val cleanedCandidateDescription = cleanDescription(candidateDescription)
            val descriptionIsMeaningful = GenericFieldHeuristics.isMeaningfulDescription(cleanedCandidateDescription)
            val description = when {
                cleanedCandidateDescription.isBlank() -> selectDescriptionFallback(rawOcrText)
                cleanedCandidateDescription.equals("Unknown", ignoreCase = true) -> selectDescriptionFallback(rawOcrText)
                GenericFieldHeuristics.isGenericOrMissing(cleanedCandidateDescription) -> selectDescriptionFallback(rawOcrText)
                !descriptionIsMeaningful -> {
                    Log.w(
                        TAG,
                        "LLM description lacked detail; falling back to OCR text: '$cleanedCandidateDescription'"
                    )
                    selectDescriptionFallback(rawOcrText)
                }
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

            val storeNeedsAttention = storeResolution.needsAttention || finalStoreName == "Unknown Store"
            val storeEvidence = storeResolution.evidence
            val storeSource = storeResolution.source

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
                cashbackDetail = null,
                redeemCode = finalCode,
                needsAttention = storeNeedsAttention,
                storeNameSource = storeSource,
                storeNameEvidence = storeEvidence,
                minimumPurchase = null
            )

        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse LLM JSON response: $response", e)
            throw IllegalStateException("Invalid JSON response from LLM: ${e.message}")
        }
    }

    private fun enforceSchemaWithFallback(
        jsonObject: JSONObject,
        rawOcrText: String?,
        captureTimestamp: Date?,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>
    ): JSONObject {
        var validationResult = SchemaValidator.validateObject(jsonObject, CouponSchema.SCHEMA)
        if (validationResult is ValidationResult.Valid) {
            Log.d(TAG, "Schema validation passed")
            return jsonObject
        }

        val issues = (validationResult as ValidationResult.Invalid).issues
        Log.w(TAG, "Schema validation failed: $issues")

        val recoverable = issues.filter { it.contains("storeName", ignoreCase = true) }
        val unrecoverable = issues - recoverable.toSet()
        if (unrecoverable.isNotEmpty()) {
            throw IllegalArgumentException("Invalid JSON schema: ${issues.joinToString(", ")}")
        }

        val fallbackStore = resolveStoreNameFallback(structuredCandidates, rawOcrText, captureTimestamp)
        if (fallbackStore != null) {
            Log.w(TAG, "Schema self-heal: injecting fallback store name '$fallbackStore'")
            jsonObject.put("storeName", fallbackStore)
            validationResult = SchemaValidator.validateObject(jsonObject, CouponSchema.SCHEMA)
            if (validationResult is ValidationResult.Valid) {
                Log.d(TAG, "Schema validation passed after storeName repair")
                return jsonObject
            }
            Log.w(TAG, "Fallback store name '$fallbackStore' still failed schema validation: ${(validationResult as ValidationResult.Invalid).issues}")
        } else {
            Log.w(TAG, "No fallback store candidate available to repair schema")
        }

        throw IllegalArgumentException("Invalid JSON schema: ${issues.joinToString(", ")}")
    }

    private fun resolveStoreNameFallback(
        structuredCandidates: Map<FieldType, List<FieldCandidate>>,
        rawOcrText: String?,
        captureTimestamp: Date?
    ): String? {
        val contextual = guessStoreFromOfferContext(rawOcrText)
        if (contextual != null) {
            return contextual
        }

        val structured = structuredCandidates[FieldType.STORE_NAME]
            .orEmpty()
            .sortedByDescending { it.confidence }
            .firstOrNull { isStoreCandidateAcceptable(it.value, rawOcrText) }
            ?.value
        if (structured != null) {
            return normalizeStoreCandidate(structured)
        }

        val fallbackFromText = runCatching {
            rawOcrText?.let { textExtractor.extractCouponInfoSync(it, captureTimestamp).storeName }
        }.getOrNull()

        return fallbackFromText?.takeIf { isStoreCandidateAcceptable(it, rawOcrText) }?.let(::normalizeStoreCandidate)
    }

    private fun guessStoreFromOfferContext(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null

        val uppercasePattern = Regex("""\b([A-Z][A-Z&']{2,})\b""")
        val candidates = mutableListOf<String>()

        STORE_CONTEXT_ANCHORS.forEach { anchor ->
            var searchIndex = rawText.indexOf(anchor, ignoreCase = true)
            while (searchIndex >= 0) {
                val windowStart = (searchIndex - 160).coerceAtLeast(0)
                val windowEnd = (searchIndex + anchor.length + 160).coerceAtMost(rawText.length)
                val window = rawText.substring(windowStart, windowEnd)
                uppercasePattern.findAll(window).forEach { match ->
                    val rawCandidate = match.value.trim()
                    if (isStoreCandidateAcceptable(rawCandidate, rawText)) {
                        candidates += rawCandidate
                    }
                }
                searchIndex = rawText.indexOf(anchor, searchIndex + anchor.length, ignoreCase = true)
            }
        }

        if (candidates.isEmpty()) {
            uppercasePattern.findAll(rawText).forEach { match ->
                val rawCandidate = match.value.trim()
                if (isStoreCandidateAcceptable(rawCandidate, rawText)) {
                    candidates += rawCandidate
                }
            }
        }

        if (candidates.isEmpty()) {
            return null
        }

        val ranked = candidates
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { it.key.length }
            )

        return ranked.firstOrNull()?.key?.let(::normalizeStoreCandidate)
    }

    private fun isStoreCandidateAcceptable(candidate: String?, fullText: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        val trimmed = candidate.trim()
        if (trimmed.length < 3 || trimmed.length > 40) return false
        if (!trimmed.any { it.isLetter() }) return false
        if (GenericFieldHeuristics.isGenericOrMissing(trimmed)) return false
        val upper = trimmed.uppercase(Locale.US)
        if (STORE_FALLBACK_STOPWORDS.contains(upper)) return false
        if (upper.any { it.isDigit() }) return false

        if (!trimmed.any { it.lowercaseChar() in "aeiouy" }) return false

        if (!fullText.isNullOrBlank()) {
            val occurrences = countOccurrences(fullText, trimmed)
            if (occurrences >= 3) {
                val longEnough = trimmed.length >= 6
                val multiWord = trimmed.contains(' ')
                if (!longEnough && !multiWord) {
                    return false
                }
            }
        }

        return true
    }

    private fun normalizeStoreCandidate(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val upper = trimmed.uppercase(Locale.US)
        if (upper == trimmed) {
            return trimmed
                .lowercase(Locale.US)
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { segment ->
                    segment.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                    }
                }
        }
        return trimmed
    }

    private fun countOccurrences(text: String, needle: String): Int {
        if (needle.isBlank()) return 0
        val pattern = Regex("\\b${Regex.escape(needle)}\\b", RegexOption.IGNORE_CASE)
        return pattern.findAll(text).count()
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
     * Legacy preprocessing for the MiniCPM vision model (kept for backward compatibility).
     * Qwen2.5 text inference does not invoke this path.
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
        
        Log.d(TAG, "Legacy MiniCPM preprocessing: ${originalWidth}x${originalHeight} -> ${newWidth}x${newHeight} (scale: $scale)")
        
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
            // 1. Sanitize redeemCode: "NULL", "null", "N/A", "NA" → null
            if (json.has("redeemCode") && !json.isNull("redeemCode")) {
                val code = json.optString("redeemCode", "")
                val sentinelCodes = setOf("NULL", "null", "Null", "N/A", "NA", "n/a", "na", "NONE", "None", "none")
                
                if (code in sentinelCodes) {
                    Log.w(TAG, "⚠️ Sanitizing sentinel redeemCode: '$code' → null")
                    json.put("redeemCode", org.json.JSONObject.NULL)
                }
            }
            
            // 2. Sanitize description: "null", "NULL" → null (already handled in cleanDescription)
            // No action needed here, cleanDescription handles it
            
        } catch (e: Exception) {
            Log.w(TAG, "Error sanitizing sentinel values", e)
        }
    }

    private fun hasSavingsDetail(info: CouponInfo): Boolean {
        if (!info.cashbackDetail.isNullOrBlank()) return true
        return GenericFieldHeuristics.hasMeaningfulCashback(info.description)
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
        val hasSavingsDetail = hasSavingsDetail(couponInfo)
        if (hasSavingsDetail) qualityScore += 25
        if (couponInfo.expiryDate != null) qualityScore += 10
        if (GenericFieldHeuristics.isMeaningfulDescription(couponInfo.description)) qualityScore += 10
        
        // Additional quality checks for boilerplate/generic content
        val hasGenericStoreName = GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)
        val hasGenericDescription = !GenericFieldHeuristics.isMeaningfulDescription(couponInfo.description)
        val hasGenericCode = GenericFieldHeuristics.isGenericOrMissing(couponInfo.redeemCode)
        val isCashbackDetailMissing = !hasSavingsDetail
        
        // Check for duplicate fields (already handled in parsing, but validate here too)
        val hasDuplicateFields = GenericFieldHeuristics.areDuplicateFields(
            couponInfo.storeName, couponInfo.redeemCode
        )
        
        Log.d(TAG, "Extraction quality score: $qualityScore/100")
        Log.d(TAG, "Generic checks - Store: $hasGenericStoreName, Desc: $hasGenericDescription, Code: $hasGenericCode, SavingsMissing: $isCashbackDetailMissing, Duplicates: $hasDuplicateFields")
        
        // Determine failure reasons for better telemetry
        when {
            // Complete failure - no meaningful data at all
            couponInfo.storeName == "Unknown Store" && 
            couponInfo.redeemCode.isNullOrBlank() && 
            isCashbackDetailMissing -> {
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
                !hasSavingsDetail(extractedInfo)) {
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
        val warmupStart = System.currentTimeMillis()
        return try {
            Log.d(TAG, "Warming up LLM model...")
            val loaded = llmRuntime.loadModel()
            val warmupDuration = System.currentTimeMillis() - warmupStart
            telemetryService.recordModelLoad(loaded, warmupDuration)
            if (loaded) {
                modelPinned = true
                Log.d(TAG, "LLM model pinned in memory for reuse (warmup ${warmupDuration}ms)")
            }
            loaded
        } catch (e: Exception) {
            val warmupDuration = System.currentTimeMillis() - warmupStart
            telemetryService.recordModelLoad(false, warmupDuration)
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
            telemetryService.recordModelUnload()
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
        if (hasSavingsDetail(couponInfo)) qualityScore += 20
        if (couponInfo.expiryDate != null) qualityScore += 15
        if (GenericFieldHeuristics.isMeaningfulDescription(couponInfo.description)) qualityScore += 10
        
        return qualityScore
    }
    
    /**
     * Calculate field-level confidences
     */
    private fun calculateFieldConfidences(couponInfo: CouponInfo): Map<String, Float> {
        return mapOf(
            "storeName" to if (GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)) 0.3f else 0.9f,
            "description" to if (GenericFieldHeuristics.isMeaningfulDescription(couponInfo.description)) 0.8f else 0.3f,
            "redeemCode" to if (couponInfo.redeemCode.isNullOrBlank()) 0.0f else 0.9f,
            "cashbackDetail" to if (hasSavingsDetail(couponInfo)) 0.8f else 0.1f,
            "expiryDate" to if (couponInfo.expiryDate != null) 0.7f else 0.1f
        )
    }
    
    /**
     * Determine the specific quality failure reason
     */
    private fun determineQualityReason(couponInfo: CouponInfo): QualityReason {
        val hasGenericStoreName = GenericFieldHeuristics.isGenericOrMissing(couponInfo.storeName)
        val hasGenericDescription = !GenericFieldHeuristics.isMeaningfulDescription(couponInfo.description)
        val hasGenericCode = GenericFieldHeuristics.isGenericOrMissing(couponInfo.redeemCode)
        val isCashbackDetailMissing = !hasSavingsDetail(couponInfo)
        val hasDuplicateFields = GenericFieldHeuristics.areDuplicateFields(
            couponInfo.storeName, couponInfo.redeemCode
        )
        
        return when {
            couponInfo.storeName == "Unknown Store" &&
            couponInfo.redeemCode.isNullOrBlank() &&
            isCashbackDetailMissing -> QualityReason.COMPLETE_EXTRACTION_FAILURE
            
            hasGenericStoreName && hasGenericCode && hasGenericDescription -> 
                QualityReason.ALL_GENERIC_CONTENT
            
            hasDuplicateFields -> QualityReason.DUPLICATE_FIELD_VALUES
            
            couponInfo.redeemCode.isNullOrBlank() && isCashbackDetailMissing ->
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
            .filter { GenericFieldHeuristics.isMeaningfulDescription(it) }
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

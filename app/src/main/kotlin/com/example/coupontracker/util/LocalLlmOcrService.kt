package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.coupontracker.analytics.TelemetryClient
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.ExtractionContext
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.PassOneUnavailableException
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
import com.example.coupontracker.ocr.OcrResultProcessor
import com.example.coupontracker.ocr.OcrResultProcessor.OcrTile
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.feedback.ValidatorFeedbackRecorder
import com.example.coupontracker.prompt.PromptBuilder
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.SchemaValidator
import com.example.coupontracker.schema.ValidationResult
import com.example.coupontracker.llm.ModelInfo
import com.example.coupontracker.llm.StartupMetricsLogger
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
import org.json.JSONArray
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
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder? = null,
    private val injectedPromptBuilder: PromptBuilder? = null,
    private val injectedTelemetryClient: TelemetryClient? = null
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

        private const val MIN_AVG_CONFIDENCE_FOR_PASS_ONE = 0.38f
        private const val MAX_UNKNOWN_GLYPH_RATE_FOR_PASS_ONE = 0.18f
        private const val MIN_ALPHANUMERIC_CHARS_FOR_PASS_ONE = 24

        private const val TELEMETRY_PASS_ONE_SUCCESS = "PassOneSuccess"
        private const val TELEMETRY_FALLBACK_MOCK = "FallbackDueToMock"
        private const val TELEMETRY_FALLBACK_LOW_OCR = "FallbackDueToLowOcr"
        private const val TELEMETRY_LLM_SKIPPED_UNAVAILABLE = "LlmSkippedUnavailable"
        private const val TELEMETRY_LLM_TIMEOUT_RETRY = "LlmTimeoutRetry"
        
        private const val USE_SCHEMA_VALIDATION = true

        private const val MAX_LLM_ATTEMPTS = 2

        private const val EXPANDED_TOKEN_BUDGET = 196

        private val CTA_SUFFIXES = listOf(
            "copy",
            "copy code",
            "tap to copy",
            "avail now",
            "apply now",
            "apply code",
            "subscribe now",
            "redeem now",
            "claim now",
            "grab deal",
            "shop now",
            "buy now",
            "get offer",
            "get deal",
            "use now",
            "use code",
            "apply",
            "redeem",
            "copy coupon"
        )

        @VisibleForTesting
        internal fun enforceCanonicalFieldsForTest(json: String): String {
            return try {
                val obj = org.json.JSONObject(json)
                val allowed = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
                val remove = obj.keys().asSequence().filter { it !in allowed }.toList()
                remove.forEach { obj.remove(it) }
                if (obj.has("couponCode") && !obj.has("redeemCode")) {
                    obj.put("redeemCode", obj.get("couponCode"))
                }
                obj.remove("couponCode")
                obj.toString()
            } catch (e: org.json.JSONException) {
                json
            }
        }

        @VisibleForTesting
        internal data class JsonRepairResult(
            val repairedJson: String,
            val wasTruncated: Boolean
        )

        @VisibleForTesting
        internal fun repairIncompleteJson(jsonStr: String): JsonRepairResult {
            var repaired = jsonStr.trim()
            var wasTruncated = false

            if (hasDanglingQuote(repaired)) {
                repaired += "\""
                wasTruncated = true
            }

            val openBrackets = repaired.count { it == '[' }
            val closeBrackets = repaired.count { it == ']' }
            if (openBrackets > closeBrackets) {
                repeat(openBrackets - closeBrackets) {
                    repaired += "]"
                }
                wasTruncated = true
            }

            val openBraces = repaired.count { it == '{' }
            val closeBraces = repaired.count { it == '}' }
            if (openBraces > closeBraces) {
                repeat(openBraces - closeBraces) {
                    repaired += "}"
                }
                wasTruncated = true
            }

            val lastCommaIndex = repaired.lastIndexOf(',')
            if (lastCommaIndex != -1) {
                val tail = repaired.substring(lastCommaIndex + 1)
                val trimmedTail = tail.trimStart()
                if (trimmedTail.startsWith('"') && !trimmedTail.contains(':')) {
                    val closingSuffix = trimmedTail.dropWhile { it != '}' && it != ']' }
                    repaired = repaired.substring(0, lastCommaIndex) + closingSuffix
                    wasTruncated = true
                }
            }

            var cleaned = repaired.replace(Regex(",\\s*(?=[}])"), "")
            cleaned = cleaned.replace(Regex(",\\s*(?=])"), "")

            return JsonRepairResult(cleaned, wasTruncated)
        }

        @VisibleForTesting
        internal fun isLikelyTruncatedJson(raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false

            if (hasDanglingQuote(trimmed)) return true

            val openBraces = trimmed.count { it == '{' }
            val closeBraces = trimmed.count { it == '}' }
            if (openBraces > closeBraces) return true

            val openBrackets = trimmed.count { it == '[' }
            val closeBrackets = trimmed.count { it == ']' }
            if (openBrackets > closeBrackets) return true

            if (!trimmed.endsWith("}")) return true

            val truncatedFieldPattern = Regex("\"[A-Za-z0-9_]+\"\\s*:\\s*\"[^\"]*$")
            if (truncatedFieldPattern.containsMatchIn(trimmed)) return true

            return false
        }

        private fun hasDanglingQuote(candidate: String): Boolean {
            var escaped = false
            var quoteCount = 0
            candidate.forEach { ch ->
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> quoteCount++
                }
            }
            return quoteCount % 2 != 0
        }

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

        private val STRING_SENTINELS = setOf(
            "UNKNOWN",
            "NOT AVAILABLE",
            "NA",
            "N/A",
            "NONE",
            "NULL",
            "NO CODE",
            "NO_CODE_NEEDED",
            "NO CODE NEEDED",
            "NOCO",
            "NO VALUE",
            "TBD",
            "-",
            "--"
        )

        private val CTA_SUFFIX_REGEX = listOf(
            Regex("""\s*(?:copy|tap to copy)$""", RegexOption.IGNORE_CASE),
            Regex("""\s*(?:copy code|copy coupon)$""", RegexOption.IGNORE_CASE),
            Regex("""\s*(?:avail now|apply now|subscribe now|redeem now|claim now|grab deal|shop now|buy now|get offer|get deal)$""",
                RegexOption.IGNORE_CASE)
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

    private data class PreparedPrompt(
        val rawText: String?,
        val prompt: PromptBuilder.Result
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
    private val promptBuilder = injectedPromptBuilder ?: PromptBuilder()
    private val telemetryClient = injectedTelemetryClient ?: TelemetryClient.getInstance(context)
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

            try {
                notifyProgress(
                    stage = LlmProgressStage.WARMING_UP,
                    percent = 20,
                    message = "Loading AI model…",
                    progressCallback = progressCallback
                )

                val warmupDuration = warmUpModel()
                val warmupMetrics = runTextWarmupPrompt()

                val readyMessage = "AI model ready in ${(warmupDuration / 1000).coerceAtLeast(1)}s @ ${"%.2f".format(warmupMetrics.tokensPerSecond)} tok/s"
                notifyProgress(
                    stage = LlmProgressStage.WARMING_UP,
                    percent = 25,
                    message = readyMessage,
                    progressCallback = progressCallback
                )
            } catch (error: PassOneUnavailableException) {
                telemetryClient.incrementCounter(
                    TELEMETRY_LLM_SKIPPED_UNAVAILABLE,
                    mapOf(
                        "reason" to (error.message ?: "unknown"),
                        "phase" to reason
                    )
                )
                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = error.message ?: "AI model warmup failed",
                    progressCallback = progressCallback
                )
                throw error
            } catch (error: Exception) {
                telemetryClient.incrementCounter(
                    TELEMETRY_LLM_SKIPPED_UNAVAILABLE,
                    mapOf(
                        "reason" to (error.message ?: "unknown_exception"),
                        "phase" to reason,
                        "exception" to error::class.simpleName
                    )
                )
                val unavailable = PassOneUnavailableException(
                    "Failed to warm up LLM model ($reason)",
                    error
                )
                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = unavailable.message ?: "AI model warmup failed",
                    progressCallback = progressCallback
                )
                throw unavailable
            }
        }
    }

    private data class WarmupPromptMetrics(
        val durationMs: Long,
        val tokensPerSecond: Double,
        val tokenCount: Int
    )

    private suspend fun runTextWarmupPrompt(): WarmupPromptMetrics {
        val warmupPrompt = """
            Return exactly one JSON object with keys storeName, description, redeemCode, expiryDate, storeNameSource, storeNameEvidence, needsAttention.
            Never output null or empty strings; use the literal string "unknown" only if the field is truly missing. Keep storeNameEvidence as an array with up to three short snippets (or [] when nothing reliable exists). needsAttention should only be true when the store looks uncertain.
        """.trimIndent()
        val warmupOcr = "WarmupCo weekend deal – 5% off everything. Redeem with code WARMUP5 before 31 Dec 2025."

        val start = System.currentTimeMillis()
        return try {
            val response = llmRuntime.runTextInference(
                ocrText = warmupOcr,
                prompt = warmupPrompt,
                keepLoaded = true
            ) ?: throw IllegalStateException("Warmup prompt returned null response")

            val duration = System.currentTimeMillis() - start
            val tokenCount = countTokens(warmupPrompt, warmupOcr, response)
            val tokensPerSecond = if (duration > 0) tokenCount / (duration / 1000.0) else 0.0

            Log.i(
                TAG,
                "✅ Text warmup prompt completed in ${duration}ms (~${"%.2f".format(tokensPerSecond)} tokens/sec, $tokenCount tokens)"
            )
            StartupMetricsLogger.logWarmupComplete(
                success = true,
                durationMs = duration,
                tokensPerSecond = tokensPerSecond,
                message = "Text warmup prompt"
            )

            WarmupPromptMetrics(duration, tokensPerSecond, tokenCount)
        } catch (error: Exception) {
            val duration = System.currentTimeMillis() - start
            StartupMetricsLogger.logWarmupComplete(
                success = false,
                durationMs = duration,
                message = "Text warmup prompt failed",
                throwable = error
            )
            Log.e(TAG, "Warmup prompt failed", error)
            throw PassOneUnavailableException("Warmup prompt failed", error)
        }
    }

    private fun countTokens(vararg segments: String?): Int {
        return segments.filterNotNull()
            .flatMap { it.split(Regex("\\s+")) }
            .count { it.isNotBlank() }
            .coerceAtLeast(1)
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
            Return one JSON object with keys storeName, description, redeemCode, expiryDate, storeNameSource, storeNameEvidence, needsAttention.
            Copy wording from the coupon text, never output null, and use the literal string "unknown" only if the value is truly missing. Keep storeNameEvidence as an array of up to three short snippets drawn from the text.
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
        progressCallback: ((LlmProgressUpdate) -> Unit)?,
        maxTokensOverride: Int? = null
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
                    llmRuntime.runTextInference(rawOcrText, prompt, keepLoaded = modelPinned, maxTokensOverride = maxTokensOverride)
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

            if (timedOut) {
                telemetryClient.incrementCounter(
                    TELEMETRY_LLM_TIMEOUT_RETRY,
                    mapOf(
                        "attempt" to (attempt + 1),
                        "maxAttempts" to MAX_LLM_ATTEMPTS
                    )
                )
            }

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

            notifyProgress(
                stage = LlmProgressStage.OCR,
                percent = 30,
                message = "Reading coupon text…",
                progressCallback = progressCallback
            )
            val preparedPrompt = preparePrompt(bitmap)
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
            val promptResult = preparedPrompt.prompt
            val metrics = promptResult.processedOcr

            if (!metrics.meetsQualityThreshold(
                    MIN_AVG_CONFIDENCE_FOR_PASS_ONE,
                    MAX_UNKNOWN_GLYPH_RATE_FOR_PASS_ONE,
                    MIN_ALPHANUMERIC_CHARS_FOR_PASS_ONE
                )
            ) {
                telemetryClient.incrementCounter(
                    TELEMETRY_FALLBACK_LOW_OCR,
                    mapOf(
                        "avgConfidence" to metrics.averageConfidence,
                        "unknownRate" to metrics.unknownGlyphRate,
                        "tiles" to metrics.tileCount,
                        "entry" to "typed"
                    )
                )

                notifyProgress(
                    stage = LlmProgressStage.FAILED,
                    percent = 100,
                    message = "OCR quality too low – using fallback",
                    progressCallback = progressCallback
                )

                val fallbackInfo = runCatching { fallbackToTraditionalOCR(bitmap, captureTimestamp) }
                    .onFailure { Log.w(TAG, "Fallback OCR failed after low-quality detection", it) }
                    .getOrElse { CouponInfo() }

                val signals = ExtractionSignals(
                    qualityScore = 0,
                    fieldConfidences = emptyMap(),
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    memoryUsageMB = memoryUsage.toFloat(),
                    stage = ExtractionStage.LLM,
                    nativeAvailable = llmRuntime.isModelAvailable(),
                    modelVersion = SUPPORTED_MODEL_VERSION
                )

                return@coroutineScope ExtractResult.LowQuality(
                    info = fallbackInfo,
                    reason = QualityReason.LOW_QUALITY_EXTRACTION,
                    signals = signals
                )
            }

            notifyProgress(
                stage = LlmProgressStage.PROMPTING,
                percent = 45,
                message = "Asking the AI to structure the coupon…",
                progressCallback = progressCallback
            )

            val normalizedOcr = promptResult.processedOcr.normalizedText
            val rawOcrText = preparedPrompt.rawText ?: normalizedOcr
            val extractionContext = ExtractionContext(
                imageUri = "inline://llm",
                ocrText = normalizedOcr,
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
            val llmOutcome = runLlmInferenceWithRetry(normalizedOcr, promptResult.prompt, progressCallback)
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
                val parsedResult = try {
                    parseWithOptionalRetry(
                        initialOutcome = llmOutcome,
                        normalizedOcr = normalizedOcr,
                        rawOcrText = rawOcrText,
                        prompt = promptResult.prompt,
                        captureTimestamp = captureTimestamp,
                        structuredCandidates = structuredCandidates,
                        allowTokenExpansion = true,
                        progressCallback = progressCallback
                    )
                } catch (schemaError: IllegalArgumentException) {
                    if (schemaError.message?.contains("invalid json schema", ignoreCase = true) == true) {
                        notifyProgress(
                            stage = LlmProgressStage.FAILED,
                            percent = 100,
                            message = "AI returned invalid schema",
                            progressCallback = progressCallback
                        )
                        return@coroutineScope ExtractResult.Failed(
                            stage = ExtractionStage.LLM,
                            error = schemaError
                        )
                    }
                    throw schemaError
                } catch (jsonError: IllegalStateException) {
                    if (jsonError.message?.contains("invalid json", ignoreCase = true) == true) {
                        notifyProgress(
                            stage = LlmProgressStage.FAILED,
                            percent = 100,
                            message = "AI response was not valid JSON",
                            progressCallback = progressCallback
                        )
                        return@coroutineScope ExtractResult.Failed(
                            stage = ExtractionStage.LLM,
                            error = jsonError
                        )
                    }
                    throw jsonError
                }
                memoryUsage = parsedResult.outcome.memoryUsageMb
                val couponInfo = parsedResult.couponInfo

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
                    qualityScore >= 70 -> {
                        telemetryClient.incrementCounter(
                            TELEMETRY_PASS_ONE_SUCCESS,
                            mapOf(
                                "avgConfidence" to metrics.averageConfidence,
                                "unknownRate" to metrics.unknownGlyphRate,
                                "tiles" to metrics.tileCount,
                                "entry" to "typed"
                            )
                        )
                        ExtractResult.Good(couponInfo, signals)
                    }
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
            val allowFallback = shouldFallbackToLegacy(e)
            val failureMessage = when {
                e is PassOneUnavailableException -> "AI unavailable – showing OCR results"
                e.message?.contains("timeout", ignoreCase = true) == true -> "AI timed out – showing OCR results"
                allowFallback -> "AI response invalid – showing OCR results"
                else -> "AI extraction failed – please retry"
            }
            notifyProgress(
                stage = LlmProgressStage.FAILED,
                percent = 100,
                message = failureMessage,
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

            if (bitmap.isRecycled) {
                throw IllegalArgumentException("Input bitmap is recycled")
            }

            if (!isServiceAvailable()) {
                throw IllegalStateException("LLM model not available on device")
            }

            ensureModelWarm("legacy_entry", null)

            val preparedPrompt = preparePrompt(bitmap)
                ?: throw IllegalStateException("OCR text extraction failed or returned empty text")
            val promptResult = preparedPrompt.prompt
            val metrics = promptResult.processedOcr

            Log.d(
                TAG,
                "OCR normalization: raw_chars=${preparedPrompt.rawText?.length ?: 0} cleaned_chars=${promptResult.processedOcr.normalizedText.length} lines=${metrics.mergedLines.size}"
            )

            if (!metrics.meetsQualityThreshold(
                    MIN_AVG_CONFIDENCE_FOR_PASS_ONE,
                    MAX_UNKNOWN_GLYPH_RATE_FOR_PASS_ONE,
                    MIN_ALPHANUMERIC_CHARS_FOR_PASS_ONE
                )
            ) {
                Log.w(
                    TAG,
                    "Bypassing LLM due to low OCR quality (avg_conf=${metrics.averageConfidence}, unknown_rate=${metrics.unknownGlyphRate}, alnum=${metrics.alphanumericCharCount})"
                )
                telemetryClient.incrementCounter(
                    TELEMETRY_FALLBACK_LOW_OCR,
                    mapOf(
                        "avgConfidence" to metrics.averageConfidence,
                        "unknownRate" to metrics.unknownGlyphRate,
                        "tiles" to metrics.tileCount,
                        "alnumChars" to metrics.alphanumericCharCount
                    )
                )

                val fallbackResult = fallbackToTraditionalOCR(bitmap, captureTimestamp)
                fallbackUsed = "LOW_OCR"
                extractedFieldCount = countExtractedFields(fallbackResult)
                val duration = System.currentTimeMillis() - startTime
                telemetryService.recordInference(
                    durationMs = duration,
                    success = false,
                    errorType = "LOW_OCR_QUALITY",
                    fallbackUsed = fallbackUsed,
                    extractedFieldCount = extractedFieldCount,
                    memoryUsageMB = memoryUsage
                )
                return@coroutineScope fallbackResult
            }

            val normalizedOcr = promptResult.processedOcr.normalizedText
            val rawOcrText = preparedPrompt.rawText ?: normalizedOcr
            val extractionContext = ExtractionContext(
                imageUri = "inline://llm",
                ocrText = normalizedOcr,
                captureTimestamp = captureTimestamp
            )
            val structuredCandidatesDeferred = async(Dispatchers.Default) {
                structuredFieldExtractor.detectFieldsStructured(extractionContext)
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "🤖 Running Qwen text-only inference...")
            Log.d(TAG, "⏱️  First run: ~60s (model warmup)")
            Log.d(TAG, "⏱️  Subsequent runs: ~10s")
            Log.d(TAG, "⏳ Please wait... (max ${INFERENCE_TIMEOUT_MS / 1000}s)")
            Log.d(TAG, "========================================")
            val inferenceStartTime = System.currentTimeMillis()
            val initialOutcome = runLlmInferenceWithRetry(normalizedOcr, promptResult.prompt, progressCallback = null)
            val structuredCandidates = runCatching { structuredCandidatesDeferred.await() }
                .onFailure { error ->
                    Log.w(TAG, "Structured extraction failed: ${error.message}", error)
                }
                .getOrElse { emptyMap() }

            val parsedResult = parseWithOptionalRetry(
                initialOutcome = initialOutcome,
                normalizedOcr = normalizedOcr,
                rawOcrText = rawOcrText,
                prompt = promptResult.prompt,
                captureTimestamp = captureTimestamp,
                structuredCandidates = structuredCandidates,
                allowTokenExpansion = true,
                progressCallback = null
            )

            val inferenceElapsed = System.currentTimeMillis() - inferenceStartTime
            memoryUsage = parsedResult.outcome.memoryUsageMb
            Log.d(TAG, "⏱️  Inference completed in ${inferenceElapsed / 1000}s")

            val couponInfo = parsedResult.couponInfo

            if (MockLlmResponseDetector.isMockResponse(couponInfo)) {
                Log.w(TAG, "Mock response signature: store='${couponInfo.storeName}', code='${couponInfo.redeemCode}', desc='${couponInfo.description}'")
                Log.w(TAG, "⚠️ MOCK LLM RESPONSE DETECTED - Falling back to OCR")
                telemetryClient.incrementCounter(
                    TELEMETRY_FALLBACK_MOCK,
                    mapOf(
                        "entry" to "typed",
                        "avgConfidence" to metrics.averageConfidence,
                        "unknownRate" to metrics.unknownGlyphRate,
                        "tiles" to metrics.tileCount
                    )
                )
                throw IllegalStateException("Mock LLM response detected (placeholder runtime output)")
            }

            validateExtractionQuality(couponInfo)
            extractedFieldCount = countExtractedFields(couponInfo)

            val duration = System.currentTimeMillis() - startTime
            telemetryService.recordInference(
                durationMs = duration,
                success = true,
                extractedFieldCount = extractedFieldCount,
                memoryUsageMB = memoryUsage
            )

            telemetryClient.incrementCounter(
                TELEMETRY_PASS_ONE_SUCCESS,
                mapOf(
                    "avgConfidence" to metrics.averageConfidence,
                    "unknownRate" to metrics.unknownGlyphRate,
                    "tiles" to metrics.tileCount,
                    "durationMs" to duration
                )
            )

            Log.d(TAG, "Qwen extraction completed successfully in ${duration}ms")
            couponInfo

        } catch (e: Exception) {
            Log.e(TAG, "LLM processing failed: ${e.message}", e)

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

            fallbackUsed = if (fallbackResult.storeName != "Unknown Store" || !fallbackResult.redeemCode.isNullOrBlank()) {
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
    private suspend fun preparePrompt(bitmap: Bitmap): PreparedPrompt? {
        val tiles = runCatching { ocrEngine.recognizeWithBoxes(bitmap) }
            .onFailure { error -> Log.w(TAG, "Failed to capture OCR tiles", error) }
            .getOrElse { emptyList() }
            .map { it.toPromptTile() }
            .filter { it.text.isNotBlank() }

        val rawText = captureRawOcrText(bitmap)
        val effectiveText = when {
            !rawText.isNullOrBlank() -> rawText
            tiles.isNotEmpty() -> tiles.joinToString(separator = "\n") { it.text }
            else -> null
        } ?: return null

        val promptResult = promptBuilder.build(effectiveText, tiles)
        return PreparedPrompt(rawText = rawText, prompt = promptResult)
    }

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

    private fun OcrTextSpan.toPromptTile(): OcrTile {
        val bounds = try {
            RectF(boundingBox)
        } catch (_: Throwable) {
            RectF()
        }
        return OcrTile(
            text = text,
            bounds = bounds,
            confidence = confidence.coerceIn(0f, 1f)
        )
    }

    private fun extractJsonSlice(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun enforceCanonicalFields(jsonString: String): String {
        return try {
            val jsonObject = JSONObject(jsonString)
            val allowedKeys = com.example.coupontracker.llm.CouponSchemaKeys.ALLOWED_SET + "couponCode"
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

            // Collapse raw newlines/tabs to spaces so JSON strings remain parseable
            cleanResponse = cleanResponse.replace("\r", " ").replace("\n", " ")
            
            if (cleanResponse.length < 20) {
                throw IllegalStateException("LLM response too short to contain JSON (got ${cleanResponse.length} chars)")
            }
            
            val jsonCandidate = extractJsonSlice(cleanResponse)
                ?: throw IllegalStateException("No JSON object found in LLM output")
            val repairResult = repairIncompleteJson(jsonCandidate)
            if (repairResult.wasTruncated) {
                Log.w(TAG, "Detected truncated JSON payload from LLM; applied minimal repair before parsing")
            }
            val sanitizedJsonCandidate = enforceCanonicalFields(repairResult.repairedJson)
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
     * CRITICAL FIX: Sanitize sentinel values that LLM uses as "missing data" placeholders
     * 
     * PROBLEMS:
     * 1. LLM generates {"cashback":{"valueNum":-1}} → validation fails → fallback
     * 2. LLM generates {"redeemCode":"NULL"} → wrong code shows in UI
     * 
     * STRATEGY: Convert sentinel values to null BEFORE validation
     */
    private fun sanitizeSentinelValues(json: JSONObject) {
        try {
            sanitizeStringField(json, "storeName", uppercase = true)
            sanitizeStringField(json, "description")
            sanitizeStringField(json, "redeemCode", uppercase = true)
            sanitizeStringField(json, "expiryDate")
            sanitizeStringField(json, "storeNameSource")
            sanitizeEvidenceArray(json)
        } catch (e: Exception) {
            Log.w(TAG, "Error sanitizing sentinel values", e)
        }
    }

    private data class ParsedLlmResult(
        val couponInfo: CouponInfo,
        val outcome: LlmInferenceOutcome
    )

    private fun sanitizeStringField(json: JSONObject, key: String, uppercase: Boolean = false) {
        if (!json.has(key) || json.isNull(key)) {
            return
        }

        val raw = json.optString(key, "")
        val stripped = stripCtaSuffixes(raw)
        val normalizedWhitespace = stripped.replace("\\s+".toRegex(), " ").trim()

        if (normalizedWhitespace.isEmpty() || isSentinelValue(normalizedWhitespace)) {
            Log.w(TAG, "⚠️ Sanitizing sentinel $key: '$raw' → null")
            json.put(key, JSONObject.NULL)
            return
        }

        val normalized = if (uppercase) normalizedWhitespace.uppercase(Locale.US) else normalizedWhitespace
        json.put(key, normalized)
    }

    private fun sanitizeEvidenceArray(json: JSONObject) {
        if (!json.has("storeNameEvidence") || json.isNull("storeNameEvidence")) {
            return
        }

        val originalArray = json.optJSONArray("storeNameEvidence") ?: return
        val sanitized = JSONArray()
        for (index in 0 until originalArray.length()) {
            val entry = stripCtaSuffixes(originalArray.optString(index, ""))
            val normalized = entry.replace("\\s+".toRegex(), " ").trim()
            if (normalized.isNotEmpty() && !isSentinelValue(normalized)) {
                sanitized.put(normalized)
            }
            if (sanitized.length() >= 3) {
                break
            }
        }
        json.put("storeNameEvidence", sanitized)
    }

    private fun isSentinelValue(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            return true
        }

        val normalized = value.trim().uppercase(Locale.US)
        if (normalized in STRING_SENTINELS) {
            return true
        }

        if (normalized.all { !it.isLetterOrDigit() }) {
            return true
        }

        return false
    }

    private suspend fun parseWithOptionalRetry(
        initialOutcome: LlmInferenceOutcome,
        normalizedOcr: String,
        rawOcrText: String,
        prompt: String,
        captureTimestamp: Date?,
        structuredCandidates: Map<FieldType, List<FieldCandidate>>,
        allowTokenExpansion: Boolean,
        progressCallback: ((LlmProgressUpdate) -> Unit)?
    ): ParsedLlmResult {
        var outcome = initialOutcome
        var tokenExpansionAvailable = allowTokenExpansion

        while (true) {
            val response = outcome.response ?: throw Exception("LLM inference timed out or returned null")

            if (tokenExpansionAvailable && isLikelyTruncatedJson(response)) {
                Log.w(TAG, "LLM response appears truncated; rerunning with expanded token budget")
                tokenExpansionAvailable = false
                outcome = runLlmInferenceWithRetry(
                    rawOcrText = normalizedOcr,
                    prompt = prompt,
                    progressCallback = progressCallback,
                    maxTokensOverride = EXPANDED_TOKEN_BUDGET
                )
                continue
            }

            try {
                val couponInfo = parseLlmResponseToCouponInfo(
                    response,
                    rawOcrText,
                    captureTimestamp,
                    structuredCandidates
                )
                return ParsedLlmResult(couponInfo, outcome)
            } catch (schemaError: IllegalArgumentException) {
                if (tokenExpansionAvailable && shouldRetryWithExpandedTokens(schemaError.message)) {
                    Log.w(TAG, "Retrying LLM parse with expanded token budget due to schema error: ${schemaError.message}")
                    tokenExpansionAvailable = false
                    outcome = runLlmInferenceWithRetry(
                        rawOcrText = normalizedOcr,
                        prompt = prompt,
                        progressCallback = progressCallback,
                        maxTokensOverride = EXPANDED_TOKEN_BUDGET
                    )
                    continue
                }
                throw schemaError
            } catch (jsonError: IllegalStateException) {
                if (tokenExpansionAvailable && shouldRetryWithExpandedTokens(jsonError.message)) {
                    Log.w(TAG, "Retrying LLM parse with expanded token budget due to JSON error: ${jsonError.message}")
                    tokenExpansionAvailable = false
                    outcome = runLlmInferenceWithRetry(
                        rawOcrText = normalizedOcr,
                        prompt = prompt,
                        progressCallback = progressCallback,
                        maxTokensOverride = EXPANDED_TOKEN_BUDGET
                    )
                    continue
                }
                throw jsonError
            }
        }
    }

    private fun shouldRetryWithExpandedTokens(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lowered = message.lowercase(Locale.US)
        return lowered.contains("invalid json") ||
            lowered.contains("unterminated string") ||
            lowered.contains("unterminated object") ||
            lowered.contains("unterminated")
    }

    private fun stripCtaSuffixes(value: String): String {
        var result = value.trim()
        var changed: Boolean
        do {
            changed = false
            for (suffix in CTA_SUFFIXES) {
                val updated = result.removeCaseInsensitiveSuffix(" $suffix")
                if (updated.length != result.length) {
                    result = updated
                    changed = true
                } else {
                    val alt = result.removeCaseInsensitiveSuffix(suffix)
                    if (alt.length != result.length) {
                        result = alt
                        changed = true
                    }
                }
            }
        } while (changed)

        result = result.replace("\\s+".toRegex(), " ").trim()
        return result
    }

    private fun String.removeCaseInsensitiveSuffix(suffix: String): String {
        if (suffix.isBlank() || this.length < suffix.length) return this
        val ending = this.substring(this.length - suffix.length)
        return if (ending.equals(suffix, ignoreCase = true)) {
            this.substring(0, this.length - suffix.length).trimEnd()
        } else {
            this
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
    suspend fun warmUpModel(): Long {
        val warmupStart = System.currentTimeMillis()
        Log.d(TAG, "Warming up LLM model...")

        try {
            val loaded = llmRuntime.loadModel()
            val warmupDuration = System.currentTimeMillis() - warmupStart
            telemetryService.recordModelLoad(loaded, warmupDuration)

            if (!loaded) {
                val message = "loadModel() returned false"
                StartupMetricsLogger.logWarmupComplete(
                    success = false,
                    durationMs = warmupDuration,
                    message = message
                )
                throw PassOneUnavailableException(message)
            }

            modelPinned = true
            Log.d(TAG, "LLM model pinned in memory for reuse (warmup ${warmupDuration}ms)")
            return warmupDuration
        } catch (error: PassOneUnavailableException) {
            throw error
        } catch (error: Exception) {
            val warmupDuration = System.currentTimeMillis() - warmupStart
            telemetryService.recordModelLoad(false, warmupDuration)
            Log.e(TAG, "Failed to warm up model", error)
            StartupMetricsLogger.logWarmupComplete(
                success = false,
                durationMs = warmupDuration,
                message = error.message,
                throwable = error
            )
            throw PassOneUnavailableException("Failed to warm up model", error)
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

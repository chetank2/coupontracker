package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.CashbackInfo
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.debug.ExtractionDebugRepository
import com.example.coupontracker.debug.ExtractionDebugScorer
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.universal.ExtractionContext
import com.example.coupontracker.universal.ExtractionCandidate
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.util.ExtractionPerformanceMonitor
import com.example.coupontracker.util.ExtractionMethod
import com.example.coupontracker.util.FeedbackType
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.BitmapManager
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.ExtractionConfig
import com.example.coupontracker.util.ExtractionLogBuffer
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.feedback.ValidatorFeedbackRecorder
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.IndianCurrencyParser
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.RunPath
import com.example.coupontracker.util.UriPersistenceManager
import com.example.coupontracker.util.LlmProgressUpdate
import com.example.coupontracker.util.normalizeExpiryDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,  // Tesseract OCR engine
    private val localLlmOcrService: LocalLlmOcrService,
    private val telemetryService: ExtractionTelemetryService,
    private val universalExtractionService: UniversalExtractionService,
    private val performanceMonitor: ExtractionPerformanceMonitor,
    private val analyticsTracker: AnalyticsTracker,
    private val bitmapManager: com.example.coupontracker.util.BitmapManager,  // V2: Injected bitmap memory management
    private val debugRepository: ExtractionDebugRepository,
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context, ocrEngine)
    private val detectorInitializationResult = initializeTwoStageDetector()
    private val twoStageDetector: TwoStageDetector? = detectorInitializationResult.detector
    private val detectorInitErrorMessage: String? = detectorInitializationResult.errorMessage
    private val uriPersistenceManager = UriPersistenceManager(context)
    private val fieldHeuristics: GenericFieldHeuristics = GenericFieldHeuristics
    private val manualOverrides = mutableMapOf<String, CouponInstance>()
    private var pendingPreview: PendingPreview? = null

    // Store extraction results for feedback learning
    private var lastExtractionResult: Pair<com.example.coupontracker.universal.UniversalExtractionResult, String>? = null

    companion object {
        private const val TAG = "ScannerViewModel"
        private const val STRATEGY_SURFACE_SINGLE = "single_capture"

        @VisibleForTesting
        internal fun parseExpiryDate(
            dateString: String?,
            locale: Locale = Locale.getDefault()
        ): Date? {
            if (dateString.isNullOrBlank()) return null

            val cleanedDate = dateString
                .trim()
                .replace("\u202F", " ")
                .let {
                    val timeRegex = Regex("\\s*(?:at\\s*)?\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?(?:\\s*[A-Za-z]+)?$")
                    timeRegex.replace(it) { _ -> "" }.trim()
                }

            val dateFormats = listOf(
                "dd/MM/yyyy",
                "MM/dd/yyyy",
                "yyyy/MM/dd",
                "dd-MM-yyyy",
                "MM-dd-yyyy",
                "yyyy-MM-dd",
                "dd.MM.yyyy",
                "MM.dd.yyyy",
                "yyyy.MM.dd",
                "dd MMM yyyy",
                "dd MMMM yyyy",
                "dd MMM, yyyy",
                "dd MMMM, yyyy",
                "dd/MM/yy",
                "MM/dd/yy",
                "dd-MM-yy",
                "MM-dd-yy",
                "dd.MM.yy",
                "MM.dd.yy",
                "dd MMM yy",
                "dd MMMM yy"
            )

            val twoDigitYearStart = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2000)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            for (format in dateFormats) {
                try {
                    val sdf = SimpleDateFormat(format, locale)
                    sdf.isLenient = false
                    if (format.contains("yy") && !format.contains("yyyy")) {
                        sdf.set2DigitYearStart(twoDigitYearStart)
                    }
                    val parsed = sdf.parse(cleanedDate)
                    if (parsed != null) {
                        return parsed
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }

            return null // Don't return fallback date - use null if parsing fails
        }
    }

    private suspend fun logStrategyExecution(
        requested: com.example.coupontracker.util.ExtractionStrategy,
        executed: String,
        surface: String,
        reason: String? = null
    ) {
        val normalizedExecuted = executed.lowercase(Locale.getDefault())
        val baseMessage = "Strategy[$surface]: requested=${requested.name}, executed=$normalizedExecuted"
        val fullMessage = if (!reason.isNullOrBlank()) {
            "$baseMessage, reason=$reason"
        } else {
            baseMessage
        }

        Log.i(TAG, fullMessage)
        analyticsTracker.trackStrategyExecution(surface, requested, normalizedExecuted, reason)

        if (!requested.name.equals(normalizedExecuted, ignoreCase = true) && !reason.isNullOrBlank()) {
            analyticsTracker.trackStrategyFallback(surface, requested, normalizedExecuted, reason)
        }
    }

    private data class DetectorInitializationResult(
        val detector: TwoStageDetector?,
        val errorMessage: String?,
        val exception: Throwable?
    )

    private fun emitLlmProgress(update: LlmProgressUpdate) {
        viewModelScope.launch {
            _uiState.value = ScannerUiState.Scanning(update)
        }
    }

    private fun initializeTwoStageDetector(): DetectorInitializationResult {
        return try {
            DetectorInitializationResult(TwoStageDetector(context), null, null)
        } catch (e: IllegalStateException) {
            val message = e.message ?: "Multi-coupon detector assets are not available for this build."
            Log.e(TAG, "TwoStageDetector initialization blocked", e)
            telemetryService.trackExtractionResult(
                ExtractResult.Failed(
                    stage = ExtractionStage.TWO_STAGE_DETECTION,
                    error = e
                ),
                RunPath(
                    strategy = "LEGACY",
                    final = "two_stage_detector_initialization_failure",
                    reasons = mutableListOf("stub_mode_manifest")
                )
            )
            DetectorInitializationResult(null, message, e)
        } catch (e: Exception) {
            val message = e.message ?: "Failed to initialize multi-coupon detector."
            Log.e(TAG, "TwoStageDetector initialization failed", e)
            telemetryService.trackExtractionResult(
                ExtractResult.Failed(
                    stage = ExtractionStage.TWO_STAGE_DETECTION,
                    error = e
                ),
                RunPath(
                    strategy = "LEGACY",
                    final = "two_stage_detector_initialization_failure",
                    reasons = mutableListOf("unexpected_initialization_error")
                )
            )
            DetectorInitializationResult(null, message, e)
        }
    }

    init {
        // Assume network is available by default
        multiEngineOCR.setNetworkAvailability(true)

        // Surface detector initialization status
        detectorInitErrorMessage?.let { error ->
            val message = "Multi-coupon detection unavailable: $error"
            Log.e(TAG, message, detectorInitializationResult.exception)
            _uiState.value = ScannerUiState.Error(message)
        } ?: run {
            val modelInfo = twoStageDetector?.getModelInfo()
            Log.d(TAG, "TwoStageDetector initialized: $modelInfo")
        }
    }

    /**
     * Enhanced scan method that uses two-stage detection for multi-coupon support
     */
    fun scanImage(imageUri: Uri, persistImmediately: Boolean = true) {
        viewModelScope.launch {
            var bitmap: Bitmap? = null
            try {
                _uiState.value = ScannerUiState.Scanning()
                
                // V2: Get current extraction strategy
                val strategy = com.example.coupontracker.util.ExtractionConfig.getStrategy()
                Log.d(TAG, "Starting scan with strategy: ${strategy.name} for: $imageUri")

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_STARTED,
                    mapOf(
                        "strategy" to strategy.name.lowercase(Locale.getDefault()),
                        "persist_mode" to if (persistImmediately) "immediate" else "review"
                    )
                )

                // Load bitmap from URI
                bitmap = loadBitmapFromUri(imageUri) ?: run {
                    _uiState.value = ScannerUiState.Error("Could not load image")
                    return@launch
                }

                // V2: Route based on extraction strategy
                when (strategy) {
                    com.example.coupontracker.util.ExtractionStrategy.LEGACY -> {
                        logStrategyExecution(
                            requested = strategy,
                            executed = strategy.name.lowercase(Locale.getDefault()),
                            surface = STRATEGY_SURFACE_SINGLE
                        )
                        // LEGACY: Two-stage detection → LLM → OCR fallback
                        scanWithLegacyPath(imageUri, bitmap, persistImmediately)
                    }
                    com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST -> {
                        logStrategyExecution(
                            requested = strategy,
                            executed = strategy.name.lowercase(Locale.getDefault()),
                            surface = STRATEGY_SURFACE_SINGLE
                        )
                        // LLM_FIRST: LLM locates ROIs → OCR extracts → Fusion
                        scanWithLlmFirstPath(imageUri, bitmap, persistImmediately)
                    }
                    com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST -> {
                        logStrategyExecution(
                            requested = strategy,
                            executed = strategy.name.lowercase(Locale.getDefault()),
                            surface = STRATEGY_SURFACE_SINGLE
                        )
                        // OCR_FIRST: OCR finds text → LLM validates → Fusion
                        scanWithOcrFirstPath(imageUri, bitmap, persistImmediately)
                    }
                    com.example.coupontracker.util.ExtractionStrategy.HYBRID -> {
                        logStrategyExecution(
                            requested = strategy,
                            executed = strategy.name.lowercase(Locale.getDefault()),
                            surface = STRATEGY_SURFACE_SINGLE
                        )
                        // HYBRID: Parallel LLM + OCR → Fusion arbitrates
                        scanWithHybridPath(imageUri, bitmap, persistImmediately)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanced scanning", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_FAILED,
                    mapOf("reason" to (e.message ?: "unknown_error"))
                )
            } finally {
                // V2: Release bitmap after processing completes
                bitmap?.let { bm ->
                    bitmapManager.releaseBitmap(bm)
                    Log.d(TAG, "Released original bitmap for: $imageUri")
                }
            }
        }
    }
    
    /**
     * V2: LEGACY extraction path (current behavior)
     * Two-stage detection → process instances → fallback to universal/traditional OCR
     */
    private suspend fun scanWithLegacyPath(imageUri: Uri, bitmap: Bitmap, persistImmediately: Boolean) {
        Log.d(TAG, "LEGACY path: Running two-stage detection")

        val detector = twoStageDetector ?: run {
            val message = detectorInitErrorMessage ?: "Multi-coupon detector is unavailable."
            Log.e(TAG, "LEGACY path unavailable: $message")
            _uiState.value = ScannerUiState.Error(message)
            return
        }

        val detectionStart = System.currentTimeMillis()
        // Run two-stage detection
        val couponInstances = withContext(Dispatchers.IO) {
            detector.detectMultiCoupons(bitmap)
        }

        val detectionTime = System.currentTimeMillis() - detectionStart
        analyticsTracker.trackProcessingTime(detectionTime, "two_stage_detection")

        Log.d(TAG, "Two-stage detection completed: ${couponInstances.size} coupons detected")

        when {
            couponInstances.isEmpty() -> {
                Log.d(TAG, "No coupons detected, trying universal extraction first")
                tryUniversalExtraction(imageUri, bitmap)
            }
            couponInstances.size == 1 -> {
                val couponInstance = couponInstances.first()
                processSingleCoupon(
                    couponInstance = couponInstance,
                    imageUri = imageUri.toString(),
                    persistImmediately = persistImmediately
                )
            }
            else -> {
                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_MULTIPLE_COUPONS_DETECTED,
                    mapOf(
                        "count" to couponInstances.size,
                        "source" to "two_stage"
                    )
                )
                _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri.toString())
            }
        }
    }
    
    /**
     * V2: LLM_FIRST extraction path - REAL IMPLEMENTATION
     * LLM identifies fields directly → Validate with OCR for low-confidence fields → Learn patterns
     */
    private suspend fun scanWithLlmFirstPath(imageUri: Uri, bitmap: Bitmap, persistImmediately: Boolean) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "LLM_FIRST: Running MiniCPM for direct field extraction")
        
        try {
            // Step 1: Call LLM service to extract fields directly
            val llmResult = localLlmOcrService.processCouponImageTyped(bitmap) { update ->
                emitLlmProgress(update)
            }
            val processingTime = System.currentTimeMillis() - startTime
            
            when (llmResult) {
                is ExtractResult.Good -> {
                    val avgConfidence = llmResult.signals.fieldConfidences.values.average().toFloat()
                    Log.d(TAG, "LLM_FIRST: LLM extraction successful (confidence: $avgConfidence)")
                    
                    // Step 2: Build coupon from LLM result
                    val llmCoupon = buildCouponFromLlmResult(llmResult.info, imageUri)

                    // Step 3: Persist URI
                    val persistedUri = uriPersistenceManager.persistUri(imageUri)
                    val finalImageUri = resolveImageUri(persistedUri, imageUri)
                    val finalCoupon = finalizeCoupon(
                        base = llmCoupon.copy(imageUri = finalImageUri),
                        ocrText = null,
                        captureTimestamp = null
                    )

                    val debugSnapshot = ExtractionDebugScorer.fromLlmResult(llmResult, source = "llm_first")
                    val normalizedDescription = CouponDedupUtils.normalizeDescription(finalCoupon.description)

                    if (persistImmediately) {
                        persistCoupon(
                            coupon = finalCoupon,
                            normalizedDescription = normalizedDescription,
                            miniCpmStatus = MiniCpmProgress.SUCCESS,
                            debugSnapshot = debugSnapshot
                        )
                    } else {
                        pendingPreview = PendingPreview(
                            coupon = finalCoupon,
                            normalizedDescription = normalizedDescription,
                            miniCpmStatus = MiniCpmProgress.SUCCESS,
                            debugSnapshot = debugSnapshot
                        )
                        _uiState.value = ScannerUiState.Success(finalCoupon, MiniCpmProgress.SUCCESS)
                    }

                    // Step 4: Learn patterns (store extraction result for learning)
                    lastExtractionResult = com.example.coupontracker.universal.UniversalExtractionResult(
                        coupon = finalCoupon,
                        confidence = avgConfidence,
                        extractedFields = emptyMap(),
                        allCandidates = emptyMap(),
                        success = true
                    ) to ""
                    
                    // Step 5: Record metrics
                    val fieldsExtracted = mutableSetOf<String>()
                    if (finalCoupon.storeName != "Unknown Store") fieldsExtracted.add("storeName")
                    if (!finalCoupon.redeemCode.isNullOrBlank()) fieldsExtracted.add("redeemCode")
                    if (finalCoupon.getCashbackNumericValue() > 0) fieldsExtracted.add("cashback")
                    if (finalCoupon.expiryDate != null) fieldsExtracted.add("expiryDate")
                    
                    performanceMonitor.recordExtractionAttempt(
                        method = ExtractionMethod.LLM_DIRECT,
                        success = true,
                        confidence = avgConfidence,
                        processingTimeMs = processingTime,
                        fieldsExtracted = fieldsExtracted
                    )
                    
                    Log.d(TAG, "LLM_FIRST: Completed successfully in ${processingTime}ms")
                }
                
                is ExtractResult.LowQuality -> {
                    Log.w(TAG, "LLM_FIRST: Low quality result (${llmResult.reason}), falling back to universal")
                    
                    performanceMonitor.recordExtractionAttempt(
                        method = ExtractionMethod.LLM_DIRECT,
                        success = false,
                        confidence = 0.3f,
                        processingTimeMs = processingTime,
                        fieldsExtracted = emptySet()
                    )
                    
                    // Fallback to universal extraction
                    tryUniversalExtraction(imageUri, bitmap)
                }
                
                is ExtractResult.Failed -> {
                    Log.e(TAG, "LLM_FIRST: LLM failed at ${llmResult.stage}, falling back to universal")
                    
                    performanceMonitor.recordExtractionAttempt(
                        method = ExtractionMethod.LLM_DIRECT,
                        success = false,
                        confidence = 0f,
                        processingTimeMs = processingTime,
                        fieldsExtracted = emptySet()
                    )
                    
                    // Fallback to universal extraction
                    tryUniversalExtraction(imageUri, bitmap)
                    }
                }

            } catch (e: Exception) {
            Log.e(TAG, "LLM_FIRST: Exception during LLM extraction", e)
            val processingTime = System.currentTimeMillis() - startTime
            
            performanceMonitor.recordExtractionAttempt(
                method = ExtractionMethod.LLM_DIRECT,
                success = false,
                confidence = 0f,
                processingTimeMs = processingTime,
                fieldsExtracted = emptySet()
            )
            
            // Fallback to universal extraction
            tryUniversalExtraction(imageUri, bitmap)
        }
    }
    
    /**
     * Build coupon from LLM extraction result
     */
    private fun buildCouponFromLlmResult(
        couponInfo: CouponInfo,
        imageUri: Uri,
        fieldConfidences: Map<String, Float> = emptyMap()
    ): Coupon {
        // Parse cashback using typed info
        val cashbackInfo = if (couponInfo.cashbackAmount != null) {
            val amount = couponInfo.cashbackAmount
            if (couponInfo.discountType == "PERCENTAGE") {
                CashbackInfo(com.example.coupontracker.data.model.CashbackType.PERCENT, amount)
            } else {
                CashbackInfo(com.example.coupontracker.data.model.CashbackType.AMOUNT, amount, "INR")
            }
        } else {
            CashbackInfo(com.example.coupontracker.data.model.CashbackType.TEXT, 0.0)
        }
        
        // CouponInfo.expiryDate is already a Date?, not a string
        val expiryDate = couponInfo.expiryDate
        
        return Coupon(
            id = 0,
            storeName = couponInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store",
            description = couponInfo.description.takeIf { it.isNotBlank() } ?: "Extracted via LLM",
            expiryDate = expiryDate,
            cashbackAmount = cashbackInfo.valueNum,
            redeemCode = couponInfo.redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = imageUri.toString(),
            category = couponInfo.category,
            status = "Active",
            createdAt = Date(),
            updatedAt = Date(),
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency,
            needsAttention = couponInfo.needsAttention,
            storeNameSource = couponInfo.storeNameSource,
            storeNameEvidence = couponInfo.storeNameEvidence
        )
    }
    
    /**
     * V2: OCR_FIRST extraction path - REAL IMPLEMENTATION
     * OCR extracts all text → Universal detector applies patterns → Build coupon
     */
    private suspend fun scanWithOcrFirstPath(imageUri: Uri, bitmap: Bitmap, persistImmediately: Boolean) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "OCR_FIRST: Running multi-engine OCR for comprehensive text extraction")
        
        try {
            // Step 1: Run comprehensive OCR to get all text
            val ocrResult = withContext(Dispatchers.IO) {
                multiEngineOCR.processImage(bitmap)
            }
            
            when (ocrResult) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val ocrHints = ocrResult.extractedInfo
                    val ocrText = ocrResult.text
                    Log.d(TAG, "OCR_FIRST: Extracted ${ocrText.length} characters from OCR")
                    
                    if (ocrText.isBlank()) {
                        Log.w(TAG, "OCR_FIRST: No OCR text extracted, falling back to LLM")
                        scanWithLlmFirstPath(imageUri, bitmap, persistImmediately)
                        return
                    }
                    
                    // Step 2: Use universal field detector with OCR text
                    val context = buildUniversalExtractionContext(ocrText, ocrHints)
                    val extractionResult = universalExtractionService.extractCoupon(
                        image = bitmap,
                        ocrText = ocrText,
                        context = context
                    )
                    
                    val processingTime = System.currentTimeMillis() - startTime
                    
                    if (extractionResult.success && extractionResult.confidence > 0.4f) {
                        Log.d(TAG, "OCR_FIRST: Universal extraction successful (confidence: ${extractionResult.confidence})")
                        
                        // Step 3: Persist URI and save coupon
                        val persistedUri = uriPersistenceManager.persistUri(imageUri)
                        val finalImageUri = resolveImageUri(persistedUri, imageUri)
                        val confidenceBreakdown = buildConfidenceMap(
                            extractionResult.coupon.extractionConfidenceBreakdown,
                            extractionResult.extractedFields
                        )
                        val baseCoupon = extractionResult.coupon.copy(
                            imageUri = finalImageUri,
                            extractionConfidenceBreakdown = confidenceBreakdown
                        )
                        val finalCoupon = finalizeCoupon(
                            base = baseCoupon,
                            ocrText = ocrText,
                            captureTimestamp = null
                        ).ensureConfidenceBreakdown(confidenceBreakdown)
                        
                        // Step 4: Store for potential learning
                        lastExtractionResult = extractionResult.copy(coupon = finalCoupon) to ocrText
                        
                        // Step 5: Record metrics
                        val fieldsExtracted = mutableSetOf<String>()
                        if (finalCoupon.storeName != "Unknown Store") fieldsExtracted.add("storeName")
                        if (!finalCoupon.redeemCode.isNullOrBlank()) fieldsExtracted.add("redeemCode")
                        if (finalCoupon.getCashbackNumericValue() > 0) fieldsExtracted.add("cashback")
                        if (finalCoupon.expiryDate != null) fieldsExtracted.add("expiryDate")
                        
                        performanceMonitor.recordExtractionAttempt(
                            method = ExtractionMethod.OCR_PATTERN_MATCH,
                            success = true,
                            confidence = extractionResult.confidence,
                            processingTimeMs = processingTime,
                            fieldsExtracted = fieldsExtracted
                        )
                        
                        // Step 6: Update UI
                        _uiState.value = ScannerUiState.Success(finalCoupon, MiniCpmProgress.SUCCESS)
                        Log.d(TAG, "OCR_FIRST: Completed successfully in ${processingTime}ms")
                        
                    } else {
                        Log.w(TAG, "OCR_FIRST: Low confidence (${extractionResult.confidence}), falling back to LLM")
                        
                        performanceMonitor.recordExtractionAttempt(
                            method = ExtractionMethod.OCR_PATTERN_MATCH,
                            success = false,
                            confidence = extractionResult.confidence,
                            processingTimeMs = processingTime,
                            fieldsExtracted = emptySet()
                        )
                        
                        // Fallback to LLM for validation
                        scanWithLlmFirstPath(imageUri, bitmap, persistImmediately)
                    }
                }
                
                is MultiEngineOCR.OCRResult.Error -> {
                    val processingTime = System.currentTimeMillis() - startTime
                    Log.e(TAG, "OCR_FIRST: OCR failed - ${ocrResult.message}, falling back to LLM")
                    
                    performanceMonitor.recordExtractionAttempt(
                        method = ExtractionMethod.OCR_PATTERN_MATCH,
                        success = false,
                        confidence = 0f,
                        processingTimeMs = processingTime,
                        fieldsExtracted = emptySet()
                    )
                    
                    // Fallback to LLM when OCR fails
                    scanWithLlmFirstPath(imageUri, bitmap, persistImmediately)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "OCR_FIRST: Exception during OCR extraction", e)
            val processingTime = System.currentTimeMillis() - startTime
            
            performanceMonitor.recordExtractionAttempt(
                method = ExtractionMethod.OCR_PATTERN_MATCH,
                success = false,
                confidence = 0f,
                processingTimeMs = processingTime,
                fieldsExtracted = emptySet()
            )
            
            // Fallback to LLM on exception
            scanWithLlmFirstPath(imageUri, bitmap, persistImmediately)
        }
    }
    
    /**
     * V2: HYBRID extraction path - REAL IMPLEMENTATION
     * Parallel LLM + OCR → Fusion chooses best fields → Build coupon
     */
    private suspend fun scanWithHybridPath(imageUri: Uri, bitmap: Bitmap, persistImmediately: Boolean) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "HYBRID: Launching parallel LLM + OCR execution")
        
        try {
            // Step 1 & 2: Launch both extraction methods in parallel and await
            val (llmResult, ocrResult) = kotlinx.coroutines.coroutineScope {
                val llmDeferred = async(Dispatchers.Default) {
                    try {
                        Log.d(TAG, "HYBRID: Starting LLM extraction...")
                        localLlmOcrService.processCouponImageTyped(bitmap) { update ->
                            emitLlmProgress(update)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HYBRID: LLM extraction failed", e)
                        null
                    }
                }
                
                val ocrDeferred = async(Dispatchers.IO) {
                    try {
                        Log.d(TAG, "HYBRID: Starting OCR extraction...")
                        when (val ocrResult = multiEngineOCR.processImage(bitmap)) {
                            is MultiEngineOCR.OCRResult.Success -> {
                                val context = buildUniversalExtractionContext(ocrResult.text, ocrResult.extractedInfo)
                                universalExtractionService.extractCoupon(bitmap, ocrResult.text, context)
                            }
                            is MultiEngineOCR.OCRResult.Error -> null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "HYBRID: OCR extraction failed", e)
                        null
                    }
                }
                
                // Await both results
                Pair(llmDeferred.await(), ocrDeferred.await())
            }
            
            val processingTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "HYBRID: Both extractions completed in ${processingTime}ms")
            
            // Step 3: Fusion - Choose best fields from each method
            val fusedCoupon = when {
                // Both successful - fuse fields by choosing best confidence
                llmResult is ExtractResult.Good && ocrResult != null && ocrResult.success -> {
                    Log.d(TAG, "HYBRID: Both LLM and OCR successful, fusing results")
                    fuseLlmAndOcrResults(llmResult, ocrResult, imageUri)
                }
                
                // Only LLM successful
                llmResult is ExtractResult.Good -> {
                    Log.d(TAG, "HYBRID: Only LLM successful, using LLM result")
                    buildCouponFromLlmResult(
                        llmResult.info,
                        imageUri,
                        llmResult.signals.fieldConfidences
                    )
                }
                
                // Only OCR successful
                ocrResult != null && ocrResult.success -> {
                    Log.d(TAG, "HYBRID: Only OCR successful, using OCR result")
                    ocrResult.coupon
                }
                
                // Both failed
                else -> {
                    Log.w(TAG, "HYBRID: Both LLM and OCR failed")
                    null
                }
            }
            
            // Step 4: Process result
            if (fusedCoupon != null) {
                // Persist URI
                val persistedUri = uriPersistenceManager.persistUri(imageUri)
                val finalCoupon = finalizeCoupon(
                    base = fusedCoupon.copy(imageUri = resolveImageUri(persistedUri, imageUri)),
                    ocrText = null,
                    captureTimestamp = null
                )
                
                // Store for potential learning
                lastExtractionResult = com.example.coupontracker.universal.UniversalExtractionResult(
                    coupon = finalCoupon,
                    confidence = 0.8f,
                    extractedFields = emptyMap(),
                    allCandidates = emptyMap(),
                    success = true
                ) to ""
                
                // Record metrics
                val fieldsExtracted = mutableSetOf<String>()
                if (finalCoupon.storeName != "Unknown Store") fieldsExtracted.add("storeName")
                if (!finalCoupon.redeemCode.isNullOrBlank()) fieldsExtracted.add("redeemCode")
                if (finalCoupon.getCashbackNumericValue() > 0) fieldsExtracted.add("cashback")
                if (finalCoupon.expiryDate != null) fieldsExtracted.add("expiryDate")
                
                performanceMonitor.recordExtractionAttempt(
                    method = ExtractionMethod.HYBRID_FUSION,
                    success = true,
                    confidence = 0.8f, // Hybrid fusion confidence
                    processingTimeMs = processingTime,
                    fieldsExtracted = fieldsExtracted
                )
                
                // Update UI
                _uiState.value = ScannerUiState.Success(finalCoupon, MiniCpmProgress.SUCCESS)
                Log.d(TAG, "HYBRID: Completed successfully in ${processingTime}ms")
                
            } else {
                Log.w(TAG, "HYBRID: Both methods failed, falling back to LEGACY")
                logStrategyExecution(
                    requested = com.example.coupontracker.util.ExtractionStrategy.HYBRID,
                    executed = "legacy",
                    surface = STRATEGY_SURFACE_SINGLE,
                    reason = "hybrid_no_success"
                )

                performanceMonitor.recordExtractionAttempt(
                    method = ExtractionMethod.HYBRID_FUSION,
                    success = false,
                    confidence = 0f,
                    processingTimeMs = processingTime,
                    fieldsExtracted = emptySet()
                )
                
                // Fallback to LEGACY two-stage detection
                scanWithLegacyPath(imageUri, bitmap, persistImmediately)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "HYBRID: Exception during hybrid extraction", e)
            logStrategyExecution(
                requested = com.example.coupontracker.util.ExtractionStrategy.HYBRID,
                executed = "legacy",
                surface = STRATEGY_SURFACE_SINGLE,
                reason = "hybrid_exception_${e.javaClass.simpleName}"
            )
            val processingTime = System.currentTimeMillis() - startTime

            performanceMonitor.recordExtractionAttempt(
                method = ExtractionMethod.HYBRID_FUSION,
                success = false,
                confidence = 0f,
                processingTimeMs = processingTime,
                fieldsExtracted = emptySet()
            )
            
            // Fallback to LEGACY on exception
            scanWithLegacyPath(imageUri, bitmap, persistImmediately)
        }
    }
    
    /**
     * Fuse LLM and OCR results by choosing best field for each type
     */
    private fun fuseLlmAndOcrResults(
        llmResult: ExtractResult.Good,
        ocrResult: com.example.coupontracker.universal.UniversalExtractionResult,
        imageUri: Uri
    ): Coupon {
        val llmInfo = llmResult.info
        val ocrCoupon = ocrResult.coupon
        
        // For each field, choose the result with higher confidence
        val llmConf = llmResult.signals.fieldConfidences
        
        // Store name: prefer LLM if confident
        val storeName = if (llmConf.getOrDefault("storeName", 0f) > 0.6f && llmInfo.storeName.isNotBlank()) {
            llmInfo.storeName
        } else if (ocrCoupon.storeName != "Unknown Store") {
            ocrCoupon.storeName
        } else {
            llmInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store"
        }
        
        // Coupon code: prefer higher confidence
        val redeemCode = when {
            llmConf.getOrDefault("code", 0f) > 0.7f && !llmInfo.redeemCode.isNullOrBlank() -> llmInfo.redeemCode
            !ocrCoupon.redeemCode.isNullOrBlank() -> ocrCoupon.redeemCode
            else -> llmInfo.redeemCode
        }
        
        // Expiry date: prefer LLM if confident, else OCR (llmInfo.expiryDate is already a Date?)
        val expiryDate = if (llmConf.getOrDefault("expiry", 0f) > 0.6f && llmInfo.expiryDate != null) {
            llmInfo.expiryDate
        } else {
            ocrCoupon.expiryDate ?: llmInfo.expiryDate
        }
        
        // Cashback: prefer LLM if confident, else OCR
        val (cashbackAmount, cashbackInfo) = if (llmConf.getOrDefault("cashback", 0f) > 0.6f && llmInfo.cashbackAmount != null) {
            val amount = llmInfo.cashbackAmount
            val info = if (llmInfo.discountType == "PERCENTAGE") {
                CashbackInfo(com.example.coupontracker.data.model.CashbackType.PERCENT, amount)
            } else {
                CashbackInfo(com.example.coupontracker.data.model.CashbackType.AMOUNT, amount, "INR")
            }
            Pair(amount, info)
        } else if (ocrCoupon.getCashbackNumericValue() > 0) {
            Pair(ocrCoupon.getCashbackNumericValue(), ocrCoupon.getCashbackInfo())
        } else {
            Pair(0.0, CashbackInfo(com.example.coupontracker.data.model.CashbackType.TEXT, 0.0))
        }
        
        // Description: prefer raw LLM text, otherwise fall back to OCR text
        val description = listOf(llmInfo.description, ocrCoupon.description)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "Coupon offer"
        
        return Coupon(
            id = 0,
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = imageUri.toString(),
            category = llmInfo.category ?: ocrCoupon.category,
            status = "Active",
            createdAt = Date(),
            updatedAt = Date(),
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency
        )
    }

    /**
     * Process a captured bitmap to extract coupon information
     */
    fun processCapturedImage(
        bitmap: Bitmap,
        imageUri: Uri? = null,
        persistImmediately: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning()
                Log.d(TAG, "Processing captured bitmap with two-stage detection")

                val detector = twoStageDetector ?: run {
                    val message = detectorInitErrorMessage ?: "Multi-coupon detector is unavailable."
                    Log.e(TAG, "Captured image processing blocked: $message")
                    _uiState.value = ScannerUiState.Error(message)
                    return@launch
                }

                // Run two-stage detection
                val couponInstances = withContext(Dispatchers.IO) {
                    detector.detectMultiCoupons(bitmap)
                }

                Log.d(TAG, "Two-stage detection on bitmap completed: ${couponInstances.size} coupons detected")

                when {
                    couponInstances.isEmpty() -> {
                        // Try universal extraction first for bitmap processing
                        Log.d(TAG, "No coupons detected in bitmap, trying universal extraction")
                        if (imageUri != null) {
                            tryUniversalExtraction(imageUri, bitmap)
                        } else {
                            // Create temporary URI for bitmap
                            Log.d(TAG, "No imageUri provided, falling back to traditional OCR")
                        fallbackToTraditionalOCRBitmap(bitmap, imageUri)
                        }
                    }
                    couponInstances.size == 1 -> {
                        // Single coupon detected
                        val couponInstance = couponInstances.first()
                        processSingleCoupon(
                            couponInstance = couponInstance,
                            imageUri = imageUri?.toString(),
                            persistImmediately = persistImmediately
                        )
                    }
                    else -> {
                        // Multiple coupons detected
                        _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri?.toString())
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    /**
     * Process a single detected coupon instance
     */
    private suspend fun processSingleCoupon(
        couponInstance: CouponInstance,
        imageUri: String?,
        persistImmediately: Boolean
    ) {
        try {
            Log.d(TAG, "Processing single coupon with ${couponInstance.fields.size} detected fields")

            // Extract text from detected fields using OCR
            val extractionResult = extractTextFromFields(couponInstance)
            val debugSnapshot = extractionResult.debugSnapshot

            // Create coupon from extracted information
            val coupon = finalizeCoupon(
                base = createCouponFromInstance(
                    couponInstance = couponInstance,
                    extractionResult = extractionResult,
                    imageUri = imageUri
                ),
                ocrText = extractionResult.fields.values.joinToString("\n"),
                captureTimestamp = null
            )

            analyticsTracker.trackEvent(
                AnalyticsTracker.EVENT_COUPON_DETECTED,
                mapOf(
                    "source" to "two_stage",
                    "confidence" to String.format(Locale.US, "%.2f", couponInstance.confidence),
                    "mini_cpm" to extractionResult.miniCpmStatus.name,
                    "fields" to extractionResult.fields.size
                )
            )

            val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
            pendingPreview = null

            if (persistImmediately) {
                persistCoupon(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = extractionResult.miniCpmStatus,
                    debugSnapshot = debugSnapshot
                )
            } else {
                pendingPreview = PendingPreview(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = extractionResult.miniCpmStatus,
                    debugSnapshot = debugSnapshot
                )
                _uiState.value = ScannerUiState.Success(coupon, extractionResult.miniCpmStatus)
                Log.d(TAG, "Preview ready for review without immediate persistence")

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                    mapOf(
                        "persisted" to false,
                        "result" to "pending_review",
                        "mini_cpm" to extractionResult.miniCpmStatus.name
                    )
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing single coupon", e)
            _uiState.value = ScannerUiState.Error("Error processing coupon: ${e.message}")
        } finally {
            twoStageDetector?.releaseInstances(listOf(couponInstance))
        }
    }

    private suspend fun persistCoupon(
        coupon: Coupon,
        normalizedDescription: String,
        miniCpmStatus: MiniCpmProgress,
        debugSnapshot: ExtractionDebugSnapshot?
    ) {
        // First check if a duplicate already exists using the saveOrMerge helper
        val savedCouponId = couponRepository.saveOrMergeCoupon(
            coupon = coupon,
            normalizedDescription = normalizedDescription,
            imagePhash = null, // TODO: Add image hashing if needed
            imageSignature = null // TODO: Add image signature if needed
        )

        val savedCoupon = couponRepository.getCouponById(savedCouponId)

        debugSnapshot?.let { snapshot ->
            debugRepository.updateSnapshot(savedCouponId, snapshot)
        }

        var analyticsResult = "created"
        var persistedFlag = true

        if (savedCoupon != null) {
            val isDuplicate = savedCoupon.createdAt.before(coupon.createdAt ?: savedCoupon.createdAt)

            if (isDuplicate) {
                _uiState.value = ScannerUiState.AlreadySaved(savedCoupon, miniCpmStatus)
                Log.d(
                    TAG,
                    "Duplicate coupon detected, existing ID: ${savedCoupon.id}, store: ${savedCoupon.storeName}"
                )
                analyticsResult = "duplicate"
                persistedFlag = false
            } else {
                _uiState.value = ScannerUiState.Saved(savedCoupon)
                Log.d(TAG, "New coupon saved with ID: ${savedCoupon.id}, store: ${savedCoupon.storeName}")
                analyticsResult = "created"
            }
        } else {
            val fallbackCoupon = coupon.copy(id = savedCouponId)
            _uiState.value = ScannerUiState.Saved(fallbackCoupon)
            Log.d(TAG, "Coupon saved (fallback) with ID: $savedCouponId")
        }

        analyticsTracker.trackEvent(
            AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
            mapOf(
                "persisted" to persistedFlag,
                "result" to analyticsResult,
                "mini_cpm" to miniCpmStatus.name
            )
        )
    }

    fun confirmPreviewSave(updatedCoupon: Coupon? = null) {
        val preview = pendingPreview ?: return
        val couponToPersist = updatedCoupon ?: preview.coupon
        val normalizedDescription = CouponDedupUtils.normalizeDescription(couponToPersist.description)

        viewModelScope.launch {
            try {
                persistCoupon(
                    coupon = couponToPersist,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = preview.miniCpmStatus,
                    debugSnapshot = preview.debugSnapshot
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving preview coupon", e)
                _uiState.value = ScannerUiState.Error("Error saving coupon: ${e.message}")
            } finally {
                pendingPreview = null
            }
        }
    }

    fun clearPendingPreview() {
        pendingPreview = null
    }

    @VisibleForTesting
    internal fun setPendingPreviewForTest(coupon: Coupon, miniCpmStatus: MiniCpmProgress) {
        pendingPreview = PendingPreview(
            coupon = coupon,
            normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description),
            miniCpmStatus = miniCpmStatus,
            debugSnapshot = null
        )
    }

    /**
     * Handle selection of a specific coupon from multiple detected coupons
     */
    fun selectCoupon(selectedInstance: CouponInstance, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning()
                Log.d(TAG, "Processing selected coupon: ${selectedInstance.id}")

                // Process the selected coupon
                processSingleCoupon(
                    couponInstance = selectedInstance,
                    imageUri = originalImageUri,
                    persistImmediately = true
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected coupon", e)
                _uiState.value = ScannerUiState.Error("Error processing selected coupon: ${e.message}")
            }
        }
    }

    /**
     * Process all detected coupons and save them
     */
    fun processAllCoupons(couponInstances: List<CouponInstance>, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning()
                val adjustedInstances = getManualAdjustedInstances(couponInstances, includeManualExtras = true)
                Log.d(
                    TAG,
                    "Processing all ${adjustedInstances.size} detected coupons (requested ${couponInstances.size})"
                )

                processCouponBatch(adjustedInstances, originalImageUri)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing all coupons", e)
                _uiState.value = ScannerUiState.Error("Error processing coupons: ${e.message}")
            }
        }
    }

    fun processSelectedCoupons(couponInstances: List<CouponInstance>, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning()
                val adjustedInstances = getManualAdjustedInstances(couponInstances, includeManualExtras = false)
                Log.d(
                    TAG,
                    "Processing ${adjustedInstances.size} selected coupons (requested ${couponInstances.size})"
                )

                processCouponBatch(adjustedInstances, originalImageUri)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected coupons", e)
                _uiState.value = ScannerUiState.Error("Error processing coupons: ${e.message}")
            }
        }
    }

    private fun getManualAdjustedInstances(
        instances: List<CouponInstance>,
        includeManualExtras: Boolean
    ): List<CouponInstance> {
        if (manualOverrides.isEmpty()) {
            return instances
        }

        if (instances.isEmpty()) {
            return if (includeManualExtras) {
                manualOverrides.values.toList()
            } else {
                emptyList()
            }
        }

        val selectedIds = instances.mapTo(mutableSetOf()) { it.id }
        val adjusted = instances.map { instance ->
            manualOverrides[instance.id] ?: instance
        }.toMutableList()

        if (includeManualExtras) {
            manualOverrides.forEach { (id, override) ->
                if (selectedIds.add(id)) {
                    adjusted.add(override)
                }
            }
        }

        return adjusted
    }

    private suspend fun processCouponBatch(
        couponInstances: List<CouponInstance>,
        originalImageUri: String?
    ) {
        val processedResults = mutableListOf<CouponProcessingSummary>()

        for ((index, instance) in couponInstances.withIndex()) {
            try {
                val extractionResult = extractTextFromFields(instance)
                val coupon = createCouponFromInstance(instance, extractionResult, originalImageUri)

                couponRepository.insertCoupon(coupon)
                processedResults.add(CouponProcessingSummary(coupon, extractionResult.miniCpmStatus))

                Log.d(
                    TAG,
                    "Saved coupon ${processedResults.size}/${couponInstances.size}: ${coupon.redeemCode}"
                )

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_COUPON_DETECTED,
                    mapOf(
                        "source" to "multi_coupon_batch",
                        "mini_cpm" to extractionResult.miniCpmStatus.name,
                        "fields" to extractionResult.fields.size
                    )
                )
                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                    mapOf(
                        "persisted" to true,
                        "result" to "batch",
                        "mini_cpm" to extractionResult.miniCpmStatus.name
                    )
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon $index", e)
                // Continue with other coupons
                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_FAILED,
                    mapOf(
                        "reason" to (e.message ?: "batch_error"),
                        "stage" to "multi_coupon"
                    )
                )
            } finally {
                twoStageDetector?.releaseInstances(listOf(instance))
            }
        }

        if (processedResults.isNotEmpty()) {
            _uiState.value = ScannerUiState.AllCouponsSaved(processedResults)
        } else {
            _uiState.value = ScannerUiState.Error("Failed to save any coupons")
            analyticsTracker.trackEvent(
                AnalyticsTracker.EVENT_CAPTURE_FAILED,
                mapOf(
                    "reason" to "batch_no_results",
                    "stage" to "multi_coupon"
                )
            )
        }
    }

    /**
     * Extract text from detected fields using OCR with run-path telemetry
     */
    private suspend fun extractTextFromFields(couponInstance: CouponInstance): FieldExtractionResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val extractedInfo = mutableMapOf<String, String>()
            var progress = MiniCpmProgress.SUCCESS
            val triedStages = mutableListOf<String>()
            var finalStage = "UNKNOWN"
            var qualityScore: Int? = null
            var fieldConfidences: Map<String, Float> = emptyMap()
            var sourceStage: ExtractionStage? = null

            extractedInfo["minicpmConfidence"] = couponInstance.confidence.toString()
            extractedInfo["minicpmDetectionStatus"] = couponInstance.status.name

            try {
                // Try LLM extraction first
                triedStages.add("LLM")
                finalStage = "LLM"
                
                val result = localLlmOcrService.processCouponImageTyped(couponInstance.cropBitmap) { update ->
                    emitLlmProgress(update)
                }
                
                when (result) {
                    is ExtractResult.Good -> {
                        extractedInfo.putAll(mapCouponInfoToFields(result.info))
                        progress = MiniCpmProgress.SUCCESS
                        qualityScore = result.signals.qualityScore
                        fieldConfidences = result.signals.fieldConfidences
                        sourceStage = result.signals.stage
                        Log.d(TAG, "✅ LLM extraction successful (quality: ${result.signals.qualityScore})")
                    }

                    is ExtractResult.LowQuality -> {
                        extractedInfo.putAll(mapCouponInfoToFields(result.info))
                        progress = MiniCpmProgress.NEEDS_REVIEW
                        qualityScore = result.signals.qualityScore
                        fieldConfidences = result.signals.fieldConfidences
                        sourceStage = result.signals.stage

                        // Try fallback for low quality
                        triedStages.add("FALLBACK_OCR")
                        finalStage = "FALLBACK_OCR"
                        val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                        mergeValidatedFields(extractedInfo, fallbackFields)
                        
                        Log.w(TAG, "⚠️ LLM low quality (${result.reason}), used fallback")
                    }
                    
                    is ExtractResult.Failed -> {
                        // LLM failed, use fallback
                        triedStages.add("FALLBACK_OCR")
                        finalStage = "FALLBACK_OCR"
                        progress = MiniCpmProgress.FALLBACK
                        qualityScore = result.signals?.qualityScore
                        fieldConfidences = result.signals?.fieldConfidences ?: emptyMap()
                        sourceStage = result.signals?.stage ?: result.stage
                        val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                        extractedInfo.putAll(fallbackFields)

                        Log.e(TAG, "❌ LLM extraction failed: ${result.error.message}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "MiniCPM processing failed, using fallback", e)
                triedStages.add("FALLBACK_OCR")
                finalStage = "FALLBACK_OCR"
                progress = MiniCpmProgress.FALLBACK
                qualityScore = qualityScore ?: 0
                sourceStage = sourceStage ?: ExtractionStage.LLM
                val fallbackFields = runFallbackOcr(couponInstance.cropBitmap)
                extractedInfo.putAll(fallbackFields)
            }

            // Track run path telemetry
            val totalTime = System.currentTimeMillis() - startTime
            val runPath = RunPath(
                primary = "LLM",
                tried = triedStages,
                final = finalStage,
                nativeAvailable = localLlmOcrService.isServiceAvailable(),
                totalTimeMs = totalTime
            )

            telemetryService.trackRunPath(runPath)

            extractedInfo["minicpmProcessing"] = progress.name
            extractedInfo["runPath"] = "${runPath.strategy} → ${runPath.final}"
            extractedInfo["processingTimeMs"] = totalTime.toString()
            qualityScore?.let { extractedInfo["qualityScore"] = it.toString() }

            if (sourceStage == null && finalStage == "FALLBACK_OCR") {
                sourceStage = ExtractionStage.MLKIT
            }

            val baseResult = FieldExtractionResult(
                fields = extractedInfo.toMap(),
                miniCpmStatus = progress,
                runPath = runPath,
                qualityScore = qualityScore,
                fieldConfidences = fieldConfidences,
                sourceStage = sourceStage
            )

            val snapshot = ExtractionDebugScorer.fromFieldExtraction(baseResult, runPath)

            baseResult.copy(debugSnapshot = snapshot)
        }
    }

    private fun mapCouponInfoToFields(couponInfo: CouponInfo): MutableMap<String, String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fields = mutableMapOf<String, String>()

        val storeName = couponInfo.storeName
        if (storeName.isNotBlank() && !fieldHeuristics.isGenericOrMissing(storeName)) {
            fields["storeName"] = storeName
        }

        val description = couponInfo.description
        if (description.isNotBlank() && !fieldHeuristics.isGenericOrMissing(description)) {
            fields["description"] = description
        }

        couponInfo.redeemCode?.takeIf { it.isNotBlank() }?.let { fields["code"] = it }

        couponInfo.expiryDate?.let { date ->
            fields["expiryDate"] = formatter.format(date)
        }

        couponInfo.cashbackAmount?.takeIf { !GenericFieldHeuristics.isZeroOrMeaningless(it) }?.let {
            // Preserve type information for percentages
            if (couponInfo.discountType == "PERCENTAGE") {
                fields["amount"] = "${formatNumeric(it)}%"  // ✅ Preserve % for percentage
            } else {
                fields["amount"] = formatNumeric(it)  // Keep as number for amounts
            }
        }

        couponInfo.minimumPurchase?.takeIf { !GenericFieldHeuristics.isZeroOrMeaningless(it) }?.let {
            fields["minOrderAmount"] = formatNumeric(it)
        }

        couponInfo.paymentMethod?.takeIf { it.isNotBlank() }?.let { fields["paymentMethod"] = it }
        couponInfo.platformType?.takeIf { it.isNotBlank() }?.let { fields["platformType"] = it }
        couponInfo.status?.takeIf { it.isNotBlank() }?.let { status ->
            fields["status"] = status
        }

        return fields
    }

    private fun shouldFlagForReview(couponInfo: CouponInfo, fields: Map<String, String>): Boolean {
        val storeWeak = fieldHeuristics.isGenericOrMissing(fields["storeName"])
        val descriptionWeak = fieldHeuristics.isGenericOrMissing(fields["description"])
        val duplicateStoreAndCode = fieldHeuristics.areDuplicateFields(fields["storeName"], fields["code"])
        val codeMissing = couponInfo.redeemCode.isNullOrBlank()
        val amountWeak = GenericFieldHeuristics.isZeroOrMeaningless(couponInfo.cashbackAmount)

        return storeWeak || descriptionWeak || duplicateStoreAndCode || (codeMissing && amountWeak)
    }

    private suspend fun runFallbackOcr(bitmap: Bitmap): Map<String, String> {
        return when (val result = multiEngineOCR.processImage(bitmap)) {
            is MultiEngineOCR.OCRResult.Success -> result.extractedInfo
            is MultiEngineOCR.OCRResult.Error -> {
                Log.w(TAG, "Fallback OCR failed: ${result.message}")
                emptyMap()
            }
        }
    }

    @VisibleForTesting
    internal fun mergeValidatedFields(primary: MutableMap<String, String>, fallback: Map<String, String>) {
        val fallbackCode = fallback["code"]?.trim()?.takeIf { it.isNotEmpty() }

        fallback.forEach { (key, value) ->
            val sanitized = value.trim()
            if (sanitized.isEmpty()) {
                return@forEach
            }

            val existing = primary[key]
            val extractedCode = primary["code"]
            if (shouldReplaceExisting(existing, sanitized, key, extractedCode, fallbackCode)) {
                primary[key] = sanitized
            }
        }
    }

    @VisibleForTesting
    internal fun shouldReplaceExisting(
        existing: String?,
        candidate: String,
        key: String,
        extractedCode: String?,
        fallbackCode: String?
    ): Boolean {
        if (candidate.isBlank()) {
            return false
        }

        if (key == "storeName") {
            if (fieldHeuristics.areDuplicateFields(candidate, extractedCode) ||
                fieldHeuristics.areDuplicateFields(candidate, fallbackCode)
            ) {
                return false
            }
        }

        if (existing.isNullOrBlank()) {
            return true
        }

        return when (key) {
            "amount", "minOrderAmount" -> {
                val currentValue = extractNumericValue(existing)
                GenericFieldHeuristics.isZeroOrMeaningless(currentValue)
            }
            else -> fieldHeuristics.isGenericOrMissing(existing)
        }
    }

    private fun extractNumericValue(value: String?): Double? {
        return IndianCurrencyParser.parseAmount(value)
    }

    private fun formatNumeric(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    /**
     * Create a Coupon object from a detected coupon instance
     */
    private fun mergeConfidenceBreakdown(
        llmConf: Map<String, Float>,
        ocrResult: com.example.coupontracker.universal.UniversalExtractionResult?
    ): Map<String, Float> {
        if (llmConf.isEmpty() && (ocrResult?.extractedFields?.isEmpty() != false)) {
            return emptyMap()
        }

        val merged = mutableMapOf<String, Float>()
        ocrResult?.extractedFields?.forEach { (type, candidate) ->
            merged[type.name.lowercase(Locale.ROOT)] = candidate.confidence
        }
        merged.putAll(llmConf)
        return merged
    }

    private fun buildDetectionConfidenceBreakdown(couponInstance: CouponInstance): Map<String, Float> {
        if (couponInstance.fields.isEmpty()) {
            return emptyMap()
        }
        return couponInstance.fields.associate { detection ->
            detection.fieldType.name.lowercase(Locale.ROOT) to detection.confidence
        }
    }

    private fun createCouponFromInstance(
        couponInstance: CouponInstance,
        extractionResult: FieldExtractionResult,
        imageUri: String?
    ): Coupon {
        val extractedInfo = extractionResult.fields

        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])

        // Parse amount to double with Indian currency support
        val amount = extractedInfo["amount"]?.let {
            IndianCurrencyParser.parseAmount(it) ?: 0.0
        } ?: 0.0

        // Create typed cashback info from extracted amount
        val cashbackInfo = extractedInfo["amount"]?.let { amountText ->
            CashbackInfo.fromText(amountText)
        } ?: CashbackInfo.fromLegacyAmount(amount, extractedInfo["description"])

        val runPathSummary = extractionResult.runPath?.let { path ->
            buildString {
                append(path.strategy.ifBlank { "LLM" })
                if (path.final.isNotBlank()) {
                    append(" → ")
                    append(path.final)
                }
            }.ifBlank { null }
        }

        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: extractedInfo["app"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: extractedInfo["benefit"] ?: "Multi-coupon detected",
            expiryDate = expiryDate,
            cashbackAmount = amount, // Keep for backward compatibility
            redeemCode = extractedInfo["code"], // Don't generate fallback - use null if not extracted
            imageUri = imageUri,

            // New typed cashback fields
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency,
            category = determineCategory(extractedInfo),
            rating = null,
            status = when (couponInstance.status) {
                com.example.coupontracker.ml.CouponStatus.COMPLETE -> "ACTIVE"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_TOP -> "PARTIAL"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_BOTTOM -> "PARTIAL"
            },
            extractionQualityScore = extractionResult.qualityScore,
            extractionConfidenceBreakdown = extractionResult.fieldConfidences,
            extractionStage = extractionResult.sourceStage?.name,
            extractionRunPath = runPathSummary,
            extractionTimestamp = Date(),
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Try universal extraction as an alternative to traditional OCR
     */
    private suspend fun tryUniversalExtraction(imageUri: Uri, bitmap: Bitmap) {
        val startTime = System.currentTimeMillis()
        
        try {
            Log.d(TAG, "Attempting universal extraction")
            
            // Extract text using OCR for universal extraction
            var ocrHints: Map<String, String>? = null
            val ocrText = when (val result = multiEngineOCR.processImage(bitmap)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    ocrHints = result.extractedInfo
                    result.text
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    Log.w(TAG, "OCR failed for universal extraction: ${result.message}")
                    ""
                }
            }
            
            if (ocrText.isBlank()) {
                Log.w(TAG, "No OCR text available for universal extraction")
                
                // Record failed attempt
                val processingTime = System.currentTimeMillis() - startTime
                performanceMonitor.recordExtractionAttempt(
                    method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                    success = false,
                    confidence = 0f,
                    processingTimeMs = processingTime,
                    fieldsExtracted = emptySet()
                )
                
                fallbackToTraditionalOCR(imageUri)
                return
            }
            
            // Create extraction context
            val context = buildUniversalExtractionContext(ocrText, ocrHints)
            
            // Run universal extraction
            val extractionResult = universalExtractionService.extractCoupon(
                image = bitmap,
                ocrText = ocrText,
                context = context
            )

            val processingTime = System.currentTimeMillis() - startTime
            analyticsTracker.trackProcessingTime(processingTime, "universal_extraction")

            if (extractionResult.success && extractionResult.confidence > 0.3f) {
                Log.d(TAG, "Universal extraction successful with confidence: ${extractionResult.confidence}")

                // Use the universally extracted coupon
                val persistedUri = uriPersistenceManager.persistUri(imageUri)
                val finalImageUri = resolveImageUri(persistedUri, imageUri)
                val confidenceBreakdown = buildConfidenceMap(
                    extractionResult.coupon.extractionConfidenceBreakdown,
                    extractionResult.extractedFields
                )
                val coupon = extractionResult.coupon.copy(
                    imageUri = finalImageUri,
                    extractionConfidenceBreakdown = confidenceBreakdown
                )
                val debugSnapshot = ExtractionDebugScorer.fromUniversalResult(
                    extractionResult,
                    ocrText.isBlank()
                )
                val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)

                // Record successful extraction
                val fieldsExtracted = mutableSetOf<String>()
                if (coupon.storeName != "Unknown Store") fieldsExtracted.add("storeName")
                if (!coupon.redeemCode.isNullOrBlank()) fieldsExtracted.add("redeemCode")
                if (coupon.getCashbackNumericValue() > 0) fieldsExtracted.add("cashback")
                if (coupon.expiryDate != null) fieldsExtracted.add("expiryDate")
                
                performanceMonitor.recordExtractionAttempt(
                    method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                    success = true,
                    confidence = extractionResult.confidence,
                    processingTimeMs = processingTime,
                    fieldsExtracted = fieldsExtracted
                )
                
                // Store extraction result for potential feedback learning
                lastExtractionResult = extractionResult.copy(coupon = coupon) to ocrText

                pendingPreview = PendingPreview(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    miniCpmStatus = MiniCpmProgress.SUCCESS,
                    debugSnapshot = debugSnapshot
                )

                _uiState.value = ScannerUiState.Success(coupon, MiniCpmProgress.SUCCESS)

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_COUPON_DETECTED,
                    mapOf(
                        "source" to "universal",
                        "confidence" to String.format(Locale.US, "%.2f", extractionResult.confidence),
                        "mini_cpm" to MiniCpmProgress.SUCCESS.name,
                        "fields" to extractionResult.extractedFields.size
                    )
                )
                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                    mapOf(
                        "persisted" to false,
                        "result" to "pending_review",
                        "mini_cpm" to MiniCpmProgress.SUCCESS.name
                    )
                )

            } else {
                Log.d(TAG, "Universal extraction failed or low confidence: ${extractionResult.confidence}")
                
                // Record failed attempt
                performanceMonitor.recordExtractionAttempt(
                    method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                    success = false,
                    confidence = extractionResult.confidence,
                    processingTimeMs = processingTime,
                    fieldsExtracted = emptySet()
                )
                
                fallbackToTraditionalOCR(imageUri)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Universal extraction error", e)
            
            // Record failed attempt
            val processingTime = System.currentTimeMillis() - startTime
            performanceMonitor.recordExtractionAttempt(
                method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                success = false,
                confidence = 0f,
                processingTimeMs = processingTime,
                fieldsExtracted = emptySet()
            )
            
            fallbackToTraditionalOCR(imageUri)
        }
    }

    /**
     * Fallback to traditional OCR when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCR(imageUri: Uri) {
        try {
            Log.d(TAG, "Using traditional OCR fallback")
            
            // First persist the URI
            val persistedUri = uriPersistenceManager.persistUri(imageUri)
            val finalUri = persistedUri ?: imageUri
            
            when (val result = multiEngineOCR.processImage(imageUri)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_CAPTURE_FAILED,
                            mapOf(
                                "reason" to "ocr_no_fields",
                                "stage" to "traditional_uri"
                            )
                        )
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, finalUri.toString())
                        val debugSnapshot = ExtractionDebugScorer.fromTraditionalOcr(extractedInfo)
                        val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
                        pendingPreview = PendingPreview(
                            coupon = coupon,
                            normalizedDescription = normalizedDescription,
                            miniCpmStatus = MiniCpmProgress.FALLBACK,
                            debugSnapshot = debugSnapshot
                        )
                        _uiState.value = ScannerUiState.Success(coupon, MiniCpmProgress.FALLBACK)

                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_COUPON_DETECTED,
                            mapOf(
                                "source" to "traditional_ocr",
                                "fields" to extractedInfo.size,
                                "mini_cpm" to MiniCpmProgress.FALLBACK.name
                            )
                        )
                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                            mapOf(
                                "persisted" to false,
                                "result" to "pending_review",
                                "mini_cpm" to MiniCpmProgress.FALLBACK.name
                            )
                        )
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                    analyticsTracker.trackEvent(
                        AnalyticsTracker.EVENT_CAPTURE_FAILED,
                        mapOf(
                            "reason" to "ocr_error",
                            "stage" to "traditional_uri"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            analyticsTracker.trackEvent(
                AnalyticsTracker.EVENT_CAPTURE_FAILED,
                mapOf(
                    "reason" to (e.message ?: "ocr_exception"),
                    "stage" to "traditional_uri"
                )
            )
        }
    }

    /**
     * Fallback to traditional OCR for bitmap when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCRBitmap(bitmap: Bitmap, imageUri: Uri?) {
        try {
            Log.d(TAG, "Using traditional OCR fallback for bitmap")
            
            when (val result = multiEngineOCR.processImage(bitmap)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted from bitmap: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_CAPTURE_FAILED,
                            mapOf(
                                "reason" to "ocr_no_fields",
                                "stage" to "traditional_bitmap"
                            )
                        )
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, imageUri?.toString())
                        val debugSnapshot = ExtractionDebugScorer.fromTraditionalOcr(extractedInfo)
                        val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
                        pendingPreview = PendingPreview(
                            coupon = coupon,
                            normalizedDescription = normalizedDescription,
                            miniCpmStatus = MiniCpmProgress.FALLBACK,
                            debugSnapshot = debugSnapshot
                        )
                        _uiState.value = ScannerUiState.Success(coupon, MiniCpmProgress.FALLBACK)

                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_COUPON_DETECTED,
                            mapOf(
                                "source" to "traditional_ocr",
                                "fields" to extractedInfo.size,
                                "mini_cpm" to MiniCpmProgress.FALLBACK.name
                            )
                        )
                        analyticsTracker.trackEvent(
                            AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                            mapOf(
                                "persisted" to false,
                                "result" to "pending_review",
                                "mini_cpm" to MiniCpmProgress.FALLBACK.name
                            )
                        )
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                    analyticsTracker.trackEvent(
                        AnalyticsTracker.EVENT_CAPTURE_FAILED,
                        mapOf(
                            "reason" to "ocr_error",
                            "stage" to "traditional_bitmap"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback for bitmap", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            analyticsTracker.trackEvent(
                AnalyticsTracker.EVENT_CAPTURE_FAILED,
                mapOf(
                    "reason" to (e.message ?: "ocr_exception"),
                    "stage" to "traditional_bitmap"
                )
            )
        }
    }

    /**
     * Load bitmap from URI
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                
                // V2: Track bitmap with BitmapManager for memory management
                bitmap?.let { bitmapManager.trackBitmap(it) }
                
                // V2: Log memory stats after loading
                val memStats = bitmapManager.getMemoryStats()
                Log.d(TAG, "Bitmap loaded: ${bitmap?.width}x${bitmap?.height}, " +
                        "Active bitmaps: ${memStats.activeBitmapCount}, " +
                        "Memory usage: ${String.format("%.2f", memStats.totalBytesMB)} MB " +
                        "(${String.format("%.1f", memStats.pixelBudgetUsage * 100)}% of budget)")
                
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from URI", e)
                null
            }
        }
    }

    /**
     * Legacy method - Create a Coupon object from the extracted information
     */
    private fun createCouponFromExtractedInfo(extractedInfo: Map<String, String>, imageUri: String? = null): Coupon {
        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])

        // Parse amount to double with Indian currency support
        val amount = extractedInfo["amount"]?.let {
            IndianCurrencyParser.parseAmount(it) ?: 0.0
        } ?: 0.0

        // Create typed cashback info from extracted amount
        val cashbackInfo = extractedInfo["amount"]?.let { amountText ->
            CashbackInfo.fromText(amountText)
        } ?: CashbackInfo.fromLegacyAmount(amount, extractedInfo["description"])

        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: "No description",
            expiryDate = expiryDate,
            cashbackAmount = amount, // Keep for backward compatibility
            redeemCode = extractedInfo["code"], // Don't generate fallback - use null if not extracted
            imageUri = imageUri,
            
            // New typed cashback fields
            cashbackType = cashbackInfo.type.name.lowercase(),
            cashbackValueNum = cashbackInfo.valueNum,
            cashbackCurrency = cashbackInfo.currency,
            category = determineCategory(extractedInfo),
            rating = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Parse expiry date string to Date
     */
    /**
     * Confirm that the extraction was correct (positive feedback)
     */
    fun confirmExtractionCorrect() {
        viewModelScope.launch {
            lastExtractionResult?.let { (extractionResult, ocrText) ->
                try {
                    Log.d(TAG, "User confirmed extraction is correct - learning from success")
                    
                    // Record positive feedback
                    performanceMonitor.recordUserFeedback(
                        method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                        feedbackType = FeedbackType.CONFIRMED_CORRECT,
                        correctedFields = emptySet()
                    )
                    
                    val context = buildFeedbackContext()
                    universalExtractionService.learnFromSuccess(
                        extractionResult = extractionResult,
                        originalText = ocrText,
                        context = context
                    )
                    
                    // Clear the stored result
                    lastExtractionResult = null
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error learning from positive feedback", e)
                }
            }
        }
    }
    
    /**
     * Submit user corrections (negative feedback with corrections)
     */
    fun submitExtractionCorrection(
        correctedStoreName: String?,
        correctedCode: String?,
        correctedAmount: String?,
        correctedExpiry: String?
    ) {
        viewModelScope.launch {
            lastExtractionResult?.let { (extractionResult, ocrText) ->
                try {
                    Log.d(TAG, "User submitted corrections - learning from feedback")
                    
                    // Identify which fields were corrected
                    val correctedFields = mutableSetOf<String>()
                    if (!correctedStoreName.isNullOrBlank()) correctedFields.add("storeName")
                    if (!correctedCode.isNullOrBlank()) correctedFields.add("redeemCode")
                    if (!correctedAmount.isNullOrBlank()) correctedFields.add("cashback")
                    if (!correctedExpiry.isNullOrBlank()) correctedFields.add("expiryDate")
                    
                    // Record negative feedback with corrections
                    performanceMonitor.recordUserFeedback(
                        method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                        feedbackType = FeedbackType.SUBMITTED_CORRECTIONS,
                        correctedFields = correctedFields
                    )
                    
                    // Create corrected coupon
                    val correctedCoupon = extractionResult.coupon.copy(
                        storeName = correctedStoreName ?: extractionResult.coupon.storeName,
                        redeemCode = correctedCode,
                        // Note: For amount and expiry, we'd need more sophisticated parsing
                        // For now, we'll just learn from the text patterns
                    )
                    
                    val context = buildFeedbackContext()
                    universalExtractionService.learnFromCorrection(
                        extractionResult = extractionResult,
                        correctedCoupon = correctedCoupon,
                        originalText = ocrText,
                        context = context
                    )

                    validatorFeedbackRecorder.recordUserCorrection(
                        rawOcrText = ocrText,
                        extractionResult = extractionResult,
                        correctedCoupon = correctedCoupon,
                        metadata = mapOf(
                            "source" to TAG,
                            "strategy" to ExtractionMethod.UNIVERSAL_EXTRACTION.name
                        )
                    )
                    
                    // Update the current coupon with corrections if it's still in preview
                    pendingPreview?.let { preview ->
                        val updatedCoupon = preview.coupon.copy(
                            storeName = correctedStoreName ?: preview.coupon.storeName,
                            redeemCode = correctedCode
                        )
                        
                        pendingPreview = preview.copy(coupon = updatedCoupon)
                        _uiState.value = ScannerUiState.Success(updatedCoupon, preview.miniCpmStatus)
                    }
                    
                    // Clear the stored result
                    lastExtractionResult = null
                    
            } catch (e: Exception) {
                    Log.e(TAG, "Error learning from correction feedback", e)
                }
            }
        }
    }
    
    /**
     * Check if feedback can be collected (i.e., there's a recent extraction to learn from)
     */
    fun canCollectFeedback(): Boolean {
        return lastExtractionResult != null
    }

    /**
     * Generate fallback coupon code
     */
    private fun generateFallbackCode(): String {
        return "COUPON_${System.currentTimeMillis().toString().takeLast(6)}"
    }

    /**
     * Determine category from extracted information
     */
    private fun determineCategory(extractedInfo: Map<String, String>): String {
        val relevantKeys = listOf("storeName", "description", "terms", "benefit", "app")
        val text = relevantKeys.mapNotNull { key -> extractedInfo[key] }
            .joinToString(" ")
            .lowercase()
        
        return when {
            text.contains("food") || text.contains("restaurant") || text.contains("zomato") || text.contains("swiggy") -> "Food"
            text.contains("fashion") || text.contains("clothing") || text.contains("myntra") -> "Fashion"
            text.contains("grocery") || text.contains("bigbasket") || text.contains("grofers") -> "Grocery"
            text.contains("travel") || text.contains("booking") || text.contains("hotel") -> "Travel"
            text.contains("electronics") || text.contains("amazon") || text.contains("flipkart") -> "Electronics"
            else -> "Other"
        }
    }

    /**
     * Save the scanned coupon to the repository
     */
    fun saveCoupon(coupon: Coupon) {
        viewModelScope.launch {
            try {
                couponRepository.insertCoupon(coupon)
                _uiState.value = ScannerUiState.Saved(coupon)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving coupon", e)
                _uiState.value = ScannerUiState.Error("Error saving coupon: ${e.message}")
            }
        }
    }

    /**
     * Reset the UI state
     */
    fun resetState() {
        clearPendingPreview()
        _uiState.value = ScannerUiState.Initial
    }

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        try {
            twoStageDetector?.cleanupBitmaps()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TwoStageDetector", e)
        }
    }

    private fun buildUniversalExtractionContext(
        ocrText: String,
        extractedInfo: Map<String, String>?
    ): ExtractionContext {
        val fallbackBrand = ocrText.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    line.length in 3..48 &&
                    line.any { it.isLetter() } &&
                    !fieldHeuristics.isGenericOrMissing(line)
            }

        val brandHint = sequenceOf(
            extractedInfo?.get("storeName"),
            extractedInfo?.get("brand"),
            extractedInfo?.get("app"),
            extractedInfo?.get("merchant"),
            fallbackBrand,
            lastExtractionResult?.first?.coupon?.storeName
        )
            .mapNotNull { candidate ->
                candidate?.takeIf { it.isNotBlank() && !fieldHeuristics.isGenericOrMissing(it) }
            }
            .firstOrNull()

        val categoryHint = extractedInfo
            ?.get("category")
            ?.takeIf { it.isNotBlank() }

        val previousSuccesses = lastExtractionResult
            ?.first
            ?.coupon
            ?.storeName
            ?.takeIf { it.isNotBlank() && !fieldHeuristics.isGenericOrMissing(it) }
            ?.let { listOf(it) }
            ?: emptyList()

        return ExtractionContext(
            brandHint = brandHint,
            categoryHint = categoryHint,
            previousSuccesses = previousSuccesses
        )
    }

    private fun buildFeedbackContext(): ExtractionContext {
        val priorStore = lastExtractionResult
            ?.first
            ?.coupon
            ?.storeName
            ?.takeIf { it.isNotBlank() && !fieldHeuristics.isGenericOrMissing(it) }

        return ExtractionContext(
            brandHint = priorStore,
            previousSuccesses = priorStore?.let { listOf(it) } ?: emptyList()
        )
    }

    private fun resolveImageUri(persisted: Uri?, original: Uri): String {
        return (persisted ?: original).toString()
    }

    private fun buildConfidenceMap(
        existing: Map<String, Float>,
        extractedFields: Map<FieldType, ExtractionCandidate>
    ): Map<String, Float> {
        if (existing.isNotEmpty()) return existing
        if (extractedFields.isEmpty()) return emptyMap()
        return extractedFields.entries.associate { (fieldType, candidate) ->
            fieldType.name.lowercase(Locale.ROOT) to candidate.confidence
        }
    }

    private fun Coupon.ensureConfidenceBreakdown(breakdown: Map<String, Float>): Coupon {
        if (breakdown.isEmpty()) return this
        if (extractionConfidenceBreakdown.isNotEmpty()) return this
        return copy(extractionConfidenceBreakdown = breakdown)
    }

    private fun finalizeCoupon(
        base: Coupon,
        ocrText: String?,
        captureTimestamp: Date?
    ): Coupon {
        val refined = CouponPostProcessor.refine(
            coupon = base,
            context = CouponFixContext(
                ocrText = ocrText,
                captureTimestamp = captureTimestamp
            )
        )
        val normalizedExpiry = normalizeExpiryDate(refined.expiryDate, captureTimestamp)
        return refined.copy(expiryDate = normalizedExpiry)
    }
}

/**
 * Enhanced UI state for the scanner with multi-coupon support
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    data class Scanning(val progress: LlmProgressUpdate? = null) : ScannerUiState()
    data class Success(val coupon: Coupon, val miniCpmStatus: MiniCpmProgress) : ScannerUiState()
    data class Saved(val coupon: Coupon) : ScannerUiState()
    data class AlreadySaved(val existingCoupon: Coupon, val miniCpmStatus: MiniCpmProgress) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
    
    // New states for multi-coupon support
    data class MultiCouponDetected(
        val couponInstances: List<CouponInstance>, 
        val originalBitmap: Bitmap, 
        val imageUri: String?
    ) : ScannerUiState()
    
    data class AllCouponsSaved(val processedCoupons: List<CouponProcessingSummary>) : ScannerUiState()
}

data class CouponProcessingSummary(
    val coupon: Coupon,
    val miniCpmStatus: MiniCpmProgress
)

private data class PendingPreview(
    val coupon: Coupon,
    val normalizedDescription: String,
    val miniCpmStatus: MiniCpmProgress,
    val debugSnapshot: ExtractionDebugSnapshot?
)

enum class MiniCpmProgress {
    SUCCESS,
    NEEDS_REVIEW,
    FALLBACK;

    fun displayName(): String {
        val lower = name.lowercase(Locale.getDefault()).replace('_', ' ')
        return lower.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }
}

data class FieldExtractionResult(
    val fields: Map<String, String>,
    val miniCpmStatus: MiniCpmProgress,
    val runPath: RunPath? = null,
    val debugSnapshot: ExtractionDebugSnapshot? = null,
    val qualityScore: Int? = null,
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val sourceStage: ExtractionStage? = null
)

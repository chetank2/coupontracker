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
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.domain.usecase.SaveScannedCouponResult
import com.example.coupontracker.domain.usecase.SaveScannedCouponUseCase
import com.example.coupontracker.domain.usecase.GuardedFullImageFallbackResult
import com.example.coupontracker.domain.usecase.GuardedFullImageFallbackUseCase
import com.example.coupontracker.domain.usecase.SingleScanRouteAction
import com.example.coupontracker.domain.usecase.SingleScanRoutingUseCase
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.DetectedCropCouponBuilder
import com.example.coupontracker.extraction.capture.DetectedCropFieldExtractor
import com.example.coupontracker.extraction.capture.LlmProgress
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.shouldBlockFullImageFallback
import com.example.coupontracker.extraction.capture.toDetectedCropFieldExtraction
import com.example.coupontracker.universal.ExtractionContext
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.util.ExtractionPerformanceMonitor
import com.example.coupontracker.util.ExtractionMethod
import com.example.coupontracker.util.FeedbackType
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.ml.MultiCouponDetectorDisabledException
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.ExtractionConfig
import com.example.coupontracker.util.ExtractionLogBuffer
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.feedback.ValidatorFeedbackRecorder
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.MultiCouponDetectorState
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.RunPath
import com.example.coupontracker.util.UriPersistenceManager
import com.example.coupontracker.util.LlmProgressUpdate
import com.example.coupontracker.util.normalizeExpiryDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Date
import java.util.Locale
import com.example.coupontracker.util.DateParser
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,  // Tesseract OCR engine
    private val telemetryService: ExtractionTelemetryService,
    private val universalExtractionService: UniversalExtractionService,
    private val performanceMonitor: ExtractionPerformanceMonitor,
    private val analyticsTracker: AnalyticsTracker,
    private val ocrFirstCouponExtractor: OcrFirstCouponExtractor,
    private val multiCouponExtractionService: MultiCouponExtractionService,
    private val bitmapManager: com.example.coupontracker.util.BitmapManager,  // V2: Injected bitmap memory management
    private val validatorFeedbackRecorder: ValidatorFeedbackRecorder,
    private val saveScannedCouponUseCase: SaveScannedCouponUseCase,
    private val guardedFullImageFallbackUseCase: GuardedFullImageFallbackUseCase,
    private val singleScanRoutingUseCase: SingleScanRoutingUseCase,
    private val fullImageFallbackProbe: FullImageFallbackProbe,
    private val detectedCropFieldExtractor: DetectedCropFieldExtractor
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context, ocrEngine)
    private val detectorInitializationResult = initializeTwoStageDetector()
    private val twoStageDetector: TwoStageDetector? = detectorInitializationResult.detector
    private val detectorInitErrorMessage: String? = detectorInitializationResult.errorMessage
    private val uriPersistenceManager = UriPersistenceManager(context)
    private val fieldHeuristics: GenericFieldHeuristics = GenericFieldHeuristics
    private val detectedCropCouponBuilder = DetectedCropCouponBuilder()
    private val manualOverrides = mutableMapOf<String, CouponInstance>()
    private var pendingPreview: PendingPreview? = null
    private var pendingMultiCouponPreview: List<CouponProcessingSummary> = emptyList()

    // Store extraction results for feedback learning
    private var lastExtractionResult: Pair<com.example.coupontracker.universal.UniversalExtractionResult, String>? = null

    init {
        multiEngineOCR.setNetworkAvailability(true)
    }

    companion object {
        private const val TAG = "ScannerViewModel"
        private const val STRATEGY_SURFACE_SINGLE = "single_capture"
        private const val OCR_CONFIDENCE_QUEUE_CLEANUP = 0.85f

        @VisibleForTesting
        internal fun parseExpiryDate(
            dateString: String?,
            locale: Locale = Locale.getDefault()
        ): Date? = DetectedCropCouponBuilder.parseExpiryDate(dateString, locale)

        @VisibleForTesting
        internal fun markDetectedCropOcrProvisional(coupon: Coupon): Coupon =
            DetectedCropCouponBuilder.markOcrProvisional(coupon)
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
            val detector = TwoStageDetector(context, isDebugBuild = BuildConfig.DEBUG)
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.ENABLED)
            DetectorInitializationResult(detector, null, null)
        } catch (disabled: MultiCouponDetectorDisabledException) {
            val message = disabled.message ?: "Multi-coupon detector is disabled."
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.DISABLED, disabled.reasonCode)
            Log.w(TAG, "TwoStageDetector disabled: ${disabled.reasonCode}")
            DetectorInitializationResult(null, message, disabled)
        } catch (e: IllegalStateException) {
            val message = e.message ?: "Multi-coupon detector assets are not available for this build."
            Log.e(TAG, "TwoStageDetector initialization blocked", e)
            telemetryService.trackExtractionResult(
                ExtractResult.Failed(
                    stage = ExtractionStage.TWO_STAGE_DETECTION,
                    error = e
                ),
                RunPath(
                    strategy = "OCR_FIRST",
                    final = "two_stage_detector_initialization_failure",
                    reasons = mutableListOf("stub_mode_manifest")
                )
            )
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.DISABLED, "illegal_state_exception")
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
                    strategy = "OCR_FIRST",
                    final = "two_stage_detector_initialization_failure",
                    reasons = mutableListOf("unexpected_initialization_error")
                )
            )
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.DISABLED, "unexpected_initialization_error")
            DetectorInitializationResult(null, message, e)
        }
    }

    init {
        // Assume network is available by default
        multiEngineOCR.setNetworkAvailability(true)

        // Surface detector initialization status
        detectorInitErrorMessage?.let { error ->
            val message = "Multi-coupon detection unavailable: $error"
            Log.w(TAG, message, detectorInitializationResult.exception)
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
            var bitmapHandedToUi = false
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

                bitmapHandedToUi = routeDetectedCouponCrops(
                    imageUri = imageUri,
                    bitmap = bitmap,
                    persistImmediately = persistImmediately
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanced scanning", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_FAILED,
                    mapOf("reason" to (e.message ?: "unknown_error"))
                )
            } finally {
                // V2: Release bitmap after processing completes
                bitmap?.takeUnless { bitmapHandedToUi }?.let { bm ->
                    bitmapManager.releaseBitmap(bm)
                    Log.d(TAG, "Released original bitmap for: $imageUri")
                }
            }
        }
    }

    private suspend fun routeDetectedCouponCrops(
        imageUri: Uri,
        bitmap: Bitmap,
        persistImmediately: Boolean
    ): Boolean {
        val strategy = com.example.coupontracker.util.ExtractionConfig.getStrategy()
        val detector = twoStageDetector
        if (detector == null) {
            Log.w(TAG, "Single scan crop-first routing unavailable: ${detectorInitErrorMessage ?: "detector not initialized"}")
            val action = singleScanRoutingUseCase.planAfterCropDetection(
                detectorAvailable = false,
                detectedCropCount = 0
            ) as SingleScanRouteAction.TryLayoutThenGuardedFallback
            if (routeLayoutDetectedCoupons(imageUri, bitmap, persistImmediately, action.reason)) {
                return false
            }
            scanWithGuardedFullImageFallback(imageUri, bitmap, persistImmediately, action.reason)
            return false
        }

        val couponInstances = withContext(Dispatchers.IO) {
            detector.detectMultiCoupons(bitmap)
        }
        Log.d(TAG, "Single scan crop-first detection found ${couponInstances.size} coupon(s)")

        return when (val action = singleScanRoutingUseCase.planAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = couponInstances.size
        )) {
            is SingleScanRouteAction.TryLayoutThenGuardedFallback -> {
                if (routeLayoutDetectedCoupons(imageUri, bitmap, persistImmediately, action.reason)) {
                    return false
                }
                scanWithGuardedFullImageFallback(imageUri, bitmap, persistImmediately, action.reason)
                false
            }
            is SingleScanRouteAction.ProcessSingleCrop -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = action.executedStrategy,
                    surface = STRATEGY_SURFACE_SINGLE,
                    reason = action.reason
                )
                processSingleCoupon(
                    couponInstance = couponInstances.first(),
                    imageUri = imageUri.toString(),
                    persistImmediately = persistImmediately,
                    captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
                )
                false
            }
            is SingleScanRouteAction.ShowMultiCouponSelection -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = action.executedStrategy,
                    surface = STRATEGY_SURFACE_SINGLE,
                    reason = action.reason
                )
                _uiState.value = ScannerUiState.MultiCouponDetected(
                    couponInstances = couponInstances,
                    originalBitmap = bitmap,
                    imageUri = imageUri.toString()
                )
                true
            }
        }
    }

    private suspend fun routeLayoutDetectedCoupons(
        imageUri: Uri,
        bitmap: Bitmap,
        persistImmediately: Boolean,
        reason: String
    ): Boolean {
        val strategy = com.example.coupontracker.util.ExtractionConfig.getStrategy()
        val captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
        val persistedUri = uriPersistenceManager.persistUri(imageUri)
        val finalImageUri = resolveImageUri(persistedUri, imageUri)

        logStrategyExecution(
            requested = strategy,
            executed = "layout_multi_coupon_probe",
            surface = STRATEGY_SURFACE_SINGLE,
            reason = reason
        )

        val multiResult = runCatching {
            multiCouponExtractionService.extractMultipleCoupons(
                bitmap = bitmap,
                imageUri = finalImageUri,
                captureTimestamp = captureTimestamp,
                allowProgressiveFallback = false
            )
        }.getOrElse { error ->
            Log.e(TAG, "Layout multi-coupon probe failed", error)
            null
        }

        val extractedCoupons = multiResult?.coupons.orEmpty()
        if (extractedCoupons.isEmpty()) {
            logStrategyExecution(
                requested = strategy,
                executed = "ocr_first_manual_clean",
                surface = STRATEGY_SURFACE_SINGLE,
                reason = "${reason}_layout_no_candidates"
            )
            if (shouldBlockFullImageFallback(multiResult)) {
                saveFullImageFallbackReviewCoupon(
                    imageUri = imageUri,
                    rawOcrText = "",
                    persistImmediately = persistImmediately,
                    reason = "${reason}_layout_${multiResult?.screenshotType}_detected_${multiResult?.totalDetected ?: 0}"
                )
                return true
            }
            return false
        }

        logStrategyExecution(
            requested = strategy,
            executed = if (extractedCoupons.size > 1) "layout_multi_coupon_extraction" else "layout_single_coupon_extraction",
            surface = STRATEGY_SURFACE_SINGLE,
            reason = "${reason}_layout_detected_${extractedCoupons.size}"
        )

        if (!persistImmediately) {
            val previews = extractedCoupons.map { result ->
                CouponProcessingSummary(
                    coupon = result.coupon.copy(
                        createdAt = captureTimestamp ?: result.coupon.createdAt,
                        updatedAt = Date()
                    ),
                    llmStatus = if (result.warnings.isEmpty()) LlmProgress.SUCCESS else LlmProgress.NEEDS_REVIEW
                )
            }
            if (previews.size == 1) {
                val preview = previews.first()
                pendingMultiCouponPreview = emptyList()
                pendingPreview = PendingPreview(
                    coupon = preview.coupon,
                    normalizedDescription = CouponDedupUtils.normalizeDescription(preview.coupon.description),
                    llmStatus = preview.llmStatus,
                    debugSnapshot = null
                )
                _uiState.value = ScannerUiState.Success(preview.coupon, preview.llmStatus)
            } else {
                pendingPreview = null
                pendingMultiCouponPreview = previews
                _uiState.value = ScannerUiState.MultiCouponPreview(previews)
            }
            return true
        }

        val processedResults = mutableListOf<CouponProcessingSummary>()
        for (result in extractedCoupons) {
            val coupon = result.coupon.copy(
                createdAt = captureTimestamp ?: result.coupon.createdAt,
                updatedAt = Date()
            )
            val savedId = persistCoupon(
                coupon = coupon,
                normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description),
                llmStatus = if (result.warnings.isEmpty()) LlmProgress.SUCCESS else LlmProgress.NEEDS_REVIEW,
                debugSnapshot = null
            )
            val savedCoupon = couponRepository.getCouponById(savedId) ?: coupon.copy(id = savedId)
            processedResults.add(
                CouponProcessingSummary(
                    coupon = savedCoupon,
                    llmStatus = if (result.warnings.isEmpty()) LlmProgress.SUCCESS else LlmProgress.NEEDS_REVIEW
                )
            )
        }

        if (processedResults.size > 1) {
            _uiState.value = ScannerUiState.AllCouponsSaved(processedResults)
        }
        return true
    }

    private suspend fun scanWithGuardedFullImageFallback(
        imageUri: Uri,
        bitmap: Bitmap,
        persistImmediately: Boolean,
        routeReason: String
    ) {
        val probeResult = fullImageFallbackProbe.evaluate(bitmap) { image ->
            multiEngineOCR.processImage(image)
        }
        probeResult.ocrErrorMessage?.let { message ->
            Log.w(TAG, "Full-image fallback OCR guard returned error: $message")
        }
        val decision = probeResult.decision

        logStrategyExecution(
            requested = ExtractionConfig.getStrategy(),
            executed = if (decision.allowDirectOcr) "ocr_first_full_image_guarded" else "full_image_review_only",
            surface = STRATEGY_SURFACE_SINGLE,
            reason = "${routeReason}_${decision.reason}"
        )

        if (decision.allowDirectOcr) {
            scanWithOcrFirstPath(imageUri, bitmap, persistImmediately)
        } else {
            saveFullImageFallbackReviewCoupon(
                imageUri = imageUri,
                rawOcrText = probeResult.rawOcrText,
                persistImmediately = persistImmediately,
                reason = "${routeReason}_${decision.reason}"
            )
        }
    }

    private suspend fun saveFullImageFallbackReviewCoupon(
        imageUri: Uri,
        rawOcrText: String,
        persistImmediately: Boolean,
        reason: String
    ) {
        when (val result = guardedFullImageFallbackUseCase(
            imageUri = imageUri,
            rawOcrText = rawOcrText,
            reason = reason,
            persistImmediately = persistImmediately
        )) {
            is GuardedFullImageFallbackResult.Persisted -> {
                applySaveResult(result.saveResult, LlmProgress.NEEDS_REVIEW)
            }
            is GuardedFullImageFallbackResult.Preview -> {
                pendingPreview = PendingPreview(
                    coupon = result.coupon,
                    normalizedDescription = result.normalizedDescription,
                    llmStatus = LlmProgress.NEEDS_REVIEW,
                    debugSnapshot = null
                )
                _uiState.value = ScannerUiState.Success(result.coupon, LlmProgress.NEEDS_REVIEW)
            }
        }
    }
    
    /**
     * V2: OCR_FIRST extraction path - REAL IMPLEMENTATION
     * OCR extracts all text → Universal detector applies patterns → Build coupon
     */
    private suspend fun scanWithOcrFirstPath(imageUri: Uri, bitmap: Bitmap, persistImmediately: Boolean) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "OCR_FIRST: Running multi-engine OCR for comprehensive text extraction")
        
        try {
            val captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
            val persistedUri = uriPersistenceManager.persistUri(imageUri)
            val finalImageUri = resolveImageUri(persistedUri, imageUri)

            val extraction = ocrFirstCouponExtractor.extract(
                bitmap = bitmap,
                imageUri = finalImageUri,
                captureTimestamp = captureTimestamp
            )
            val finalCoupon = extraction.coupon
            val processingTime = System.currentTimeMillis() - startTime
            val fieldsExtracted = mutableSetOf<String>()
            if (finalCoupon.storeName != Coupon.Defaults.UNKNOWN_STORE) fieldsExtracted.add("storeName")
            if (!finalCoupon.redeemCode.isNullOrBlank()) fieldsExtracted.add("redeemCode")
            if (finalCoupon.getCashbackNumericValue() > 0) fieldsExtracted.add("cashback")
            if (finalCoupon.expiryDate != null) fieldsExtracted.add("expiryDate")

            performanceMonitor.recordExtractionAttempt(
                method = ExtractionMethod.OCR_PATTERN_MATCH,
                success = extraction.success,
                confidence = extraction.confidence,
                processingTimeMs = processingTime,
                fieldsExtracted = fieldsExtracted
            )

            lastExtractionResult = com.example.coupontracker.universal.UniversalExtractionResult(
                coupon = finalCoupon,
                confidence = extraction.confidence,
                extractedFields = emptyMap(),
                allCandidates = emptyMap(),
                success = extraction.success
            ) to extraction.rawOcrText

            if (persistImmediately) {
                persistCoupon(
                    coupon = finalCoupon,
                    normalizedDescription = CouponDedupUtils.normalizeDescription(finalCoupon.description),
                    llmStatus = LlmProgress.FALLBACK,
                    debugSnapshot = null
                )
            } else {
                pendingPreview = PendingPreview(
                    coupon = finalCoupon,
                    normalizedDescription = CouponDedupUtils.normalizeDescription(finalCoupon.description),
                    llmStatus = LlmProgress.FALLBACK,
                    debugSnapshot = null
                )
                _uiState.value = ScannerUiState.Success(finalCoupon, LlmProgress.FALLBACK)
            }
            Log.d(TAG, "OCR_FIRST: Completed with shared extractor in ${processingTime}ms")
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
            
            saveOcrOnlyCoupon(
                imageUri = imageUri,
                ocrText = "",
                confidence = 0f,
                persistImmediately = persistImmediately,
                failureReason = e.message ?: "OCR failed"
            )
        }
    }

    private suspend fun saveOcrOnlyCoupon(
        imageUri: Uri,
        ocrText: String,
        confidence: Float,
        persistImmediately: Boolean,
        failureReason: String
    ) {
        val persistedUri = uriPersistenceManager.persistUri(imageUri)
        val finalImageUri = resolveImageUri(persistedUri, imageUri)
        val description = ocrText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(500)
            .ifBlank { failureReason }
        val coupon = finalizeCoupon(
            base = Coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = description,
                redeemCode = null,
                imageUri = finalImageUri,
                status = Coupon.Status.ACTIVE,
                needsAttention = true,
                cleanupStatus = Coupon.CleanupStatus.NONE,
                rawOcrText = ocrText,
                ocrConfidence = confidence,
                extractionSource = Coupon.ExtractionSource.OCR_FAST
            ),
            ocrText = ocrText,
            captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
        )
        val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)

        if (persistImmediately) {
            persistCoupon(
                coupon = coupon,
                normalizedDescription = normalizedDescription,
                llmStatus = LlmProgress.FALLBACK,
                debugSnapshot = null
            )
        } else {
            pendingPreview = PendingPreview(
                coupon = coupon,
                normalizedDescription = normalizedDescription,
                llmStatus = LlmProgress.FALLBACK,
                debugSnapshot = null
            )
            _uiState.value = ScannerUiState.Success(coupon, LlmProgress.FALLBACK)
        }
    }
    
    /**
     * Process a single detected coupon instance
     */
    private suspend fun processSingleCoupon(
        couponInstance: CouponInstance,
        imageUri: String?,
        persistImmediately: Boolean,
        captureTimestamp: Date? = extractCaptureTimestamp(imageUri)
    ) {
        try {
            Log.d(TAG, "Processing single coupon with ${couponInstance.fields.size} detected fields")

            // Extract text from detected fields using OCR
            val extractionResult = detectedCropFieldExtractor.extractTextFromFields(couponInstance, captureTimestamp)
            val debugSnapshot = extractionResult.debugSnapshot
            val scopedImageUri = persistCouponCrop(couponInstance.cropBitmap) ?: imageUri

            val coupon = detectedCropCouponBuilder.buildProvisionalCoupon(
                couponInstance = couponInstance,
                extraction = extractionResult.toDetectedCropFieldExtraction(),
                imageUri = scopedImageUri,
                captureTimestamp = captureTimestamp
            )

            analyticsTracker.trackEvent(
                AnalyticsTracker.EVENT_COUPON_DETECTED,
                mapOf(
                    "source" to "two_stage",
                    "confidence" to String.format(Locale.US, "%.2f", couponInstance.confidence),
                    "llm_status" to extractionResult.llmStatus.name,
                    "fields" to extractionResult.fields.size
                )
            )

            val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
            pendingPreview = null

            if (persistImmediately) {
                persistCoupon(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    llmStatus = extractionResult.llmStatus,
                    debugSnapshot = debugSnapshot
                )
            } else {
                pendingPreview = PendingPreview(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    llmStatus = extractionResult.llmStatus,
                    debugSnapshot = debugSnapshot
                )
                _uiState.value = ScannerUiState.Success(coupon, extractionResult.llmStatus)
                Log.d(TAG, "Preview ready for review without immediate persistence")

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_CAPTURE_COMPLETED,
                    mapOf(
                        "persisted" to false,
                        "result" to "pending_review",
                        "llm_status" to extractionResult.llmStatus.name
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
        llmStatus: LlmProgress,
        debugSnapshot: ExtractionDebugSnapshot?
    ): Long {
        val result = saveScannedCouponUseCase(
            coupon = coupon,
            normalizedDescription = normalizedDescription,
            llmStatusName = llmStatus.name,
            debugSnapshot = debugSnapshot
        )

        applySaveResult(result, llmStatus)
        return result.savedCouponId
    }

    private fun applySaveResult(result: SaveScannedCouponResult, llmStatus: LlmProgress) {
        when (result.kind) {
            SaveScannedCouponResult.Kind.ALREADY_SAVED -> {
                _uiState.value = ScannerUiState.AlreadySaved(result.couponForUi, llmStatus)
                Log.d(
                    TAG,
                    "Duplicate coupon detected, existing ID: ${result.couponForUi.id}, store: ${result.couponForUi.storeName}"
                )
            }
            SaveScannedCouponResult.Kind.SAVED -> {
                _uiState.value = ScannerUiState.Saved(result.couponForUi)
                Log.d(TAG, "Coupon saved with ID: ${result.savedCouponId}, store: ${result.couponForUi.storeName}")
            }
        }
    }

    private fun shouldQueueCleanup(coupon: Coupon, confidence: Float): Boolean {
        val assessment = CouponExtractionConfidenceScorer.score(coupon, coupon.rawOcrText)
        return confidence < OCR_CONFIDENCE_QUEUE_CLEANUP ||
            assessment.recommendation == ExtractionRecommendation.VERIFY_WITH_VISION ||
            assessment.recommendation == ExtractionRecommendation.MANUAL_REVIEW
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
                    llmStatus = preview.llmStatus,
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

    fun confirmMultiCouponPreviewSave() {
        val previews = pendingMultiCouponPreview
        if (previews.isEmpty()) return

        viewModelScope.launch {
            val processedResults = mutableListOf<CouponProcessingSummary>()
            try {
                for (preview in previews) {
                    val savedId = persistCoupon(
                        coupon = preview.coupon,
                        normalizedDescription = CouponDedupUtils.normalizeDescription(preview.coupon.description),
                        llmStatus = preview.llmStatus,
                        debugSnapshot = null
                    )
                    val savedCoupon = couponRepository.getCouponById(savedId) ?: preview.coupon.copy(id = savedId)
                    processedResults.add(
                        CouponProcessingSummary(
                            coupon = savedCoupon,
                            llmStatus = preview.llmStatus
                        )
                    )
                }
                _uiState.value = ScannerUiState.AllCouponsSaved(processedResults)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving multi-coupon preview", e)
                _uiState.value = ScannerUiState.Error("Error saving coupons: ${e.message}")
            } finally {
                pendingMultiCouponPreview = emptyList()
            }
        }
    }

    fun clearPendingPreview() {
        pendingPreview = null
        pendingMultiCouponPreview = emptyList()
    }

    fun getPendingPreviewCoupon(): Coupon? = pendingPreview?.coupon

    @VisibleForTesting
    internal fun setPendingPreviewForTest(coupon: Coupon, llmStatus: LlmProgress) {
        pendingPreview = PendingPreview(
            coupon = coupon,
            normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description),
            llmStatus = llmStatus,
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
                val captureTimestamp = extractCaptureTimestamp(originalImageUri)

                // Process the selected coupon
                processSingleCoupon(
                    couponInstance = selectedInstance,
                    imageUri = originalImageUri,
                    persistImmediately = true,
                    captureTimestamp = captureTimestamp
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

    private suspend fun persistCouponCrop(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "coupon_crops").apply { mkdirs() }
            val file = File(dir, "coupon_crop_${System.currentTimeMillis()}_${bitmap.width}x${bitmap.height}.jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            Uri.fromFile(file).toString()
        }.onFailure { error ->
            Log.w(TAG, "Failed to persist coupon crop", error)
        }.getOrNull()
    }

    private fun extractCaptureTimestamp(imageUri: String?): Date? {
        if (imageUri.isNullOrBlank()) return null
        return runCatching {
            ImageMetadataExtractor.extractCaptureTimestamp(context, Uri.parse(imageUri))
        }.onFailure { error ->
            Log.w(TAG, "Failed to extract capture timestamp for $imageUri", error)
        }.getOrNull()
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
        val captureTimestamp = extractCaptureTimestamp(originalImageUri)

        for ((index, instance) in couponInstances.withIndex()) {
            try {
                val extractionResult = detectedCropFieldExtractor.extractTextFromFields(instance, captureTimestamp)
                val scopedImageUri = persistCouponCrop(instance.cropBitmap) ?: originalImageUri
                val coupon = detectedCropCouponBuilder.buildCoupon(
                    couponInstance = instance,
                    extraction = extractionResult.toDetectedCropFieldExtraction(),
                    imageUri = scopedImageUri,
                    captureTimestamp = captureTimestamp
                )
                val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)

                val saveResult = saveScannedCouponUseCase(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    llmStatusName = extractionResult.llmStatus.name,
                    debugSnapshot = null
                )
                val savedCoupon = saveResult.couponForUi
                processedResults.add(CouponProcessingSummary(savedCoupon, extractionResult.llmStatus))

                Log.d(
                    TAG,
                    "Saved coupon ${processedResults.size}/${couponInstances.size}: ${coupon.redeemCode}"
                )

                analyticsTracker.trackEvent(
                    AnalyticsTracker.EVENT_COUPON_DETECTED,
                    mapOf(
                        "source" to "multi_coupon_batch",
                        "llm_status" to extractionResult.llmStatus.name,
                        "fields" to extractionResult.fields.size
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
        correctedDetail: String?,
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
                    if (!correctedDetail.isNullOrBlank()) correctedFields.add("cashback")
                    if (!correctedExpiry.isNullOrBlank()) correctedFields.add("expiryDate")
                    
                    // Record negative feedback with corrections
                    performanceMonitor.recordUserFeedback(
                        method = ExtractionMethod.UNIVERSAL_EXTRACTION,
                        feedbackType = FeedbackType.SUBMITTED_CORRECTIONS,
                        correctedFields = correctedFields
                    )
                    
                    // Create corrected coupon
                    var correctedCoupon = extractionResult.coupon.copy(
                        storeName = correctedStoreName ?: extractionResult.coupon.storeName,
                        redeemCode = correctedCode,
                    )

                    if (!correctedDetail.isNullOrBlank()) {
                        correctedCoupon = correctedCoupon.withAdditionalDetails(correctedDetail)
                    }

                    val parsedExpiry = correctedExpiry?.let { input ->
                        DateParser.parseDate(input)
                    }
                    if (parsedExpiry != null) {
                        correctedCoupon = correctedCoupon.copy(expiryDate = parsedExpiry)
                    }
                    
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
                        var updatedCoupon = preview.coupon.copy(
                            storeName = correctedStoreName ?: preview.coupon.storeName,
                            redeemCode = correctedCode
                        )
                        if (!correctedDetail.isNullOrBlank()) {
                            updatedCoupon = updatedCoupon.withAdditionalDetails(correctedDetail)
                        }
                        if (parsedExpiry != null) {
                            updatedCoupon = updatedCoupon.copy(expiryDate = parsedExpiry)
                        }
                        
                        pendingPreview = preview.copy(coupon = updatedCoupon)
                        _uiState.value = ScannerUiState.Success(updatedCoupon, preview.llmStatus)
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
        val normalized = refined.copy(expiryDate = normalizeExpiryDate(refined.expiryDate, captureTimestamp))
        val assessment = CouponExtractionConfidenceScorer.score(normalized, ocrText)
        return normalized.copy(
            extractionQualityScore = assessment.score,
            extractionConfidenceBreakdown = normalized.extractionConfidenceBreakdown.ifEmpty {
                assessment.fieldConfidences
            },
            needsAttention = normalized.needsAttention ||
                assessment.recommendation != ExtractionRecommendation.SAVE_DIRECTLY
        )
    }
}

/**
 * Enhanced UI state for the scanner with multi-coupon support
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    data class Scanning(val progress: LlmProgressUpdate? = null) : ScannerUiState()
    data class Success(val coupon: Coupon, val llmStatus: LlmProgress) : ScannerUiState()
    data class Saved(val coupon: Coupon) : ScannerUiState()
    data class AlreadySaved(val existingCoupon: Coupon, val llmStatus: LlmProgress) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
    
    // New states for multi-coupon support
    data class MultiCouponDetected(
        val couponInstances: List<CouponInstance>, 
        val originalBitmap: Bitmap, 
        val imageUri: String?
    ) : ScannerUiState()

    data class MultiCouponPreview(val extractedCoupons: List<CouponProcessingSummary>) : ScannerUiState()
    
    data class AllCouponsSaved(val processedCoupons: List<CouponProcessingSummary>) : ScannerUiState()
}

data class CouponProcessingSummary(
    val coupon: Coupon,
    val llmStatus: LlmProgress
)

private data class PendingPreview(
    val coupon: Coupon,
    val normalizedDescription: String,
    val llmStatus: LlmProgress,
    val debugSnapshot: ExtractionDebugSnapshot?
)

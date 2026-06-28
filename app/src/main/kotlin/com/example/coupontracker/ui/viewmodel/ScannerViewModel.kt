package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.debug.ExtractionDebugScorer
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.domain.usecase.SaveScannedCouponResult
import com.example.coupontracker.domain.usecase.SaveScannedCouponUseCase
import com.example.coupontracker.domain.usecase.SingleScanRouteDecision
import com.example.coupontracker.domain.usecase.SingleScanRoutingUseCase
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.FullImageFallbackReviewCouponFactory
import com.example.coupontracker.extraction.capture.shouldBlockFullImageFallback
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.rules.TextExtractor
import com.example.coupontracker.extraction.validation.CouponFieldBundleValidator
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.universal.ExtractionContext
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.util.ExtractionPerformanceMonitor
import com.example.coupontracker.util.ExtractionMethod
import com.example.coupontracker.util.FeedbackType
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.ml.MultiCouponDetectorDisabledException
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.BitmapManager
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.extraction.rules.CouponInfo
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.CouponCardOcrNormalizer
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.example.coupontracker.util.DateParser
import org.json.JSONArray
import org.json.JSONObject
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
    private val singleScanRoutingUseCase: SingleScanRoutingUseCase,
    private val fullImageFallbackProbe: FullImageFallbackProbe,
    private val fullImageFallbackReviewCouponFactory: FullImageFallbackReviewCouponFactory
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context, ocrEngine)
    private val detectorInitializationResult = initializeTwoStageDetector()
    private val twoStageDetector: TwoStageDetector? = detectorInitializationResult.detector
    private val detectorInitErrorMessage: String? = detectorInitializationResult.errorMessage
    private val uriPersistenceManager = UriPersistenceManager(context)
    private val fieldHeuristics: GenericFieldHeuristics = GenericFieldHeuristics
    private val textExtractor = TextExtractor()
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
        private const val DETECTED_CROP_OCR_PENDING_REASON =
            "Background vision verification pending for OCR-only detected crop"

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

        @VisibleForTesting
        internal fun markDetectedCropOcrProvisional(coupon: Coupon): Coupon {
            val pendingEvidence = listOf(
                "background_vision_verification=pending",
                "source=single_detected_crop_ocr_only"
            )
            val mergedEvidence = sequenceOf(
                coupon.debugVisionEvidence,
                pendingEvidence.joinToString("; ")
            )
                .filterNot { it.isNullOrBlank() }
                .joinToString("; ")

            return coupon.copy(
                needsAttention = true,
                cleanupStatus = Coupon.CleanupStatus.PENDING,
                cleanupStartedAt = null,
                cleanupFinishedAt = null,
                cleanupError = DETECTED_CROP_OCR_PENDING_REASON,
                layoutState = if (coupon.layoutState == Coupon.LayoutState.COMPLETE) {
                    Coupon.LayoutState.LOW_CONFIDENCE
                } else {
                    coupon.layoutState
                },
                debugVisionEvidence = mergedEvidence
            )
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
            val decision = singleScanRoutingUseCase.decideAfterCropDetection(
                detectorAvailable = false,
                detectedCropCount = 0
            ) as SingleScanRouteDecision.TryLayoutThenGuardedFallback
            if (routeLayoutDetectedCoupons(imageUri, bitmap, persistImmediately, decision.reason)) {
                return false
            }
            scanWithGuardedFullImageFallback(imageUri, bitmap, persistImmediately, decision.reason)
            return false
        }

        val couponInstances = withContext(Dispatchers.IO) {
            detector.detectMultiCoupons(bitmap)
        }
        Log.d(TAG, "Single scan crop-first detection found ${couponInstances.size} coupon(s)")

        return when (val decision = singleScanRoutingUseCase.decideAfterCropDetection(
            detectorAvailable = true,
            detectedCropCount = couponInstances.size
        )) {
            is SingleScanRouteDecision.TryLayoutThenGuardedFallback -> {
                if (routeLayoutDetectedCoupons(imageUri, bitmap, persistImmediately, decision.reason)) {
                    return false
                }
                scanWithGuardedFullImageFallback(imageUri, bitmap, persistImmediately, decision.reason)
                false
            }
            SingleScanRouteDecision.ProcessSingleCrop -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = "ocr_first_card_crop",
                    surface = STRATEGY_SURFACE_SINGLE,
                    reason = "single_coupon_crop_detected"
                )
                processSingleCoupon(
                    couponInstance = couponInstances.first(),
                    imageUri = imageUri.toString(),
                    persistImmediately = persistImmediately,
                    captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
                )
                false
            }
            SingleScanRouteDecision.ShowMultiCouponSelection -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = "multi_coupon_selection",
                    surface = STRATEGY_SURFACE_SINGLE,
                    reason = "multiple_coupon_crops_detected"
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
        val captureTimestamp = ImageMetadataExtractor.extractCaptureTimestamp(context, imageUri)
        val persistedUri = uriPersistenceManager.persistUri(imageUri)
        val finalImageUri = resolveImageUri(persistedUri, imageUri)
        val coupon = fullImageFallbackReviewCouponFactory.create(
            imageUri = finalImageUri,
            rawOcrText = rawOcrText,
            reason = reason,
            captureTimestamp = captureTimestamp
        )
        val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)

        if (persistImmediately) {
            persistCoupon(
                coupon = coupon,
                normalizedDescription = normalizedDescription,
                llmStatus = LlmProgress.NEEDS_REVIEW,
                debugSnapshot = null
            )
        } else {
            pendingPreview = PendingPreview(
                coupon = coupon,
                normalizedDescription = normalizedDescription,
                llmStatus = LlmProgress.NEEDS_REVIEW,
                debugSnapshot = null
            )
            _uiState.value = ScannerUiState.Success(coupon, LlmProgress.NEEDS_REVIEW)
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
            val extractionResult = extractTextFromFields(couponInstance, captureTimestamp)
            val debugSnapshot = extractionResult.debugSnapshot
            val scopedImageUri = persistCouponCrop(couponInstance.cropBitmap) ?: imageUri

            // Create coupon from extracted information
            val baseCoupon = createCouponFromInstance(
                    couponInstance = couponInstance,
                    extractionResult = extractionResult,
                    imageUri = scopedImageUri,
                    captureTimestamp = captureTimestamp
                )
            val coupon = markDetectedCropOcrProvisional(
                finalizeCoupon(
                    base = validateDetectedCouponInstance(
                        coupon = baseCoupon,
                        extractionResult = extractionResult,
                        expiryDateText = extractionResult.fields["expiryDate"]
                    ),
                    ocrText = extractionResult.fullOcrText,
                    captureTimestamp = captureTimestamp
                )
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

        return result.savedCouponId
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
            val extractionResult = extractTextFromFields(instance, captureTimestamp)
            val scopedImageUri = persistCouponCrop(instance.cropBitmap) ?: originalImageUri
            val coupon = finalizeCoupon(
                base = createCouponFromInstance(instance, extractionResult, scopedImageUri, captureTimestamp),
                ocrText = extractionResult.fullOcrText,
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

    /**
     * Extract text from detected fields using OCR with run-path telemetry
     */
    private suspend fun extractTextFromFields(
        couponInstance: CouponInstance,
        captureTimestamp: Date?
    ): FieldExtractionResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val extractedInfo = mutableMapOf<String, String>()
            var progress = LlmProgress.FALLBACK
            val triedStages = mutableListOf<String>()
            var finalStage = "OCR"
            var qualityScore: Int? = null
            var fieldConfidences: Map<String, Float> = emptyMap()
            var sourceStage: ExtractionStage? = ExtractionStage.MLKIT
            var fullOcrText: String? = null
            var ocrBlocks: List<TextBlock> = emptyList()
            var imageHeight = 0

            extractedInfo["minicpmConfidence"] = couponInstance.confidence.toString()
            extractedInfo["minicpmDetectionStatus"] = couponInstance.status.name

            try {
                triedStages.add("OCR")
                val fallbackResult = runFallbackOcr(couponInstance.cropBitmap, captureTimestamp)
                extractedInfo.putAll(fallbackResult.fields)
                fullOcrText = fallbackResult.text
                ocrBlocks = fallbackResult.ocrBlocks
                imageHeight = fallbackResult.imageHeight
                qualityScore = if (fallbackResult.fields.isNotEmpty()) 55 else 0
                fieldConfidences = fallbackResult.fields.keys.associateWith { 0.55f }
                Log.d(TAG, "OCR-only field extraction completed with ${fallbackResult.fields.size} fields")
            } catch (e: Exception) {
                Log.e(TAG, "OCR field extraction failed", e)
                triedStages.add("OCR_FAILED")
                finalStage = "OCR_FAILED"
                qualityScore = qualityScore ?: 0
            }

            // Track run path telemetry
            val totalTime = System.currentTimeMillis() - startTime
            val runPath = RunPath(
                primary = "OCR",
                tried = triedStages,
                final = finalStage,
                nativeAvailable = false,
                totalTimeMs = totalTime
            )

            telemetryService.trackRunPath(runPath)

            extractedInfo["minicpmProcessing"] = progress.name
            extractedInfo["runPath"] = "${runPath.strategy} → ${runPath.final}"
            extractedInfo["processingTimeMs"] = totalTime.toString()
            qualityScore?.let { extractedInfo["qualityScore"] = it.toString() }

            val baseResult = FieldExtractionResult(
                fields = extractedInfo.toMap(),
                llmStatus = progress,
                runPath = runPath,
                qualityScore = qualityScore,
                fieldConfidences = fieldConfidences,
                sourceStage = sourceStage,
                fullOcrText = fullOcrText,
                ocrBlocks = ocrBlocks,
                imageHeight = imageHeight
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

        val savingsDetail = couponInfo.cashbackDetail
            ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }
            ?: DescriptionUtils.extractCashbackLine(couponInfo.description)
                ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }

        savingsDetail?.let { fields["amount"] = it }

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
        val amountWeak = !GenericFieldHeuristics.hasMeaningfulCashback(couponInfo.cashbackDetail) &&
            !GenericFieldHeuristics.hasMeaningfulCashback(couponInfo.description)

        return storeWeak || descriptionWeak || duplicateStoreAndCode || (codeMissing && amountWeak)
    }

    private suspend fun runFallbackOcr(bitmap: Bitmap, captureTimestamp: Date?): FallbackOcrResult {
        val boxedResult = runCatching {
            val spans = ocrEngine.recognizeWithBoxes(bitmap)
            BoxedOcrResult(
                text = CouponCardOcrNormalizer.normalize(bitmap.width, bitmap.height, spans),
                blocks = ocrSpansToTextBlocks(spans),
                imageHeight = bitmap.height
            )
        }.getOrNull()

        val boxedText = boxedResult?.text
        if (!boxedText.isNullOrBlank()) {
            val couponInfo = textExtractor.extractCouponInfoSync(boxedText, captureTimestamp)
            val fields = mapCouponInfoToFields(couponInfo)
            if (fields.isNotEmpty()) {
                return FallbackOcrResult(
                    fields = fields,
                    text = boxedText,
                    ocrBlocks = boxedResult.blocks,
                    imageHeight = boxedResult.imageHeight
                )
            }
        }

        return when (val result = multiEngineOCR.processImage(bitmap)) {
            is MultiEngineOCR.OCRResult.Success -> {
                val couponInfo = textExtractor.extractCouponInfoSync(result.text, captureTimestamp)
                val fields = mapCouponInfoToFields(couponInfo).ifEmpty { result.extractedInfo }
                FallbackOcrResult(
                    fields = fields,
                    text = result.text,
                    ocrBlocks = boxedResult?.blocks.orEmpty(),
                    imageHeight = boxedResult?.imageHeight ?: 0
                )
            }
            is MultiEngineOCR.OCRResult.Error -> {
                Log.w(TAG, "Fallback OCR failed: ${result.message}")
                FallbackOcrResult(
                    fields = emptyMap(),
                    text = null,
                    ocrBlocks = boxedResult?.blocks.orEmpty(),
                    imageHeight = boxedResult?.imageHeight ?: 0
                )
            }
        }
    }

    private data class FallbackOcrResult(
        val fields: Map<String, String>,
        val text: String?,
        val ocrBlocks: List<TextBlock> = emptyList(),
        val imageHeight: Int = 0
    )

    private data class BoxedOcrResult(
        val text: String,
        val blocks: List<TextBlock>,
        val imageHeight: Int
    )

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
        imageUri: String?,
        captureTimestamp: Date? = null
    ): Coupon {
        val extractedInfo = extractionResult.fields

        // Parse expiry date string to Date if available
        val expiryDate = DateParser.parseDate(extractedInfo["expiryDate"], captureTimestamp)
            ?: parseExpiryDate(extractedInfo["expiryDate"])

        // Normalize cashback detail if present
        val cashbackDetail = extractedInfo["amount"]?.let { raw ->
            DescriptionUtils.formatCashbackDetail(raw) ?: raw
        }

        val runPathSummary = extractionResult.runPath?.let { path ->
            buildString {
                append(path.strategy.ifBlank { "LLM" })
                if (path.final.isNotBlank()) {
                    append(" → ")
                    append(path.final)
                }
            }.ifBlank { null }
        }

        val baseDescription = extractedInfo["description"] ?: extractedInfo["benefit"] ?: "Multi-coupon detected"
        val mergedDescription = DescriptionUtils.appendDetails(baseDescription, cashbackDetail)

        return Coupon(
            storeName = extractedInfo["storeName"] ?: extractedInfo["app"] ?: Coupon.Defaults.UNKNOWN_STORE,
            description = mergedDescription,
            expiryDate = expiryDate,
            redeemCode = extractedInfo["code"], // Don't generate fallback - use null if not extracted
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
            status = when (couponInstance.status) {
                com.example.coupontracker.ml.CouponStatus.COMPLETE -> "ACTIVE"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_TOP -> "PARTIAL"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_BOTTOM -> "PARTIAL"
            },
            extractionQualityScore = extractionResult.qualityScore,
            extractionConfidenceBreakdown = extractionResult.fieldConfidences,
            extractionStage = extractionResult.sourceStage?.name,
            extractionRunPath = runPathSummary,
            rawOcrText = extractionResult.fullOcrText,
            extractionSource = Coupon.ExtractionSource.OCR_FAST,
            extractionTimestamp = Date()
        )
    }

    private fun validateDetectedCouponInstance(
        coupon: Coupon,
        extractionResult: FieldExtractionResult,
        expiryDateText: String?
    ): Coupon {
        val validation = CouponFieldBundleValidator().validate(
            bundle = FieldValueBundle(
                storeName = coupon.storeName,
                description = coupon.description,
                redeemCode = coupon.redeemCode,
                expiryDateText = expiryDateText,
                codeState = coupon.codeState,
                expiryState = coupon.expiryState
            ),
            fields = buildDetectedFieldCandidates(coupon, extractionResult, expiryDateText),
            rawOcrText = extractionResult.fullOcrText,
            ocrBlocks = extractionResult.ocrBlocks,
            imageHeight = extractionResult.imageHeight
        )

        val issueMessages = validation.issues.map { "${it.field.name}:${it.message}" }
        val hasError = validation.issues.any { it.severity == CouponFieldBundleValidator.Severity.ERROR } ||
            !validation.spatialResult.consistent
        val foregroundModal = coupon.layoutState == Coupon.LayoutState.MODAL_FOREGROUND
        val multiCouponRegion = issueMessages.any { it.contains("multiple_coupon_sections_in_single_region") } &&
            !foregroundModal
        val onlyForegroundOwnershipIssue = foregroundModal &&
            issueMessages.isNotEmpty() &&
            issueMessages.all { it.contains("multiple_coupon_sections_in_single_region") }
        val needsAttention = validation.needsAttention && !onlyForegroundOwnershipIssue
        val invalidCode = issueMessages.any {
            it.contains("COUPON_CODE:") || it.contains("store_duplicates_code") || it.contains("description_duplicates_code")
        }
        val runPath = JSONObject()
            .put("stage", "detected_coupon_instance")
            .put("validator", "CouponFieldBundleValidator")
            .put("trusted", validation.trusted)
            .put("needsAttention", validation.needsAttention)
            .put("issues", JSONArray(issueMessages))
            .toString()

        return coupon.copy(
            redeemCode = if (multiCouponRegion || invalidCode) null else coupon.redeemCode,
            expiryDate = if (multiCouponRegion) null else coupon.expiryDate,
            needsAttention = coupon.needsAttention || needsAttention,
            cleanupStatus = if (needsAttention) {
                Coupon.CleanupStatus.FAILED
            } else {
                coupon.cleanupStatus
            },
            cleanupError = if (needsAttention) validation.reason else null,
            extractionSource = if (validation.trusted && !hasError) {
                Coupon.ExtractionSource.OCR_VERIFIED
            } else {
                coupon.extractionSource
            },
            extractionRunPath = runPath
        )
    }

    private fun buildDetectedFieldCandidates(
        coupon: Coupon,
        extractionResult: FieldExtractionResult,
        expiryDateText: String?
    ): Map<FieldType, FieldCandidate> {
        fun confidenceFor(fieldName: String): Float {
            return extractionResult.fieldConfidences[fieldName]
                ?: extractionResult.fieldConfidences[fieldName.lowercase(Locale.ROOT)]
                ?: 0.55f
        }

        return buildMap {
            put(
                FieldType.STORE_NAME,
                FieldCandidate(coupon.storeName, confidenceFor("storeName"), "detected_coupon_ocr", null)
            )
            put(
                FieldType.DESCRIPTION,
                FieldCandidate(coupon.description, confidenceFor("description"), "detected_coupon_ocr", null)
            )
            coupon.redeemCode?.takeIf { it.isNotBlank() }?.let { code ->
                put(FieldType.COUPON_CODE, FieldCandidate(code, confidenceFor("code"), "detected_coupon_ocr", null))
            }
            val expiryCandidate = expiryDateText?.takeIf { it.isNotBlank() } ?: coupon.expiryDate?.toString()
            expiryCandidate?.let { expiry ->
                put(FieldType.EXPIRY_DATE, FieldCandidate(expiry, confidenceFor("expiryDate"), "detected_coupon_ocr", null))
            }
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
     * Determine category from extracted information
     */
    private fun determineCategory(extractedInfo: Map<String, String>): String {
        val relevantKeys = listOf("storeName", "description", "terms", "benefit", "app")
        val text = relevantKeys.mapNotNull { key -> extractedInfo[key] }
            .joinToString(" ")
            .lowercase()
        
        return when {
            text.contains("food") || text.contains("restaurant") || text.contains("dining") || text.contains("meal") -> "Food"
            text.contains("fashion") || text.contains("clothing") || text.contains("apparel") || text.contains("wear") -> "Fashion"
            text.contains("grocery") || text.contains("groceries") || text.contains("supermarket") -> "Grocery"
            text.contains("travel") || text.contains("booking") || text.contains("hotel") -> "Travel"
            text.contains("electronics") || text.contains("mobile") || text.contains("appliance") -> "Electronics"
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

enum class LlmProgress {
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
    val llmStatus: LlmProgress,
    val runPath: RunPath? = null,
    val debugSnapshot: ExtractionDebugSnapshot? = null,
    val qualityScore: Int? = null,
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val sourceStage: ExtractionStage? = null,
    val fullOcrText: String? = null,
    val ocrBlocks: List<TextBlock> = emptyList(),
    val imageHeight: Int = 0
)

internal fun ocrSpansToTextBlocks(spans: List<OcrTextSpan>): List<TextBlock> {
    return spans.map { span ->
        TextBlock(
            text = span.text,
            bounds = RectF(span.boundingBox),
            confidence = span.confidence
        )
    }
}

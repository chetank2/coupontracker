package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.CouponInputManager
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.ml.MultiCouponDetectorDisabledException
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.MultiCouponDetectorState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for batch scanning of multiple coupons
 */
@HiltViewModel
class BatchScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,  // Tesseract OCR engine
    private val bitmapManager: com.example.coupontracker.util.BitmapManager,  // V2: Bitmap memory management
    private val localLlmOcrService: com.example.coupontracker.util.LocalLlmOcrService,  // V2: LLM service
    private val universalExtractionService: com.example.coupontracker.universal.UniversalExtractionService,  // V2: Universal extraction
    private val analyticsTracker: AnalyticsTracker,
    private val telemetryService: ExtractionTelemetryService,
    private val regionPipeline: com.example.coupontracker.extraction.multi.CouponRegionPipeline,
    private val batchPipelineFlag: com.example.coupontracker.extraction.multi.BatchPipelineFeatureFlag
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BatchScannerUiState())
    val uiState: StateFlow<BatchScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR = com.example.coupontracker.util.MultiEngineOCR(context, ocrEngine)
    private val uriPersistenceManager = com.example.coupontracker.util.UriPersistenceManager(context)
    private val detectorInitializationResult = initializeTwoStageDetector()
    private val twoStageDetector = detectorInitializationResult.detector
    private val detectorInitErrorMessage = detectorInitializationResult.errorMessage
    
    // V2.1: Hybrid detector for multi-coupon per image detection
    private val hybridDetector = com.example.coupontracker.ml.HybridCouponDetector(context, ocrEngine)

    companion object {
        private const val TAG = "BatchScannerViewModel"
        private const val STRATEGY_SURFACE_BATCH = "batch_capture"
    }

    private data class DetectorInitializationResult(
        val detector: com.example.coupontracker.ml.TwoStageDetector?,
        val errorMessage: String?,
        val exception: Throwable?
    )

    private fun initializeTwoStageDetector(): DetectorInitializationResult {
        return try {
            val detector = com.example.coupontracker.ml.TwoStageDetector(
                context,
                isDebugBuild = BuildConfig.DEBUG
            )
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
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.DISABLED, "illegal_state_exception")
            DetectorInitializationResult(null, message, e)
        } catch (e: Exception) {
            val message = e.message ?: "Failed to initialize multi-coupon detector."
            Log.e(TAG, "TwoStageDetector initialization failed", e)
            telemetryService.trackMultiCouponDetectorState(MultiCouponDetectorState.DISABLED, "unexpected_initialization_error")
            DetectorInitializationResult(null, message, e)
        }
    }

    init {
        // V2: Enable OCR network availability (critical for OCR_FIRST and HYBRID strategies)
        multiEngineOCR.setNetworkAvailability(true)
        Log.d(TAG, "MultiEngineOCR network availability enabled for batch processing")

        detectorInitErrorMessage?.let { error ->
            val message = "Multi-coupon detection unavailable: $error"
            val anyDetectorAvailable = runCatching { hybridDetector.isPartiallyAvailable() }
                .onFailure { throwable ->
                    Log.e(TAG, "Failed to evaluate hybrid detector availability", throwable)
                }
                .getOrDefault(false)

            if (anyDetectorAvailable) {
                Log.w(
                    TAG,
                    "$message - falling back to OCR anchor segmentation for batch scanning",
                    detectorInitializationResult.exception
                )
                updateState { current ->
                    current.copy(
                        error = null,
                        notice = message
                    )
                }
            } else {
                Log.e(TAG, message, detectorInitializationResult.exception)
                updateState { it.copy(error = message) }
            }
        }
    }

    /**
     * Add images to the batch
     * @param uris List of image URIs to add
     */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return

        val currentImages = _uiState.value.selectedImages.toMutableList()
        val existingUris = currentImages.map { it.uri.toString() }.toMutableSet()
        val newImages = mutableListOf<SelectedImage>()

        for (uri in uris) {
            val uriKey = uri.toString()
            if (existingUris.contains(uriKey) || newImages.any { it.uri.toString() == uriKey }) {
                Log.d(TAG, "Skipping duplicate image uri=$uriKey")
                continue
            }

            val selectionOrder = currentImages.size + newImages.size + 1
            val selectedImage = createSelectedImage(
                uri = uri,
                selectionOrder = selectionOrder,
                explicitMimeType = null
            )

            newImages.add(selectedImage)
        }

        if (newImages.isNotEmpty()) {
            currentImages.addAll(newImages)
            _uiState.value = _uiState.value.copy(selectedImages = currentImages)
        }
    }

    /**
     * Add a PDF to the batch
     * @param uri PDF URI to add
     */
    fun addPdf(uri: Uri) {
        val currentImages = _uiState.value.selectedImages.toMutableList()
        val uriKey = uri.toString()
        if (currentImages.any { it.uri.toString() == uriKey }) {
            Log.d(TAG, "Skipping duplicate PDF uri=$uriKey")
            return
        }

        val selectionOrder = currentImages.size + 1
        val selectedImage = createSelectedImage(
            uri = uri,
            selectionOrder = selectionOrder,
            explicitMimeType = "application/pdf"
        )

        currentImages.add(selectedImage)
        _uiState.value = _uiState.value.copy(selectedImages = currentImages)
    }

    /**
     * Remove an image from the batch
     * @param index Index of the image to remove
     */
    fun removeImage(index: Int) {
        val currentImages = _uiState.value.selectedImages.toMutableList()
        if (index in currentImages.indices) {
            val removed = currentImages.removeAt(index)
            val updatedStatuses = _uiState.value.imageProcessingStatuses.filterNot {
                it.image.uri.toString() == removed.uri.toString()
            }
            _uiState.value = _uiState.value.copy(
                selectedImages = currentImages,
                imageProcessingStatuses = updatedStatuses
            )
        }
    }

    /**
     * Clear all selected images
     */
    fun clearImages() {
        _uiState.value = _uiState.value.copy(
            selectedImages = emptyList(),
            processedCoupons = emptyList(),
            imageProcessingStatuses = emptyList(),
            processedCount = 0,
            currentlyProcessingImage = null,
            error = null
        )
    }
    
    /**
     * Check if batch scanning is supported on this build. We consider the
     * feature available when either the dedicated two-stage detector is ready
     * or the OCR anchor fallback path can handle segmentation.
     */
    fun isBatchScanningSupported(): Boolean {
        return try {
            hybridDetector.isPartiallyAvailable()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking batch scanning support", e)
            false
        }
    }

    /**
     * Indicates whether we're currently relying on the OCR-only fallback for
     * coupon segmentation (i.e., two-stage models are unavailable).
     */
    fun isOcrFallbackActive(): Boolean {
        return try {
            hybridDetector.isOcrOnlyMode()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking OCR fallback state", e)
            false
        }
    }

    /**
     * Process all selected images
     * V2: REAL IMPLEMENTATION - Routes through selected strategy
     */
    fun processImages() {
        viewModelScope.launch {
            try {
                // V2: Get active strategy
                val strategy = com.example.coupontracker.util.ExtractionConfig.getStrategy()
                Log.d(TAG, "Batch: Starting with strategy ${strategy.name}, ${_uiState.value.selectedImages.size} images")

                if (twoStageDetector == null && !hybridDetector.isPartiallyAvailable()) {
                    val message = detectorInitErrorMessage ?: "Multi-coupon detector is unavailable."
                    Log.e(TAG, "Batch: No detector or fallback available - aborting processing")
                    telemetryService.trackMultiCouponDetectorState(
                        MultiCouponDetectorState.DISABLED,
                        "no_detector_or_fallback"
                    )
                    updateState { it.copy(isProcessing = false, error = message) }
                    return@launch
                } else if (twoStageDetector == null) {
                    Log.w(TAG, "Batch: TwoStageDetector unavailable - using OCR anchor fallback")
                    telemetryService.trackMultiCouponDetectorState(
                        MultiCouponDetectorState.STUB,
                        "ocr_anchor_fallback"
                    )
                    updateState {
                        it.copy(
                            notice = "Multi-coupon detection disabled – using OCR anchor fallback"
                        )
                    }
                }

                updateState {
                    it.copy(
                        isProcessing = true,
                        processedCount = 0,
                        error = null,
                        imageProcessingStatuses = emptyList(),
                        currentlyProcessingImage = null
                    )
                }

                val images = _uiState.value.selectedImages
                val processedCoupons = mutableListOf<Coupon>()
                var failedCount = 0
                val imageStatuses = mutableListOf<ImageProcessingStatus>()

                for ((index, selectedImage) in images.withIndex()) {
                    val uri = selectedImage.uri
                    updateState { it.copy(currentlyProcessingImage = selectedImage) }
                    var bitmap: android.graphics.Bitmap? = null
                    try {
                        if (!selectedImage.isImage()) {
                            Log.w(TAG, "Batch: Unsupported file type ${selectedImage.mimeType} for uri=$uri")
                            failedCount++
                            imageStatuses.add(
                                ImageProcessingStatus(
                                    image = selectedImage,
                                    success = false,
                                    message = "Unsupported file type"
                                )
                            )
                            continue
                        }

                        // Load and track bitmap
                        bitmap = android.graphics.BitmapFactory.decodeStream(
                            context.contentResolver.openInputStream(uri)
                        )

                        if (bitmap == null) {
                            Log.e(TAG, "Batch: Failed to decode bitmap ${index + 1}")
                            failedCount++
                            imageStatuses.add(
                                ImageProcessingStatus(
                                    image = selectedImage,
                                    success = false,
                                    message = "Unable to open image"
                                )
                            )
                            continue
                        }
                        
                        bitmapManager.trackBitmap(bitmap)
                        
                        // V2.1: Detect multiple coupons per image using hybrid detector
                        val imageCoupons = detectAndExtractMultipleCoupons(uri, bitmap, strategy)
                        
                        if (imageCoupons.isNotEmpty()) {
                            processedCoupons.addAll(imageCoupons)
                            Log.d(TAG, "Batch: Extracted ${imageCoupons.size} coupon(s) from image ${index + 1}/${images.size}")
                            imageStatuses.add(
                                ImageProcessingStatus(
                                    image = selectedImage,
                                    success = true,
                                    message = null,
                                    couponsFound = imageCoupons.size
                                )
                            )
                        } else {
                            Log.w(TAG, "Batch: No coupons extracted from image ${index + 1}/${images.size}")
                            failedCount++
                            imageStatuses.add(
                                ImageProcessingStatus(
                                    image = selectedImage,
                                    success = false,
                                    message = "No coupons detected"
                                )
                            )
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Batch: Error processing ${index + 1}/${images.size}", e)
                        failedCount++
                        imageStatuses.add(
                            ImageProcessingStatus(
                                image = selectedImage,
                                success = false,
                                message = e.message ?: "Unexpected error"
                            )
                        )
                    } finally {
                        bitmap?.let {
                            bitmapManager.releaseBitmap(it)
                        }
                    }

                    // Update progress
                    updateState {
                        it.copy(
                            processedCount = index + 1,
                            currentlyProcessingImage = null,
                            imageProcessingStatuses = imageStatuses.toList()
                        )
                    }
                }

                // Show success or partial success message
                val failedImages = imageStatuses.filterNot { it.success }
                val statusMessage = when {
                    failedCount == 0 -> null
                    failedCount < images.size -> {
                        val failedNames = failedImages.joinToString { it.image.displayName }
                        "Processed ${images.size - failedCount} of ${images.size} files. Issues with: $failedNames"
                    }
                    else -> "Failed to process any files."
                }

                updateState {
                    it.copy(
                        isProcessing = false,
                        processedCoupons = processedCoupons,
                        error = statusMessage,
                        imageProcessingStatuses = imageStatuses.toList(),
                        currentlyProcessingImage = null
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing images", e)
                updateState {
                    it.copy(
                        isProcessing = false,
                        error = "Error processing images: ${e.message}",
                        currentlyProcessingImage = null
                    )
                }
            }
        }
    }

    /**
     * Update the UI state
     */
    private fun updateState(update: (BatchScannerUiState) -> BatchScannerUiState) {
        _uiState.value = update(_uiState.value)
    }

    /**
     * Remove a processed coupon
     * @param index Index of the coupon to remove
     */
    fun removeCoupon(index: Int) {
        val currentCoupons = _uiState.value.processedCoupons.toMutableList()
        if (index in currentCoupons.indices) {
            currentCoupons.removeAt(index)
            _uiState.value = _uiState.value.copy(processedCoupons = currentCoupons)
        }
    }

    /**
     * Save all processed coupons
     */
    fun saveAllCoupons() {
        viewModelScope.launch {
            try {
                updateState { it.copy(isSaving = true, error = null) }

                val coupons = _uiState.value.processedCoupons
                if (coupons.isEmpty()) {
                    updateState { it.copy(isSaving = false, error = "No coupons to save") }
                    return@launch
                }

                // Save all coupons in a transaction if possible
                try {
                    for (coupon in coupons) {
                        couponRepository.insertCoupon(coupon)
                    }

                    // Reset state after saving
                    resetState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving coupons", e)
                    updateState { it.copy(isSaving = false, error = "Error saving coupons: ${e.message}") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in saveAllCoupons", e)
                updateState { it.copy(isSaving = false, error = "Error: ${e.message}") }
            }
        }
    }

    /**
     * Reset processed coupons but keep selected images
     */
    fun resetProcessedCoupons() {
        _uiState.value = _uiState.value.copy(
            processedCoupons = emptyList(),
            imageProcessingStatuses = emptyList(),
            processedCount = 0,
            currentlyProcessingImage = null,
            error = null
        )
    }

    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = BatchScannerUiState()
    }

    // V2: Strategy-specific processing methods (mirrors ScannerViewModel logic)
    
    /**
     * Process image using LEGACY two-stage detection
     * This is the TERMINAL fallback - it never recurses to other strategies
     */
    private suspend fun processWithLegacyPath(uri: Uri, bitmap: android.graphics.Bitmap): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val detector = twoStageDetector ?: run {
                Log.w(TAG, "LEGACY path requested but TwoStageDetector is unavailable. Using OCR fallback instead.")
                return@withContext processWithOcrFirstPath(
                    uri = uri,
                    bitmap = bitmap,
                    allowLlmFallback = true,
                    allowLegacyFallback = false
                )
            }

            // Run two-stage detection
            val couponInstances = detector.detectMultiCoupons(bitmap)

            try {
                if (couponInstances.isNotEmpty()) {
                    // Take first coupon for batch processing
                    val instance = couponInstances.first()
                    val extractedInfo = extractFieldsFromInstance(instance)
                    buildCouponFromFields(
                        extractedInfo,
                        uri,
                        detectionConfidenceMap(instance)
                    )
                } else {
                    // TERMINAL: Create placeholder coupon instead of recursing
                    // This prevents LEGACY→OCR→LLM→LEGACY infinite loop
                    Log.w(TAG, "LEGACY detection failed, creating placeholder coupon")
                    buildPlaceholderCoupon(uri)
                }
            } finally {
                // Release detector-managed crops immediately after processing
                detector.releaseInstances(couponInstances)
            }
        }
    }
    
    /**
     * Create a placeholder coupon when all extraction methods fail
     * This is the absolute last resort to prevent infinite recursion
     */
    private suspend fun buildPlaceholderCoupon(uri: Uri): Coupon {
        return Coupon(
            storeName = "Unknown Store",
            description = "Extraction failed - please edit manually",
            expiryDate = null,
            redeemCode = null,
            imageUri = persistUri(uri),
            category = "Other",
            status = "Active"
        )
    }
    
    /**
     * Process image using LLM_FIRST strategy
     * @param allowOcrFallback If true, can fall back to OCR. If false, falls back to LEGACY to prevent infinite loops.
     */
    private suspend fun processWithLlmFirstPath(
        uri: Uri,
        bitmap: android.graphics.Bitmap,
        allowOcrFallback: Boolean = true,
        allowLegacyFallback: Boolean = true
    ): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val llmResult = localLlmOcrService.processCouponImageTyped(bitmap)
            
            when (llmResult) {
                is com.example.coupontracker.util.ExtractResult.Good -> {
                    buildCouponFromLlmResult(
                        llmResult.info,
                        uri,
                        llmResult.signals.fieldConfidences
                    )
                }
                else -> {
                    if (allowOcrFallback) {
                        // First fallback: Try OCR (but don't allow it to call back to LLM)
                        Log.d(TAG, "LLM_FIRST failed, falling back to OCR_FIRST (terminal)")
                        processWithOcrFirstPath(
                            uri = uri,
                            bitmap = bitmap,
                            allowLlmFallback = false,
                            allowLegacyFallback = allowLegacyFallback
                        )
                    } else {
                        // Terminal fallback: Use LEGACY two-stage detection if available
                        Log.d(TAG, "LLM_FIRST failed with no OCR fallback allowed, using LEGACY")
                        if (allowLegacyFallback) {
                            logStrategyExecution(
                                requested = com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST,
                                executed = "legacy",
                                reason = "llm_terminal_failure"
                            )
                            processWithLegacyPath(uri, bitmap)
                        } else {
                            Log.w(TAG, "LLM_FIRST fallback to LEGACY disabled, returning placeholder coupon")
                            buildPlaceholderCoupon(uri)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Process image using OCR_FIRST strategy
     * @param allowLlmFallback If true, can fall back to LLM. If false, falls back to LEGACY to prevent infinite loops.
     */
    private suspend fun processWithOcrFirstPath(
        uri: Uri,
        bitmap: android.graphics.Bitmap,
        allowLlmFallback: Boolean = true,
        allowLegacyFallback: Boolean = true
    ): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ocrResult = multiEngineOCR.processImage(bitmap)
            
            when (ocrResult) {
                is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success -> {
                    val ocrText = ocrResult.text.ifBlank {
                        ocrResult.extractedInfo.values.joinToString(" ")
                    }
                    val extractionResult = universalExtractionService.extractCoupon(
                        bitmap, ocrText, com.example.coupontracker.universal.ExtractionContext()
                    )
                    
                    if (extractionResult.success) {
                        extractionResult.coupon.copy(imageUri = persistUri(uri))
                    } else {
                        if (allowLlmFallback) {
                            // First fallback: Try LLM (but don't allow it to call back to OCR)
                            Log.d(TAG, "OCR_FIRST low confidence, falling back to LLM_FIRST (terminal)")
                            processWithLlmFirstPath(
                                uri = uri,
                                bitmap = bitmap,
                                allowOcrFallback = false,
                                allowLegacyFallback = allowLegacyFallback
                            )
                        } else {
                            // Terminal fallback: Use LEGACY two-stage detection
                            Log.d(TAG, "OCR_FIRST failed with no LLM fallback allowed, using LEGACY")
                            if (allowLegacyFallback) {
                                logStrategyExecution(
                                    requested = com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST,
                                    executed = "legacy",
                                    reason = "ocr_low_confidence"
                                )
                                processWithLegacyPath(uri, bitmap)
                            } else {
                                Log.w(TAG, "OCR_FIRST fallback to LEGACY disabled, returning placeholder coupon")
                                buildPlaceholderCoupon(uri)
                            }
                        }
                    }
                }
                is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Error -> {
                    if (allowLlmFallback) {
                        // First fallback: Try LLM (but don't allow it to call back to OCR)
                        Log.d(TAG, "OCR_FIRST error, falling back to LLM_FIRST (terminal)")
                        processWithLlmFirstPath(
                            uri = uri,
                            bitmap = bitmap,
                            allowOcrFallback = false,
                            allowLegacyFallback = allowLegacyFallback
                        )
                    } else {
                        // Terminal fallback: Use LEGACY two-stage detection
                        Log.d(TAG, "OCR_FIRST failed with no LLM fallback allowed, using LEGACY")
                        if (allowLegacyFallback) {
                            logStrategyExecution(
                                requested = com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST,
                                executed = "legacy",
                                reason = "ocr_exception"
                            )
                            processWithLegacyPath(uri, bitmap)
                        } else {
                            Log.w(TAG, "OCR_FIRST fallback to LEGACY disabled, returning placeholder coupon")
                            buildPlaceholderCoupon(uri)
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Process image using HYBRID strategy
     */
    private suspend fun processWithHybridPath(uri: Uri, bitmap: android.graphics.Bitmap): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            // Launch both in parallel
            val (llmResult, ocrResult) = coroutineScope {
                val llmDeferred = async {
                    try { localLlmOcrService.processCouponImageTyped(bitmap) } catch (e: Exception) { null }
                }
                val ocrDeferred = async(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val ocr = multiEngineOCR.processImage(bitmap)
                        when (ocr) {
                            is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success -> {
                                val text = ocr.text.ifBlank {
                                    ocr.extractedInfo.values.joinToString(" ")
                                }
                                universalExtractionService.extractCoupon(bitmap, text, com.example.coupontracker.universal.ExtractionContext())
                            }
                            else -> null
                        }
                    } catch (e: Exception) { null }
                }
                Pair(llmDeferred.await(), ocrDeferred.await())
            }
            
            // Fuse results with real per-field confidence comparison
            when {
                llmResult is com.example.coupontracker.util.ExtractResult.Good && ocrResult != null && ocrResult.success -> {
                    // Both successful - perform REAL FUSION (not just LLM)
                    Log.d(TAG, "HYBRID: Both LLM and OCR successful, performing per-field fusion")
                    fuseLlmAndOcrResults(llmResult, ocrResult, uri)
                }
                llmResult is com.example.coupontracker.util.ExtractResult.Good -> {
                    Log.d(TAG, "HYBRID: Only LLM successful")
                    buildCouponFromLlmResult(
                        llmResult.info,
                        uri,
                        llmResult.signals.fieldConfidences
                    )
                }
                ocrResult != null && ocrResult.success -> {
                    Log.d(TAG, "HYBRID: Only OCR successful")
                    ocrResult.coupon.copy(imageUri = persistUri(uri))
                }
                else -> {
                    // Both failed - use LEGACY
                    Log.d(TAG, "HYBRID: Both failed, falling back to LEGACY")
                    logStrategyExecution(
                        requested = com.example.coupontracker.util.ExtractionStrategy.HYBRID,
                        executed = "legacy",
                        reason = "hybrid_no_success"
                    )
                    processWithLegacyPath(uri, bitmap)
                }
            }
        }
    }

    private suspend fun logStrategyExecution(
        requested: com.example.coupontracker.util.ExtractionStrategy,
        executed: String,
        reason: String? = null
    ) {
        val normalizedExecuted = executed.lowercase(Locale.getDefault())
        val message = buildString {
            append("Strategy[batch]: requested=")
            append(requested.name)
            append(", executed=")
            append(normalizedExecuted)
            if (!reason.isNullOrBlank()) {
                append(", reason=")
                append(reason)
            }
        }

        Log.i(TAG, message)
        analyticsTracker.trackStrategyExecution(
            STRATEGY_SURFACE_BATCH,
            requested,
            normalizedExecuted,
            reason
        )

        if (!requested.name.equals(normalizedExecuted, ignoreCase = true) && !reason.isNullOrBlank()) {
            analyticsTracker.trackStrategyFallback(
                STRATEGY_SURFACE_BATCH,
                requested,
                normalizedExecuted,
                reason
            )
        }
    }

    /**
     * Fuse LLM and OCR results by choosing best field per confidence
     * This is the REAL HYBRID fusion logic (mirrors ScannerViewModel)
     */
    private suspend fun fuseLlmAndOcrResults(
        llmResult: com.example.coupontracker.util.ExtractResult.Good,
        ocrResult: com.example.coupontracker.universal.UniversalExtractionResult,
        uri: Uri
    ): Coupon {
        val llmInfo = llmResult.info
        val ocrCoupon = ocrResult.coupon
        
        // For each field, choose the result with higher confidence
        val llmConf = llmResult.signals.fieldConfidences
        
        // Store name: prefer LLM if confident (>0.6), else OCR
        val storeName = if (llmConf.getOrDefault("storeName", 0f) > 0.6f && llmInfo.storeName.isNotBlank()) {
            llmInfo.storeName
        } else if (ocrCoupon.storeName != "Unknown Store") {
            ocrCoupon.storeName
        } else {
            llmInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store"
        }
        
        // Coupon code: prefer LLM if confident (>0.7), else OCR
        val redeemCode = when {
            llmConf.getOrDefault("code", 0f) > 0.7f && !llmInfo.redeemCode.isNullOrBlank() -> llmInfo.redeemCode
            !ocrCoupon.redeemCode.isNullOrBlank() -> ocrCoupon.redeemCode
            else -> llmInfo.redeemCode
        }
        
        // Expiry date: prefer LLM if confident (>0.6), else OCR
        val expiryDate = if (llmConf.getOrDefault("expiry", 0f) > 0.6f && llmInfo.expiryDate != null) {
            llmInfo.expiryDate
        } else {
            ocrCoupon.expiryDate ?: llmInfo.expiryDate
        }
        
        // Cashback detail: prefer LLM detail if confident, else fall back to OCR-derived line
        val llmDetail = llmInfo.cashbackDetail?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }
        val ocrDetail = DescriptionUtils.extractCashbackLine(ocrCoupon.description)
        val cashbackDetail = when {
            llmConf.getOrDefault("cashback", 0f) > 0.6f && llmDetail != null -> llmDetail
            GenericFieldHeuristics.hasMeaningfulCashback(ocrDetail) -> ocrDetail
            else -> llmDetail ?: ocrDetail
        }

        // Description: prefer LLM verbatim text, fall back to OCR text
        val description = listOf(llmInfo.description, ocrCoupon.description)
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "Coupon offer"
        val mergedDescription = DescriptionUtils.appendDetails(description, cashbackDetail)
        
        return Coupon(
            storeName = storeName,
            description = mergedDescription,
            expiryDate = expiryDate,
            redeemCode = redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = persistUri(uri),
            category = llmInfo.category ?: ocrCoupon.category,
            status = "Active"
        )
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

    private fun detectionConfidenceMap(instance: com.example.coupontracker.ml.CouponInstance): Map<String, Float> {
        if (instance.fields.isEmpty()) {
            return emptyMap()
        }
        return instance.fields.associate { detection ->
            detection.fieldType.name.lowercase(Locale.ROOT) to detection.confidence
        }
    }

    // Helper methods

    private suspend fun persistUri(uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        uriPersistenceManager.persistUri(uri)?.toString()
    }
    
    /**
     * Extract real coupon fields from detected instance (mirrors ScannerViewModel logic)
     * This is the REAL LEGACY implementation that was missing
     */
    private suspend fun extractFieldsFromInstance(instance: com.example.coupontracker.ml.CouponInstance): Map<String, String> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val extractedInfo = mutableMapOf<String, String>()
            var finalStage = "UNKNOWN"
            
            // Store detection metadata
            extractedInfo["minicpmConfidence"] = instance.confidence.toString()
            extractedInfo["minicpmDetectionStatus"] = instance.status.name
            
            try {
                // Step 1: Try LLM extraction on the cropped coupon
                Log.d(TAG, "LEGACY: Extracting fields from coupon crop using LLM")
                finalStage = "LLM"
                
                val llmResult = localLlmOcrService.processCouponImageTyped(instance.cropBitmap)
                
                when (llmResult) {
                    is com.example.coupontracker.util.ExtractResult.Good -> {
                        // LLM succeeded - map fields
                        extractedInfo.putAll(mapCouponInfoToFields(llmResult.info))
                        Log.d(TAG, "LEGACY: LLM extraction successful (quality: ${llmResult.signals.qualityScore})")
                    }
                    
                    is com.example.coupontracker.util.ExtractResult.LowQuality -> {
                        // LLM low quality - use it but try OCR fallback
                        extractedInfo.putAll(mapCouponInfoToFields(llmResult.info))
                        
                        Log.w(TAG, "LEGACY: LLM low quality (${llmResult.reason}), trying OCR fallback")
                        finalStage = "LLM+OCR_FALLBACK"
                        
                        // OCR fallback on the crop
                        val ocrResult = multiEngineOCR.processImage(instance.cropBitmap)
                        when (ocrResult) {
                            is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success -> {
                                val ocrText = ocrResult.text.ifBlank {
                                    ocrResult.extractedInfo.values.joinToString(" ")
                                }
                                val universalResult = universalExtractionService.extractCoupon(
                                    instance.cropBitmap, ocrText, com.example.coupontracker.universal.ExtractionContext()
                                )
                                if (universalResult.success) {
                                    // Merge OCR fields where LLM was weak
                                    mergeValidatedFields(extractedInfo, mapCouponToFields(universalResult.coupon))
                                }
                            }
                            else -> {
                                Log.w(TAG, "LEGACY: OCR fallback also failed")
                            }
                        }
                    }
                    
                    is com.example.coupontracker.util.ExtractResult.Failed -> {
                        // LLM failed completely - use OCR only
                        Log.e(TAG, "LEGACY: LLM failed (${llmResult.error.message}), using OCR only")
                        finalStage = "OCR_ONLY"
                        
                        val ocrResult = multiEngineOCR.processImage(instance.cropBitmap)
                        when (ocrResult) {
                            is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success -> {
                                val ocrText = ocrResult.text.ifBlank {
                                    ocrResult.extractedInfo.values.joinToString(" ")
                                }
                                val universalResult = universalExtractionService.extractCoupon(
                                    instance.cropBitmap, ocrText, com.example.coupontracker.universal.ExtractionContext()
                                )
                                if (universalResult.success) {
                                    extractedInfo.putAll(mapCouponToFields(universalResult.coupon))
                                } else {
                                    // Both LLM and OCR failed - return minimal data
                                    extractedInfo["storeName"] = "Unknown Store"
                                    extractedInfo["description"] = "Extraction failed - please edit manually"
                                }
                            }
                            else -> {
                                // Both LLM and OCR failed
                                extractedInfo["storeName"] = "Unknown Store"
                                extractedInfo["description"] = "Extraction failed - please edit manually"
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "LEGACY: Field extraction failed", e)
                finalStage = "ERROR"
                extractedInfo["storeName"] = "Unknown Store"
                extractedInfo["description"] = "Extraction error: ${e.message}"
            }
            
            // Add processing metadata
            extractedInfo["processingStage"] = finalStage
            extractedInfo["extractionMethod"] = "LEGACY_BATCH"
            
            extractedInfo
        }
    }
    
    /**
     * Map CouponInfo to field map (mirrors ScannerViewModel)
     */
    private fun mapCouponInfoToFields(couponInfo: com.example.coupontracker.util.CouponInfo): MutableMap<String, String> {
        val fields = mutableMapOf<String, String>()
        
        // Store name
        if (couponInfo.storeName.isNotBlank()) {
            fields["storeName"] = couponInfo.storeName
        }
        
        // Description
        if (couponInfo.description.isNotBlank()) {
            fields["description"] = couponInfo.description
        }
        
        // Coupon code
        couponInfo.redeemCode?.takeIf { it.isNotBlank() }?.let { 
            fields["code"] = it 
        }
        
        // Expiry date
        couponInfo.expiryDate?.let { date ->
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            fields["expiryDate"] = formatter.format(date)
        }
        
        // Cashback detail (already formatted string)
        val savingsDetail = couponInfo.cashbackDetail
            ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }
            ?: DescriptionUtils.extractCashbackLine(couponInfo.description)
                ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }

        savingsDetail?.let { fields["amount"] = it }
        
        // Category
        couponInfo.category?.let { category ->
            fields["category"] = category
        }
        
        return fields
    }
    
    /**
     * Map Coupon to field map (for OCR results)
     */
    private fun mapCouponToFields(coupon: com.example.coupontracker.data.model.Coupon): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        
        if (coupon.storeName.isNotBlank() && coupon.storeName != "Unknown Store") {
            fields["storeName"] = coupon.storeName
        }
        
        if (coupon.description.isNotBlank()) {
            fields["description"] = coupon.description
        }
        
        coupon.redeemCode?.takeIf { it.isNotBlank() }?.let {
            fields["code"] = it
        }
        
        coupon.expiryDate?.let { date ->
            val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            fields["expiryDate"] = formatter.format(date)
        }
        
        DescriptionUtils.extractCashbackLine(coupon.description)?.let { detail ->
            fields["amount"] = detail
        }
        
        coupon.category?.let { category ->
            fields["category"] = category
        }
        
        return fields
    }
    
    /**
     * Merge OCR fields into LLM fields where LLM was weak or missing
     */
    private fun mergeValidatedFields(llmFields: MutableMap<String, String>, ocrFields: Map<String, String>) {
        ocrFields.forEach { (key, value) ->
            when {
                // If LLM field is missing or generic, use OCR
                !llmFields.containsKey(key) -> llmFields[key] = value
                llmFields[key] == "Unknown Store" && key == "storeName" -> llmFields[key] = value
                llmFields[key]?.isBlank() == true -> llmFields[key] = value
                // For codes, prefer OCR if it looks more valid
                key == "code" && value.length >= 4 && llmFields[key]?.length ?: 0 < 4 -> llmFields[key] = value
            }
        }
    }
    
    private suspend fun buildCouponFromFields(
        fields: Map<String, String>,
        uri: Uri,
        confidenceBreakdown: Map<String, Float> = emptyMap()
    ): Coupon {
        // Parse expiry date
        val expiryDate = fields["expiryDate"]?.let { dateStr ->
            try {
                val formatter = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                formatter.parse(dateStr)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse expiry date: $dateStr", e)
                null
            }
        }
        
        val baseDescription = fields["description"]?.takeIf { it.isNotBlank() } ?: "Batch processed coupon"
        val mergedDescription = DescriptionUtils.appendDetails(baseDescription, fields["amount"])
        
        return Coupon(
            storeName = fields["storeName"] ?: "Unknown Store",
            description = mergedDescription,
            expiryDate = expiryDate,
            redeemCode = fields["code"]?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = persistUri(uri),
            category = fields["category"],
            status = "Active"
        )
    }
    
    private suspend fun buildCouponFromLlmResult(
        couponInfo: com.example.coupontracker.util.CouponInfo,
        uri: Uri,
        fieldConfidences: Map<String, Float> = emptyMap()
    ): Coupon {
        val description = buildDescriptionFromInfo(couponInfo)
        
        return Coupon(
            storeName = couponInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store",
            description = description.ifBlank { "Extracted via LLM" },
            expiryDate = couponInfo.expiryDate,
            redeemCode = couponInfo.redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" },
            imageUri = persistUri(uri),
            status = "Active"
        )
    }

    private fun buildDescriptionFromInfo(info: com.example.coupontracker.util.CouponInfo): String {
        val segments = linkedSetOf<String>()

        if (info.description.isNotBlank()) {
            segments += info.description.trim()
        }

        info.cashbackDetail?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }?.let { detail ->
            segments += detail.trim()
        }

        info.minimumPurchase?.takeIf { it > 0 }?.let {
            segments += "Minimum purchase: ${formatCurrency(it)}"
        }

        info.maximumDiscount?.takeIf { it > 0 }?.let {
            segments += "Maximum discount: ${formatCurrency(it)}"
        }

        info.category?.takeIf { it.isNotBlank() }?.let {
            segments += "Category: ${it.trim()}"
        }

        info.paymentMethod?.takeIf { it.isNotBlank() }?.let {
            segments += "Payment method: ${it.trim()}"
        }

        info.platformType?.takeIf { it.isNotBlank() }?.let {
            segments += "Platform: ${it.trim()}"
        }

        info.usageLimit?.takeIf { it > 0 }?.let {
            segments += "Usage limit: $it"
        }

        info.rating?.takeIf { it.isNotBlank() }?.let {
            segments += "Rating: ${it.trim()}"
        }

        info.status?.takeIf { it.isNotBlank() && !it.equals("Active", ignoreCase = true) }?.let {
            segments += "Status: ${it.trim()}"
        }

        if (segments.isEmpty()) {
            return "Extracted via LLM"
        }

        return segments.joinToString(separator = "\n")
    }

    private fun formatCurrency(amount: Double): String {
        val rounded = if (amount % 1.0 == 0.0) {
            amount.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
        return "₹$rounded"
    }

    override fun onCleared() {
        super.onCleared()
        // V2: Cleanup detector bitmap crops to prevent memory leaks
        try {
            twoStageDetector?.cleanupBitmaps()
            Log.d(TAG, "Cleaned up TwoStageDetector bitmap crops")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TwoStageDetector", e)
        }
    }
    
    /**
     * V2.1: Detect and extract multiple coupons from a single image
     * Uses HybridCouponDetector to find coupon regions, then extracts each
     */
    private suspend fun detectAndExtractMultipleCoupons(
        uri: Uri,
        bitmap: android.graphics.Bitmap,
        strategy: com.example.coupontracker.util.ExtractionStrategy
    ): List<Coupon> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        
        Log.d(TAG, "Detecting multiple coupons in image...")
        
        try {
            // Step 1: Run OCR on full image
            val ocrResult = multiEngineOCR.processImage(bitmap)
            
            if (ocrResult !is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success) {
                Log.w(TAG, "OCR failed, falling back to single coupon extraction")
                return@withContext listOf(extractSingleCoupon(uri, bitmap, strategy))
            }
            
            // Step 2: Detect coupon regions using hybrid detector
            val couponRegions = hybridDetector.detectCoupons(bitmap, ocrResult)
            
            Log.d(TAG, "Hybrid detector found ${couponRegions.size} coupon region(s)")
            
            // Step 3: If only one region or full-image fallback, use standard extraction
            if (couponRegions.size == 1 && couponRegions[0].source == com.example.coupontracker.ml.HybridCouponDetector.DetectionSource.FALLBACK) {
                Log.d(TAG, "Single coupon detected, using standard extraction")
                return@withContext listOf(extractSingleCoupon(uri, bitmap, strategy))
            }
            
            // Step 4: Extract each detected coupon region
            val extractedCoupons: List<Coupon> = if (batchPipelineFlag.isEnabled()) {
                val viaPipeline = extractViaCouponRegionPipeline(bitmap, couponRegions, uri)
                if (viaPipeline.isNotEmpty()) {
                    Log.d(TAG, "Pipeline extraction yielded ${viaPipeline.size} coupon(s)")
                    viaPipeline
                } else {
                    Log.w(TAG, "Pipeline yielded zero coupons; falling back to per-region loop")
                    extractCouponsViaPerRegionLoop(bitmap, couponRegions, uri, strategy)
                }
            } else {
                extractCouponsViaPerRegionLoop(bitmap, couponRegions, uri, strategy)
            }
            
            // If no coupons extracted from regions, fallback to single coupon
            if (extractedCoupons.isEmpty()) {
                Log.w(TAG, "No coupons extracted from regions, falling back to single coupon extraction")
                return@withContext listOf(extractSingleCoupon(uri, bitmap, strategy))
            }
            
            return@withContext extractedCoupons
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in multi-coupon detection", e)
            // Fallback to single coupon extraction
            return@withContext listOf(extractSingleCoupon(uri, bitmap, strategy))
        }
    }
    
    /**
     * Extract single coupon using standard strategy
     */
    private suspend fun extractSingleCoupon(
        uri: Uri,
        bitmap: android.graphics.Bitmap,
        strategy: com.example.coupontracker.util.ExtractionStrategy
    ): Coupon {
        return when (strategy) {
            com.example.coupontracker.util.ExtractionStrategy.LEGACY -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = strategy.name.lowercase(Locale.getDefault())
                )
                processWithLegacyPath(uri, bitmap)
            }
            com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = strategy.name.lowercase(Locale.getDefault())
                )
                processWithLlmFirstPath(uri, bitmap)
            }
            com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = strategy.name.lowercase(Locale.getDefault())
                )
                processWithOcrFirstPath(uri, bitmap)
            }
            com.example.coupontracker.util.ExtractionStrategy.HYBRID -> {
                logStrategyExecution(
                    requested = strategy,
                    executed = strategy.name.lowercase(Locale.getDefault())
                )
                processWithHybridPath(uri, bitmap)
            }
        }
    }
    
    /**
     * Extract coupon from a detected region
     */
    private suspend fun extractCouponFromRegion(
        regionBitmap: android.graphics.Bitmap,
        region: com.example.coupontracker.ml.HybridCouponDetector.CouponRegion,
        strategy: com.example.coupontracker.util.ExtractionStrategy,
        uri: Uri
    ): Coupon {
        // If region already has OCR text, use it directly with LLM
        if (region.ocrText.isNotBlank() && strategy in listOf(
            com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST,
            com.example.coupontracker.util.ExtractionStrategy.HYBRID
        )) {
            try {
                Log.d(TAG, "Using pre-extracted OCR text (${region.ocrText.length} chars) for LLM extraction")
                
                // Use LLM with pre-extracted OCR text
                val llmResult = localLlmOcrService.processCouponImageTyped(regionBitmap)
                
                if (llmResult is com.example.coupontracker.util.ExtractResult.Good) {
                    return convertExtractResultToCoupon(llmResult, uri)
                }
            } catch (e: Exception) {
                Log.w(TAG, "LLM extraction with pre-extracted OCR failed: ${e.message}")
            }
        }
        
        // Fallback: extract using standard strategy path
        return extractSingleCoupon(uri, regionBitmap, strategy)
    }

    /**
     * Original per-region extraction loop (lifted into a method so the flag
     * branch in `detectMultipleCoupons` is readable). Behaviour byte-equivalent
     * to the pre-rewire code.
     */
    private suspend fun extractCouponsViaPerRegionLoop(
        bitmap: android.graphics.Bitmap,
        couponRegions: List<com.example.coupontracker.ml.HybridCouponDetector.CouponRegion>,
        uri: android.net.Uri,
        strategy: com.example.coupontracker.util.ExtractionStrategy
    ): List<com.example.coupontracker.data.model.Coupon> {
        val extractedCoupons = mutableListOf<com.example.coupontracker.data.model.Coupon>()
        for ((regionIndex, region) in couponRegions.withIndex()) {
            try {
                Log.d(TAG, "Extracting coupon region ${regionIndex + 1}/${couponRegions.size}")
                val regionBitmap = cropBitmapToRegion(bitmap, region.boundingBox)
                if (regionBitmap == null) {
                    Log.w(TAG, "Failed to crop region ${regionIndex + 1}, skipping")
                    continue
                }
                bitmapManager.trackBitmap(regionBitmap)
                try {
                    val coupon = extractCouponFromRegion(
                        regionBitmap = regionBitmap,
                        region = region,
                        strategy = strategy,
                        uri = uri
                    )
                    extractedCoupons.add(coupon)
                    Log.d(TAG, "Successfully extracted coupon ${regionIndex + 1}: store='${coupon.storeName}', code='${coupon.redeemCode}'")
                } finally {
                    bitmapManager.releaseBitmap(regionBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting region ${regionIndex + 1}", e)
            }
        }
        return extractedCoupons
    }

    /**
     * Pipeline-backed batch extraction. Crops each detected region, runs
     * the unified per-region OCR + extraction pipeline, then converts each
     * canonical JSON result into a `Coupon`. Dedup + cap are applied by
     * the pipeline.
     */
    private suspend fun extractViaCouponRegionPipeline(
        bitmap: android.graphics.Bitmap,
        couponRegions: List<com.example.coupontracker.ml.HybridCouponDetector.CouponRegion>,
        uri: android.net.Uri
    ): List<com.example.coupontracker.data.model.Coupon> {
        val crops = mutableListOf<android.graphics.Bitmap>()
        for (region in couponRegions) {
            val crop = cropBitmapToRegion(bitmap, region.boundingBox) ?: continue
            bitmapManager.trackBitmap(crop)
            crops += crop
        }
        if (crops.isEmpty()) return emptyList()
        return try {
            val canonicalJsons = regionPipeline.extractFromCrops(crops)
            canonicalJsons.map { json ->
                com.example.coupontracker.extraction.multi.JsonToCouponConverter.convert(json, uri)
            }
        } finally {
            crops.forEach { bitmapManager.releaseBitmap(it) }
        }
    }

    /**
     * Crop bitmap to region bounds
     */
    private fun cropBitmapToRegion(bitmap: android.graphics.Bitmap, region: android.graphics.Rect): android.graphics.Bitmap? {
        return try {
            // Validate bounds
            val left = region.left.coerceIn(0, bitmap.width)
            val top = region.top.coerceIn(0, bitmap.height)
            val right = region.right.coerceIn(left, bitmap.width)
            val bottom = region.bottom.coerceIn(top, bitmap.height)
            
            val width = right - left
            val height = bottom - top
            
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid crop region: width=$width, height=$height")
                return null
            }
            
            android.graphics.Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e)
            null
        }
    }
    
    /**
     * Convert ExtractResult to Coupon
     */
    private fun convertExtractResultToCoupon(result: com.example.coupontracker.util.ExtractResult.Good, uri: Uri): Coupon {
        val couponInfo = result.info
        val signals = result.signals
        val runPath = result.runPath
        val runPathSummary = runPath.final.takeIf { it.isNotBlank() }?.let { final ->
            "${runPath.strategy} → $final"
        }
        val description = DescriptionUtils.appendDetails(couponInfo.description, couponInfo.cashbackDetail)
        return Coupon(
            storeName = couponInfo.storeName,
            description = description,
            expiryDate = couponInfo.expiryDate,
            redeemCode = couponInfo.redeemCode,
            imageUri = uri.toString(),
            status = "Active",
            needsAttention = couponInfo.needsAttention,
            storeNameSource = couponInfo.storeNameSource,
            storeNameEvidence = couponInfo.storeNameEvidence,
            extractionQualityScore = signals.qualityScore,
            extractionConfidenceBreakdown = signals.fieldConfidences,
            extractionStage = signals.stage.name,
            extractionRunPath = runPathSummary,
            extractionTimestamp = java.util.Date()
        )
    }

    private fun createSelectedImage(
        uri: Uri,
        selectionOrder: Int,
        explicitMimeType: String?
    ): SelectedImage {
        val displayName = resolveDisplayName(uri) ?: "Image $selectionOrder"
        val resolvedMimeType = explicitMimeType ?: context.contentResolver.getType(uri)
        val guessedMimeType = resolvedMimeType ?: guessMimeType(displayName)
        return SelectedImage(
            uri = uri,
            displayName = displayName,
            mimeType = guessedMimeType,
            selectionOrder = selectionOrder
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()
    }

    private fun guessMimeType(displayName: String): String {
        return when {
            displayName.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            displayName.endsWith(".png", ignoreCase = true) -> "image/png"
            displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            else -> "image/*"
        }
    }
}

/**
 * UI state for the batch scanner
 */
data class BatchScannerUiState(
    val selectedImages: List<SelectedImage> = emptyList(),
    val processedCoupons: List<Coupon> = emptyList(),
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val processedCount: Int = 0,
    val error: String? = null,
    val imageProcessingStatuses: List<ImageProcessingStatus> = emptyList(),
    val currentlyProcessingImage: SelectedImage? = null,
    val notice: String? = null
)

data class SelectedImage(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val selectionOrder: Int
) {
    fun isImage(): Boolean = mimeType.startsWith("image/") || mimeType == "image/*"
}

data class ImageProcessingStatus(
    val image: SelectedImage,
    val success: Boolean,
    val message: String?,
    val couponsFound: Int = 0
)

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
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.domain.usecase.BatchScanReadinessDecision
import com.example.coupontracker.domain.usecase.BatchScanReadinessUseCase
import com.example.coupontracker.domain.usecase.SaveBatchCouponsUseCase
import com.example.coupontracker.extraction.capture.BatchCaptureInput
import com.example.coupontracker.extraction.capture.BatchCaptureItemStatus
import com.example.coupontracker.extraction.capture.BatchCaptureOrchestrator
import com.example.coupontracker.extraction.capture.BatchRegionIsolationCoordinator
import com.example.coupontracker.extraction.capture.BatchRegionExtractionRunner
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.CouponInputManager
import com.example.coupontracker.ml.MultiCouponDetectorDisabledException
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.MultiCouponDetectorState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class BatchScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,  // Tesseract OCR engine
    private val bitmapManager: com.example.coupontracker.util.BitmapManager,  // V2: Bitmap memory management
    private val universalExtractionService: com.example.coupontracker.universal.UniversalExtractionService,  // V2: Universal extraction
    private val ocrFirstCouponExtractor: OcrFirstCouponExtractor,
    private val couponInputManager: CouponInputManager,
    private val analyticsTracker: AnalyticsTracker,
    private val telemetryService: ExtractionTelemetryService,
    private val regionPipeline: com.example.coupontracker.extraction.multi.CouponRegionPipeline,
    private val batchPipelineFlag: com.example.coupontracker.extraction.multi.BatchPipelineFeatureFlag,
    private val batchScanReadinessUseCase: BatchScanReadinessUseCase,
    private val batchCaptureOrchestrator: BatchCaptureOrchestrator,
    private val batchRegionIsolationCoordinator: BatchRegionIsolationCoordinator,
    private val batchRegionExtractionRunner: BatchRegionExtractionRunner,
    private val saveBatchCouponsUseCase: SaveBatchCouponsUseCase
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
        // Enable OCR availability for batch capture.
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

                when (val readiness = batchScanReadinessUseCase.decide(
                    twoStageDetectorAvailable = twoStageDetector != null,
                    fallbackDetectorAvailable = hybridDetector.isPartiallyAvailable(),
                    detectorInitErrorMessage = detectorInitErrorMessage
                )) {
                    BatchScanReadinessDecision.Ready -> Unit
                    is BatchScanReadinessDecision.Abort -> {
                        Log.e(TAG, "Batch: No detector or fallback available - aborting processing")
                        telemetryService.trackMultiCouponDetectorState(
                            MultiCouponDetectorState.DISABLED,
                            readiness.telemetryReason
                        )
                        updateState { it.copy(isProcessing = false, error = readiness.message) }
                        return@launch
                    }
                    is BatchScanReadinessDecision.UseOcrAnchorFallback -> {
                        Log.w(TAG, "Batch: TwoStageDetector unavailable - using OCR anchor fallback")
                        telemetryService.trackMultiCouponDetectorState(
                            MultiCouponDetectorState.STUB,
                            readiness.telemetryReason
                        )
                        updateState {
                            it.copy(notice = readiness.notice)
                        }
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
                val inputs = images.map { it.toBatchCaptureInput() }
                val selectedImagesByUri = images.associateBy { it.uri.toString() }

                val batchResult = batchCaptureOrchestrator.process(
                    inputs = inputs,
                    decodeBitmap = { inputUri ->
                        android.graphics.BitmapFactory.decodeStream(
                            context.contentResolver.openInputStream(inputUri)
                        )
                    },
                    trackBitmap = { bitmapManager.trackBitmap(it) },
                    releaseBitmap = { bitmapManager.releaseBitmap(it) },
                    processPdf = { inputUri -> couponInputManager.processPdfUri(inputUri) },
                    extractImageCoupons = { inputUri, imageBitmap ->
                        detectAndExtractMultipleCoupons(inputUri, imageBitmap)
                    },
                    onItemStarted = { input ->
                        updateState {
                            it.copy(currentlyProcessingImage = selectedImagesByUri[input.uri.toString()])
                        }
                    },
                    onItemFinished = { progress ->
                        updateState {
                            it.copy(
                                processedCount = progress.processedCount,
                                currentlyProcessingImage = null,
                                imageProcessingStatuses = progress.itemStatuses.toImageProcessingStatuses(
                                    selectedImagesByUri
                                )
                            )
                        }
                    }
                )

                updateState {
                    it.copy(
                        isProcessing = false,
                        processedCoupons = batchResult.coupons,
                        error = batchResult.errorMessage,
                        imageProcessingStatuses = batchResult.itemStatuses.toImageProcessingStatuses(
                            selectedImagesByUri
                        ),
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
                    saveBatchCouponsUseCase(coupons)
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

    /**
     * Process one image through the shared OCR-first capture path. Qwen cleanup
     * is intentionally not part of capture.
     */
    private suspend fun processWithOcrFirstPath(
        uri: Uri,
        bitmap: android.graphics.Bitmap
    ): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persistedUri = persistUri(uri)
            val captureTimestamp = extractCaptureTimestamp(persistedUri, uri)
            val extraction = ocrFirstCouponExtractor.extract(
                bitmap = bitmap,
                imageUri = persistedUri,
                captureTimestamp = captureTimestamp
            )
            if (!extraction.success) {
                Log.w(TAG, "OCR_FIRST low confidence; returning shared OCR review result")
            }
            extraction.coupon
        }
    }

    private fun extractCaptureTimestamp(persistedUri: String?, originalUri: Uri): java.util.Date? {
        return runCatching {
            persistedUri?.let { ImageMetadataExtractor.extractCaptureTimestamp(context, Uri.parse(it)) }
        }.getOrNull()
            ?: runCatching {
                ImageMetadataExtractor.extractCaptureTimestamp(context, originalUri)
            }.getOrNull()
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

    private suspend fun persistUri(uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        uriPersistenceManager.persistUri(uri)?.toString()
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
        bitmap: android.graphics.Bitmap
    ): List<Coupon> = batchRegionIsolationCoordinator.extract(
        uri = uri,
        bitmap = bitmap,
        runOcr = { sourceBitmap -> multiEngineOCR.processImage(sourceBitmap) },
        detectRegions = { sourceBitmap, ocrResult -> hybridDetector.detectCoupons(sourceBitmap, ocrResult) },
        extractIsolatedRegions = { isolatedRegions ->
            batchRegionExtractionRunner.extract(
                bitmap = bitmap,
                couponRegions = isolatedRegions,
                usePipeline = batchPipelineFlag.isEnabled(),
                trackBitmap = bitmapManager::trackBitmap,
                releaseBitmap = bitmapManager::releaseBitmap,
                extractPipeline = { crops -> extractPipelineCrops(crops, uri) },
                extractSingleRegion = { regionBitmap ->
                    extractCouponFromRegion(
                        regionBitmap = regionBitmap,
                        uri = uri
                    )
                }
            )
        }
    )
    
    /**
     * Extract single coupon using standard strategy
     */
    private suspend fun extractSingleCoupon(
        uri: Uri,
        bitmap: android.graphics.Bitmap,
        strategy: com.example.coupontracker.util.ExtractionStrategy
    ): Coupon {
        logStrategyExecution(
            requested = strategy,
            executed = "ocr_first_manual_clean"
        )
        return processWithOcrFirstPath(uri, bitmap)
    }
    
    /**
     * Extract coupon from a detected region
     */
    private suspend fun extractCouponFromRegion(
        regionBitmap: android.graphics.Bitmap,
        uri: Uri
    ): Coupon {
        return extractSingleCoupon(uri, regionBitmap, com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST)
    }

    /**
     * Converts pipeline canonical JSON into coupons. Crop ownership lives in
     * BatchRegionExtractionRunner; dedup + cap are applied by the pipeline.
     */
    private suspend fun extractPipelineCrops(
        crops: List<android.graphics.Bitmap>,
        uri: android.net.Uri
    ): List<com.example.coupontracker.data.model.Coupon> {
        val canonicalJsons = regionPipeline.extractFromCrops(crops)
        return canonicalJsons.map { json ->
            com.example.coupontracker.extraction.multi.JsonToCouponConverter.convert(json, uri)
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
            status = com.example.coupontracker.data.model.Coupon.Status.ACTIVE,
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
    fun isPdf(): Boolean = mimeType == "application/pdf"
}

private fun SelectedImage.toBatchCaptureInput(): BatchCaptureInput {
    return BatchCaptureInput(
        uri = uri,
        displayName = displayName,
        mimeType = mimeType
    )
}

private fun List<BatchCaptureItemStatus>.toImageProcessingStatuses(
    selectedImagesByUri: Map<String, SelectedImage>
): List<ImageProcessingStatus> {
    return map { status ->
        ImageProcessingStatus(
            image = selectedImagesByUri.getValue(status.input.uri.toString()),
            success = status.success,
            message = status.message,
            couponsFound = status.couponsFound
        )
    }
}

data class ImageProcessingStatus(
    val image: SelectedImage,
    val success: Boolean,
    val message: String?,
    val couponsFound: Int = 0
)

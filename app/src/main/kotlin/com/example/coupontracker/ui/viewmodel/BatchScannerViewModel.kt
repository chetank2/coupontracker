package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponInputManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for batch scanning of multiple coupons
 */
@HiltViewModel
class BatchScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    private val bitmapManager: com.example.coupontracker.util.BitmapManager,  // V2: Bitmap memory management
    private val localLlmOcrService: com.example.coupontracker.util.LocalLlmOcrService,  // V2: LLM service
    private val universalExtractionService: com.example.coupontracker.universal.UniversalExtractionService  // V2: Universal extraction
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BatchScannerUiState())
    val uiState: StateFlow<BatchScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR = com.example.coupontracker.util.MultiEngineOCR(context)
    private val uriPersistenceManager = com.example.coupontracker.util.UriPersistenceManager(context)
    private val detectorInitializationResult = initializeTwoStageDetector()
    private val twoStageDetector = detectorInitializationResult.detector
    private val detectorInitErrorMessage = detectorInitializationResult.errorMessage

    companion object {
        private const val TAG = "BatchScannerViewModel"
    }

    private data class DetectorInitializationResult(
        val detector: com.example.coupontracker.ml.TwoStageDetector?,
        val errorMessage: String?,
        val exception: Throwable?
    )

    private fun initializeTwoStageDetector(): DetectorInitializationResult {
        return try {
            DetectorInitializationResult(com.example.coupontracker.ml.TwoStageDetector(context), null, null)
        } catch (e: IllegalStateException) {
            val message = e.message ?: "Multi-coupon detector assets are not available for this build."
            Log.e(TAG, "TwoStageDetector initialization blocked", e)
            DetectorInitializationResult(null, message, e)
        } catch (e: Exception) {
            val message = e.message ?: "Failed to initialize multi-coupon detector."
            Log.e(TAG, "TwoStageDetector initialization failed", e)
            DetectorInitializationResult(null, message, e)
        }
    }

    init {
        // V2: Enable OCR network availability (critical for OCR_FIRST and HYBRID strategies)
        multiEngineOCR.setNetworkAvailability(true)
        Log.d(TAG, "MultiEngineOCR network availability enabled for batch processing")

        detectorInitErrorMessage?.let { error ->
            val message = "Multi-coupon detection unavailable: $error"
            Log.e(TAG, message, detectorInitializationResult.exception)
            updateState { it.copy(error = message) }
        }
    }

    /**
     * Add images to the batch
     * @param uris List of image URIs to add
     */
    fun addImages(uris: List<Uri>) {
        val currentImages = _uiState.value.selectedImages.toMutableList()
        currentImages.addAll(uris)
        _uiState.value = _uiState.value.copy(selectedImages = currentImages)
    }

    /**
     * Add a PDF to the batch
     * @param uri PDF URI to add
     */
    fun addPdf(uri: Uri) {
        val currentImages = _uiState.value.selectedImages.toMutableList()
        currentImages.add(uri)
        _uiState.value = _uiState.value.copy(selectedImages = currentImages)
    }

    /**
     * Remove an image from the batch
     * @param index Index of the image to remove
     */
    fun removeImage(index: Int) {
        val currentImages = _uiState.value.selectedImages.toMutableList()
        if (index in currentImages.indices) {
            currentImages.removeAt(index)
            _uiState.value = _uiState.value.copy(selectedImages = currentImages)
        }
    }

    /**
     * Clear all selected images
     */
    fun clearImages() {
        _uiState.value = _uiState.value.copy(selectedImages = emptyList())
    }
    
    /**
     * Check if TwoStageDetector is available for batch scanning
     * Returns false if detector is null or models are not loaded
     */
    fun isTwoStageDetectorAvailable(): Boolean {
        return try {
            twoStageDetector != null && twoStageDetector?.getMemoryStats() != null
        } catch (e: Exception) {
            Log.e(TAG, "Error checking TwoStageDetector availability", e)
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

                if (twoStageDetector == null) {
                    val message = detectorInitErrorMessage ?: "Multi-coupon detector is unavailable."
                    Log.e(TAG, "Batch: TwoStageDetector unavailable - aborting processing")
                    updateState { it.copy(isProcessing = false, error = message) }
                    return@launch
                }

                updateState { it.copy(isProcessing = true, processedCount = 0, error = null) }

                val images = _uiState.value.selectedImages
                val processedCoupons = mutableListOf<Coupon>()
                var failedCount = 0

                for ((index, uri) in images.withIndex()) {
                    var bitmap: android.graphics.Bitmap? = null
                    try {
                        // Load and track bitmap
                        bitmap = android.graphics.BitmapFactory.decodeStream(
                            context.contentResolver.openInputStream(uri)
                        )
                        
                        if (bitmap == null) {
                            Log.e(TAG, "Batch: Failed to decode bitmap ${index + 1}")
                            failedCount++
                            continue
                        }
                        
                        bitmapManager.trackBitmap(bitmap)
                        
                        // V2: Route through selected strategy (NOT CouponInputManager!)
                        val coupon = when (strategy) {
                            com.example.coupontracker.util.ExtractionStrategy.LEGACY -> 
                                processWithLegacyPath(uri, bitmap)
                            com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST -> 
                                processWithLlmFirstPath(uri, bitmap)
                            com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST -> 
                                processWithOcrFirstPath(uri, bitmap)
                            com.example.coupontracker.util.ExtractionStrategy.HYBRID -> 
                                processWithHybridPath(uri, bitmap)
                        }
                        
                        processedCoupons.add(coupon)
                        Log.d(TAG, "Batch: Successfully processed ${index + 1}/${images.size} via ${strategy.name}")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Batch: Error processing ${index + 1}/${images.size}", e)
                        failedCount++
                    } finally {
                        bitmap?.let { 
                            bitmapManager.releaseBitmap(it)
                        }
                    }

                    // Update progress
                    updateState { it.copy(processedCount = index + 1) }
                }

                // Show success or partial success message
                val statusMessage = when {
                    failedCount == 0 -> null
                    failedCount < images.size -> "Processed ${images.size - failedCount} of ${images.size} images. Some images could not be processed."
                    else -> "Failed to process any images."
                }

                updateState {
                    it.copy(
                        isProcessing = false,
                        processedCoupons = processedCoupons,
                        error = statusMessage
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing images", e)
                updateState {
                    it.copy(
                        isProcessing = false,
                        error = "Error processing images: ${e.message}"
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
            val detector = twoStageDetector
                ?: throw IllegalStateException(detectorInitErrorMessage ?: "Multi-coupon detector is unavailable.")

            // Run two-stage detection
            val couponInstances = detector.detectMultiCoupons(bitmap)

            try {
                if (couponInstances.isNotEmpty()) {
                    // Take first coupon for batch processing
                    val instance = couponInstances.first()
                    val extractedInfo = extractFieldsFromInstance(instance)
                    buildCouponFromFields(extractedInfo, uri)
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
            id = 0,
            storeName = "Unknown Store",
            description = "Extraction failed - please edit manually",
            expiryDate = null,
            cashbackAmount = 0.0,
            redeemCode = null,
            imageUri = persistUri(uri),
            category = "Other",
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date(),
            cashbackType = com.example.coupontracker.data.model.CashbackType.TEXT.name.lowercase(),
            cashbackValueNum = 0.0,
            cashbackCurrency = null,
            offerText = "Unable to extract coupon details"
        )
    }
    
    /**
     * Process image using LLM_FIRST strategy
     * @param allowOcrFallback If true, can fall back to OCR. If false, falls back to LEGACY to prevent infinite loops.
     */
    private suspend fun processWithLlmFirstPath(
        uri: Uri, 
        bitmap: android.graphics.Bitmap,
        allowOcrFallback: Boolean = true
    ): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val llmResult = localLlmOcrService.processCouponImageTyped(bitmap)
            
            when (llmResult) {
                is com.example.coupontracker.util.ExtractResult.Good -> {
                    buildCouponFromLlmResult(llmResult.info, uri)
                }
                else -> {
                    if (allowOcrFallback) {
                        // First fallback: Try OCR (but don't allow it to call back to LLM)
                        Log.d(TAG, "LLM_FIRST failed, falling back to OCR_FIRST (terminal)")
                        processWithOcrFirstPath(uri, bitmap, allowLlmFallback = false)
                    } else {
                        // Terminal fallback: Use LEGACY two-stage detection
                        Log.d(TAG, "LLM_FIRST failed with no OCR fallback allowed, using LEGACY")
                        processWithLegacyPath(uri, bitmap)
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
        allowLlmFallback: Boolean = true
    ): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val ocrResult = multiEngineOCR.processImage(bitmap)
            
            when (ocrResult) {
                is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Success -> {
                    val ocrText = ocrResult.extractedInfo.values.joinToString(" ")
                    val extractionResult = universalExtractionService.extractCoupon(
                        bitmap, ocrText, com.example.coupontracker.universal.ExtractionContext()
                    )
                    
                    if (extractionResult.success) {
                        extractionResult.coupon.copy(imageUri = persistUri(uri))
                    } else {
                        if (allowLlmFallback) {
                            // First fallback: Try LLM (but don't allow it to call back to OCR)
                            Log.d(TAG, "OCR_FIRST low confidence, falling back to LLM_FIRST (terminal)")
                            processWithLlmFirstPath(uri, bitmap, allowOcrFallback = false)
                        } else {
                            // Terminal fallback: Use LEGACY two-stage detection
                            Log.d(TAG, "OCR_FIRST failed with no LLM fallback allowed, using LEGACY")
                            processWithLegacyPath(uri, bitmap)
                        }
                    }
                }
                is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Error -> {
                    if (allowLlmFallback) {
                        // First fallback: Try LLM (but don't allow it to call back to OCR)
                        Log.d(TAG, "OCR_FIRST error, falling back to LLM_FIRST (terminal)")
                        processWithLlmFirstPath(uri, bitmap, allowOcrFallback = false)
                    } else {
                        // Terminal fallback: Use LEGACY two-stage detection
                        Log.d(TAG, "OCR_FIRST failed with no LLM fallback allowed, using LEGACY")
                        processWithLegacyPath(uri, bitmap)
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
                                val text = ocr.extractedInfo.values.joinToString(" ")
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
                    buildCouponFromLlmResult(llmResult.info, uri)
                }
                ocrResult != null && ocrResult.success -> {
                    Log.d(TAG, "HYBRID: Only OCR successful")
                    ocrResult.coupon.copy(imageUri = persistUri(uri))
                }
                else -> {
                    // Both failed - use LEGACY
                    Log.d(TAG, "HYBRID: Both failed, falling back to LEGACY")
                    processWithLegacyPath(uri, bitmap)
                }
            }
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
        
        // Cashback: prefer LLM if confident (>0.6), else OCR
        val (cashbackAmount, cashbackValueNum, cashbackType, cashbackCurrency) = 
            if (llmConf.getOrDefault("cashback", 0f) > 0.6f && llmInfo.cashbackAmount != null) {
                val amount = llmInfo.cashbackAmount
                if (llmInfo.discountType == "PERCENTAGE") {
                    Tuple4(amount, amount, com.example.coupontracker.data.model.CashbackType.PERCENT.name.lowercase(), null)
                } else {
                    Tuple4(amount, amount, com.example.coupontracker.data.model.CashbackType.AMOUNT.name.lowercase(), "INR")
                }
            } else if (ocrCoupon.getCashbackNumericValue() > 0) {
                val value = ocrCoupon.getCashbackNumericValue()
                Tuple4(value, value, ocrCoupon.cashbackType ?: "text", ocrCoupon.cashbackCurrency)
            } else {
                Tuple4(0.0, 0.0, com.example.coupontracker.data.model.CashbackType.TEXT.name.lowercase(), null)
            }
        
        // Description: combine both sources
        val description = when {
            llmInfo.description.isNotBlank() && ocrCoupon.description.isNotBlank() -> 
                "${llmInfo.description} (Hybrid: LLM + OCR)"
            llmInfo.description.isNotBlank() -> llmInfo.description
            ocrCoupon.description.isNotBlank() -> ocrCoupon.description
            else -> "Extracted via Hybrid method"
        }
        
        return Coupon(
            id = 0,
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = persistUri(uri),
            category = llmInfo.category ?: ocrCoupon.category,
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date(),
            cashbackType = cashbackType,
            cashbackValueNum = cashbackValueNum,
            cashbackCurrency = cashbackCurrency,
            offerText = ocrCoupon.offerText ?: llmInfo.description
        )
    }
    
    // Helper data class for tuple return
    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    
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
                                val ocrText = ocrResult.extractedInfo.values.joinToString(" ")
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
                                val ocrText = ocrResult.extractedInfo.values.joinToString(" ")
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
        
        // Cashback amount (preserve type information)
        couponInfo.cashbackAmount?.let { amount ->
            if (couponInfo.discountType == "PERCENTAGE") {
                fields["amount"] = "${amount}%"  // ✅ Preserve % for percentage
            } else {
                fields["amount"] = amount.toString()  // Keep as number for amounts
            }
        }
        
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
        
        if (coupon.cashbackAmount > 0) {
            // Preserve type information from typed cashback fields
            if (coupon.cashbackType == "percent") {
                fields["amount"] = "${coupon.cashbackAmount}%"  // ✅ Preserve % for percentage
            } else {
                fields["amount"] = coupon.cashbackAmount.toString()  // Keep as number for amounts
            }
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
    
    private suspend fun buildCouponFromFields(fields: Map<String, String>, uri: Uri): Coupon {
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
        
        // Parse cashback amount
        val cashbackAmount = fields["amount"]?.let { amountStr ->
            com.example.coupontracker.util.IndianCurrencyParser.parseAmount(amountStr) ?: 0.0
        } ?: 0.0
        
        // Determine cashback type and currency
        val (cashbackType, cashbackValueNum, cashbackCurrency) = if (cashbackAmount > 0) {
            val originalAmount = fields["amount"] ?: ""
            if (originalAmount.contains("%")) {
                Triple("percent", cashbackAmount, null)  // ✅ lowercase
            } else {
                Triple("amount", cashbackAmount, "INR")  // ✅ lowercase
            }
        } else {
            Triple("text", 0.0, null)  // ✅ lowercase
        }
        
        return Coupon(
            id = 0,
            storeName = fields["storeName"] ?: "Unknown Store",
            description = fields["description"] ?: "Batch processed coupon",
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = fields["code"]?.takeIf { it.isNotBlank() && it != "NEEDED" && it != "VOUCHER" },
            imageUri = persistUri(uri),
            category = fields["category"] ?: "Other",
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date(),
            // V2: Typed cashback fields
            cashbackType = cashbackType,
            cashbackValueNum = cashbackValueNum,
            cashbackCurrency = cashbackCurrency,
            offerText = fields["description"] ?: "Extracted via batch LEGACY processing"
        )
    }
    
    private suspend fun buildCouponFromLlmResult(couponInfo: com.example.coupontracker.util.CouponInfo, uri: Uri): Coupon {
        // Parse typed cashback from LLM result
        val cashbackAmount = couponInfo.cashbackAmount ?: 0.0
        val (cashbackType, cashbackValueNum, cashbackCurrency) = when {
            couponInfo.discountType == "PERCENTAGE" && cashbackAmount > 0 -> {
                Triple("percent", cashbackAmount, null)
            }
            cashbackAmount > 0 -> {
                Triple("amount", cashbackAmount, "INR")
            }
            else -> {
                Triple("text", 0.0, null)
            }
        }
        
        return Coupon(
            id = 0,
            storeName = couponInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store",
            description = couponInfo.description.takeIf { it.isNotBlank() } ?: "Extracted via LLM",
            expiryDate = couponInfo.expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = couponInfo.redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" },
            imageUri = persistUri(uri),
            category = couponInfo.category,
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date(),
            // V2: Typed cashback fields (CRITICAL - was missing!)
            cashbackType = cashbackType,
            cashbackValueNum = cashbackValueNum,
            cashbackCurrency = cashbackCurrency,
            offerText = couponInfo.description.takeIf { it.isNotBlank() } ?: "Extracted via batch LLM"
        )
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
}

/**
 * UI state for the batch scanner
 */
data class BatchScannerUiState(
    val selectedImages: List<Uri> = emptyList(),
    val processedCoupons: List<Coupon> = emptyList(),
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val processedCount: Int = 0,
    val error: String? = null
)

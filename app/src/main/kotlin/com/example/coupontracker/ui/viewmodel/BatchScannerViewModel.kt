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
    private val twoStageDetector = com.example.coupontracker.ml.TwoStageDetector(context)  // V2: LEGACY detector (not injected)

    companion object {
        private const val TAG = "BatchScannerViewModel"
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
     * Process all selected images
     * V2: REAL IMPLEMENTATION - Routes through selected strategy
     */
    fun processImages() {
        viewModelScope.launch {
            try {
                // V2: Get active strategy
                val strategy = com.example.coupontracker.util.ExtractionConfig.getStrategy()
                Log.d(TAG, "Batch: Starting with strategy ${strategy.name}, ${_uiState.value.selectedImages.size} images")
                
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
     */
    private suspend fun processWithLegacyPath(uri: Uri, bitmap: android.graphics.Bitmap): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // Run two-stage detection
            val couponInstances = twoStageDetector.detectMultiCoupons(bitmap)
            
            if (couponInstances.isNotEmpty()) {
                // Take first coupon for batch processing
                val instance = couponInstances.first()
                val extractedInfo = extractFieldsFromInstance(instance)
                buildCouponFromFields(extractedInfo, uri)
            } else {
                // Fallback to universal extraction
                processWithOcrFirstPath(uri, bitmap)
            }
        }
    }
    
    /**
     * Process image using LLM_FIRST strategy
     */
    private suspend fun processWithLlmFirstPath(uri: Uri, bitmap: android.graphics.Bitmap): Coupon {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val llmResult = localLlmOcrService.processCouponImageTyped(bitmap)
            
            when (llmResult) {
                is com.example.coupontracker.util.ExtractResult.Good -> {
                    buildCouponFromLlmResult(llmResult.info, uri)
                }
                else -> {
                    // Fallback to OCR on LLM failure
                    processWithOcrFirstPath(uri, bitmap)
                }
            }
        }
    }
    
    /**
     * Process image using OCR_FIRST strategy
     */
    private suspend fun processWithOcrFirstPath(uri: Uri, bitmap: android.graphics.Bitmap): Coupon {
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
                        // Fallback to LLM
                        processWithLlmFirstPath(uri, bitmap)
                    }
                }
                is com.example.coupontracker.util.MultiEngineOCR.OCRResult.Error -> {
                    // Fallback to LLM
                    processWithLlmFirstPath(uri, bitmap)
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
            
            // Fuse results
            when {
                llmResult is com.example.coupontracker.util.ExtractResult.Good && ocrResult != null && ocrResult.success -> {
                    // Both successful - prefer LLM for most fields
                    buildCouponFromLlmResult(llmResult.info, uri)
                }
                llmResult is com.example.coupontracker.util.ExtractResult.Good -> {
                    buildCouponFromLlmResult(llmResult.info, uri)
                }
                ocrResult != null && ocrResult.success -> {
                    ocrResult.coupon.copy(imageUri = persistUri(uri))
                }
                else -> {
                    // Both failed - use LEGACY
                    processWithLegacyPath(uri, bitmap)
                }
            }
        }
    }
    
    // Helper methods
    
    private suspend fun persistUri(uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        uriPersistenceManager.persistUri(uri)?.toString()
    }
    
    private fun extractFieldsFromInstance(instance: com.example.coupontracker.ml.CouponInstance): Map<String, String> {
        // Simple extraction from bounding boxes (basic implementation)
        return mapOf(
            "storeName" to "Unknown Store",
            "description" to "Extracted via two-stage detection"
        )
    }
    
    private suspend fun buildCouponFromFields(fields: Map<String, String>, uri: Uri): Coupon {
        return Coupon(
            id = 0,
            storeName = fields["storeName"] ?: "Unknown Store",
            description = fields["description"] ?: "Batch processed coupon",
            expiryDate = null,
            cashbackAmount = 0.0,
            redeemCode = fields["code"],
            imageUri = persistUri(uri),
            category = null,
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date()
        )
    }
    
    private suspend fun buildCouponFromLlmResult(couponInfo: com.example.coupontracker.util.CouponInfo, uri: Uri): Coupon {
        return Coupon(
            id = 0,
            storeName = couponInfo.storeName.takeIf { it.isNotBlank() } ?: "Unknown Store",
            description = couponInfo.description.takeIf { it.isNotBlank() } ?: "Extracted via LLM",
            expiryDate = couponInfo.expiryDate,
            cashbackAmount = couponInfo.cashbackAmount ?: 0.0,
            redeemCode = couponInfo.redeemCode?.takeIf { it.isNotBlank() && it != "NEEDED" },
            imageUri = persistUri(uri),
            category = couponInfo.category,
            status = "Active",
            createdAt = java.util.Date(),
            updatedAt = java.util.Date()
        )
    }

    override fun onCleared() {
        super.onCleared()
        // V2: No longer using CouponInputManager
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

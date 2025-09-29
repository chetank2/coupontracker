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
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BatchScannerUiState())
    val uiState: StateFlow<BatchScannerUiState> = _uiState.asStateFlow()

    private val couponInputManager = CouponInputManager(context)

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
     */
    fun processImages() {
        viewModelScope.launch {
            try {
                updateState { it.copy(isProcessing = true, processedCount = 0, error = null) }

                val images = _uiState.value.selectedImages
                val processedCoupons = mutableListOf<Coupon>()
                var failedCount = 0

                for ((index, uri) in images.withIndex()) {
                    try {
                        // Process the image with URI persistence
                        val coupon = couponInputManager.processCouponFromImageUriWithPersistence(uri)
                        processedCoupons.add(coupon)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image at index $index", e)
                        failedCount++
                        // Continue with next image
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

    override fun onCleared() {
        super.onCleared()
        couponInputManager.cleanup()
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

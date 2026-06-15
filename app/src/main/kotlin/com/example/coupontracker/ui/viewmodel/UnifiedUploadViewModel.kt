package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponInputManager
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the unified upload screen
 */
@HiltViewModel
class UnifiedUploadViewModel @Inject constructor(
    application: Application,
    private val couponRepository: CouponRepository,
    private val couponInputManager: CouponInputManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UnifiedUploadUiState())
    val uiState: StateFlow<UnifiedUploadUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "UnifiedUploadViewModel"
    }

    /**
     * Add a single image
     * @param uri Image URI to process
     */
    fun addSingleImage(uri: Uri) {
        processMedia(uri, "image")
    }

    /**
     * Add multiple images
     * @param uris List of image URIs to process
     */
    fun addMultipleImages(uris: List<Uri>) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    error = null
                )

                val sharedPrefs = getApplication<Application>().getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
                val gson = Gson()
                val uriStrings = uris.map { it.toString() }
                sharedPrefs.edit()
                    .putString("shared_image_uris", gson.toJson(uriStrings))
                    .apply()

                val pendingCount = uris.size
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    navigateToBatchScanner = true,
                    pendingBatchCount = pendingCount
                )
            } catch (e: Exception) {
                handleError(e, "Error processing multiple images")
            }
        }
    }

    /**
     * Add a PDF
     * @param uri PDF URI to process
     */
    fun addPdf(uri: Uri) {
        processMedia(uri, "pdf")
    }

    /**
     * Process a media file (image or PDF)
     * @param uri URI of the media to process
     * @param mediaType Type of media ("image" or "pdf")
     */
    private fun processMedia(uri: Uri, mediaType: String) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    error = null
                )

                val coupon = if (mediaType == "pdf") {
                    couponInputManager.processPdfUri(uri)
                } else {
                    couponInputManager.processCouponFromImageUriWithPersistence(uri)
                }

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processedCoupon = coupon
                )
            } catch (e: Exception) {
                handleError(e, "Error processing ${mediaType}")
            }
        }
    }

    /**
     * Handle errors in a consistent way
     * @param e The exception that occurred
     * @param message The error message prefix
     */
    private fun handleError(e: Exception, message: String) {
        Log.e(TAG, message, e)
        _uiState.value = _uiState.value.copy(
            isProcessing = false,
            error = "$message: ${e.message}"
        )
    }

    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = UnifiedUploadUiState()
    }

    override fun onCleared() {
        super.onCleared()
        couponInputManager.cleanup()
    }
}

/**
 * UI state for the unified upload screen
 */
data class UnifiedUploadUiState(
    val isProcessing: Boolean = false,
    val processedCoupon: Coupon? = null,
    val navigateToBatchScanner: Boolean = false,
    val error: String? = null,
    val pendingBatchCount: Int = 0
)

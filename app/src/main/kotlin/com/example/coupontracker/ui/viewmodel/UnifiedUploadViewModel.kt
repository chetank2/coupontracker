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
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UnifiedUploadUiState())
    val uiState: StateFlow<UnifiedUploadUiState> = _uiState.asStateFlow()

    private val couponInputManager = CouponInputManager(context)

    companion object {
        private const val TAG = "UnifiedUploadViewModel"
    }

    /**
     * Add a single image
     * @param uri Image URI to process
     */
    fun addSingleImage(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    error = null
                )

                val coupon = couponInputManager.processCouponFromImageUri(uri)

                // Set the image URI in the coupon object
                val couponWithImage = coupon.copy(imageUri = uri.toString())

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processedCoupon = couponWithImage
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Error processing image: ${e.message}"
                )
            }
        }
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

                // Store the URIs for batch processing
                val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
                val gson = Gson()
                val uriStrings = uris.map { it.toString() }
                val uriJson = gson.toJson(uriStrings)
                sharedPrefs.edit().putString("shared_image_uris", uriJson).apply()

                // Navigate to batch scanner (will be handled in the UI)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    navigateToBatchScanner = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing multiple images", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Error processing images: ${e.message}"
                )
            }
        }
    }

    /**
     * Add a PDF
     * @param uri PDF URI to process
     */
    fun addPdf(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(
                    isProcessing = true,
                    error = null
                )

                val coupon = couponInputManager.processPdfUri(uri)

                // Set the image URI in the coupon object
                val couponWithImage = coupon.copy(imageUri = uri.toString())

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    processedCoupon = couponWithImage
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing PDF", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    error = "Error processing PDF: ${e.message}"
                )
            }
        }
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
    val error: String? = null
)

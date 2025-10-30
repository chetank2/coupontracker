package com.example.coupontracker.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponInputManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

/**
 * ViewModel for manual entry of coupon details
 */
@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    application: Application,
    private val couponRepository: CouponRepository,
    private val couponInputManager: CouponInputManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ManualEntryUiState())
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()

    companion object {
        private const val TAG = "ManualEntryViewModel"
    }

    init {
        // Check for shared URL on initialization
        checkForSharedUrl()
    }

    /**
     * Check for shared URL from intent and auto-process it
     */
    private fun checkForSharedUrl() {
        val sharedPrefs = getApplication<Application>().getSharedPreferences("coupon_tracker_prefs", android.content.Context.MODE_PRIVATE)
        val sharedUrl = sharedPrefs.getString("shared_url", null)
        
        if (sharedUrl != null) {
            // Clear the shared URL to prevent reuse
            sharedPrefs.edit().remove("shared_url").apply()
            
            // Set the URL and auto-process it
            _uiState.value = _uiState.value.copy(url = sharedUrl)
            processUrl()
        }
    }

    /**
     * Set the URL to process
     * @param url The URL to process
     */
    fun setUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    /**
     * Process the URL to extract coupon information
     */
    fun processUrl() {
        val url = _uiState.value.url ?: return

        if (url.isBlank()) {
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessingUrl = true, error = null)

                val coupon = couponInputManager.processCouponFromUrl(url)

                _uiState.value = _uiState.value.copy(
                    isProcessingUrl = false,
                    urlData = UrlData(
                        storeName = coupon.storeName,
                        description = coupon.description,
                        code = coupon.redeemCode
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing URL", e)
                _uiState.value = _uiState.value.copy(
                    isProcessingUrl = false,
                    error = "Error processing URL: ${e.message}"
                )
            }
        }
    }

    /**
     * Save the coupon with the provided details
     */
    fun saveCoupon(
        storeName: String,
        description: String,
        code: String?,
        expiryDate: Date?,
        category: String?
    ) {
        if (storeName.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Store name is required")
            return
        }

        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSaving = true, error = null)

                val coupon = Coupon(
                    id = 0,
                    storeName = storeName,
                    description = description,
                    expiryDate = expiryDate,
                    redeemCode = code.takeIf { !it.isNullOrBlank() },
                    imageUri = null,
                    category = category.takeIf { !it.isNullOrBlank() },
                    status = "Active",
                    createdAt = Date(),
                    updatedAt = Date()
                )

                couponRepository.insertCoupon(coupon)

                _uiState.value = _uiState.value.copy(isSaving = false, isSaved = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving coupon", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Error saving coupon: ${e.message}"
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        couponInputManager.cleanup()
    }
}

/**
 * UI state for manual entry
 */
data class ManualEntryUiState(
    val url: String? = null,
    val isProcessingUrl: Boolean = false,
    val urlData: UrlData? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

/**
 * Data extracted from a URL
 */
data class UrlData(
    val storeName: String,
    val description: String,
    val code: String?
)

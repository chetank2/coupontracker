package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
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
import java.util.Date
import javax.inject.Inject

/**
 * ViewModel for manual entry of coupon details
 */
@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(ManualEntryUiState())
    val uiState: StateFlow<ManualEntryUiState> = _uiState.asStateFlow()
    
    private val couponInputManager = CouponInputManager(context)
    
    companion object {
        private const val TAG = "ManualEntryViewModel"
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
                        amount = coupon.cashbackAmount,
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
        amount: Double,
        code: String?,
        expiryDate: Date,
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
                    cashbackAmount = amount,
                    expiryDate = expiryDate,
                    redeemCode = code.takeIf { !it.isNullOrBlank() },
                    createdDate = Date(),
                    category = category.takeIf { !it.isNullOrBlank() },
                    status = "Active"
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
    val amount: Double?,
    val code: String?
)

package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponInputManager
import com.google.mlkit.vision.barcode.common.Barcode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for QR code scanning
 */
@HiltViewModel
class QRScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(QRScannerUiState())
    val uiState: StateFlow<QRScannerUiState> = _uiState.asStateFlow()
    
    private val couponInputManager = CouponInputManager(context)
    
    companion object {
        private const val TAG = "QRScannerViewModel"
    }
    
    /**
     * Handle barcode detection
     * @param barcode The detected barcode
     */
    fun onBarcodeDetected(barcode: Barcode) {
        if (_uiState.value.isProcessing) {
            return
        }
        
        _uiState.value = _uiState.value.copy(
            scannedBarcode = barcode,
            isProcessing = true
        )
    }
    
    /**
     * Process a URL from a barcode
     * @param url The URL to process
     */
    fun processUrl(url: String) {
        viewModelScope.launch {
            try {
                val coupon = couponInputManager.processCouponFromUrl(url)
                _uiState.value = _uiState.value.copy(
                    coupon = coupon,
                    isProcessing = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing URL", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error processing URL: ${e.message}",
                    isProcessing = false
                )
            }
        }
    }
    
    /**
     * Process text from a barcode
     * @param text The text to process
     */
    fun processText(text: String) {
        viewModelScope.launch {
            try {
                val coupon = couponInputManager.processCouponFromText(text)
                _uiState.value = _uiState.value.copy(
                    coupon = coupon,
                    isProcessing = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing text", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error processing text: ${e.message}",
                    isProcessing = false
                )
            }
        }
    }
    
    /**
     * Save the processed coupon
     */
    fun saveCoupon() {
        val coupon = _uiState.value.coupon ?: return
        
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isSaving = true)
                
                couponRepository.insertCoupon(coupon)
                
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isSaved = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving coupon", e)
                _uiState.value = _uiState.value.copy(
                    error = "Error saving coupon: ${e.message}",
                    isSaving = false
                )
            }
        }
    }
    
    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = QRScannerUiState()
    }
    
    override fun onCleared() {
        super.onCleared()
        couponInputManager.cleanup()
    }
}

/**
 * UI state for QR code scanning
 */
data class QRScannerUiState(
    val scannedBarcode: Barcode? = null,
    val coupon: Coupon? = null,
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.MultiEngineOCR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()
    
    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context)
    
    companion object {
        private const val TAG = "ScannerViewModel"
    }
    
    init {
        // Assume network is available by default
        multiEngineOCR.setNetworkAvailability(true)
    }
    
    /**
     * Scan and process the image to extract coupon information
     */
    fun scanImage(imageUri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                
                // Process the image with OCR
                when (val result = multiEngineOCR.processImage(imageUri)) {
                    is MultiEngineOCR.OCRResult.Success -> {
                        // Extract the recognized text and convert to coupon data
                        val extractedInfo = result.extractedInfo
                        Log.d(TAG, "Extracted info from image: $extractedInfo")
                        
                        if (extractedInfo.isEmpty()) {
                            _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                        } else {
                            // Create coupon from extracted info
                            val coupon = createCouponFromExtractedInfo(extractedInfo)
                            _uiState.value = ScannerUiState.Success(coupon)
                        }
                    }
                    is MultiEngineOCR.OCRResult.Error -> {
                        _uiState.value = ScannerUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }
    
    /**
     * Process a captured bitmap to extract coupon information
     */
    fun processCapturedImage(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                
                // Process the bitmap with OCR
                when (val result = multiEngineOCR.processImage(bitmap)) {
                    is MultiEngineOCR.OCRResult.Success -> {
                        // Extract the recognized text and convert to coupon data
                        val extractedInfo = result.extractedInfo
                        Log.d(TAG, "Extracted info from bitmap: $extractedInfo")
                        
                        if (extractedInfo.isEmpty()) {
                            _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                        } else {
                            // Create coupon from extracted info
                            val coupon = createCouponFromExtractedInfo(extractedInfo)
                            _uiState.value = ScannerUiState.Success(coupon)
                        }
                    }
                    is MultiEngineOCR.OCRResult.Error -> {
                        _uiState.value = ScannerUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }
    
    /**
     * Create a Coupon object from the extracted information
     */
    private fun createCouponFromExtractedInfo(extractedInfo: Map<String, String>): Coupon {
        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])
        
        // Parse amount to double
        val amount = extractedInfo["amount"]?.let {
            // Extract numeric value from the amount string (remove rupee symbol and other non-numeric characters)
            val numericValue = Regex("\\d+(\\.\\d+)?").find(it)?.value
            numericValue?.toDoubleOrNull() ?: 0.0
        } ?: 0.0
        
        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: "No description",
            expiryDate = expiryDate ?: Date(), // Use current date if no expiry provided
            cashbackAmount = amount,
            redeemCode = extractedInfo["code"],
            imageUri = null,
            category = "Other",
            rating = null,
            status = "ACTIVE",
            createdAt = Date(),
            updatedAt = Date()
        )
    }
    
    /**
     * Parse expiry date string to Date
     */
    private fun parseExpiryDate(dateString: String?): Date? {
        if (dateString.isNullOrBlank()) return null
        
        val dateFormats = arrayOf(
            "dd/MM/yyyy", "MM/dd/yyyy", "yyyy/MM/dd",
            "dd-MM-yyyy", "MM-dd-yyyy", "yyyy-MM-dd"
        )
        
        for (format in dateFormats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                return sdf.parse(dateString.trim())
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        return null
    }
    
    /**
     * Save the scanned coupon to the repository
     */
    fun saveCoupon(coupon: Coupon) {
        viewModelScope.launch {
            try {
                couponRepository.insertCoupon(coupon)
                _uiState.value = ScannerUiState.Saved(coupon)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving coupon", e)
                _uiState.value = ScannerUiState.Error("Error saving coupon: ${e.message}")
            }
        }
    }
    
    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = ScannerUiState.Initial
    }
}

/**
 * Represents the UI state for the scanner
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    object Scanning : ScannerUiState()
    data class Success(val coupon: Coupon) : ScannerUiState()
    data class Saved(val coupon: Coupon) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
} 
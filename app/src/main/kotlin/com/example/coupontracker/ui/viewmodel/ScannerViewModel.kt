package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.ml.TwoStageDetector
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.FieldType
import com.example.coupontracker.util.MultiEngineOCR
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Initial)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context)
    private val twoStageDetector: TwoStageDetector = TwoStageDetector(context)
    private val manualCouponOverrides = mutableMapOf<String, CouponInstance>()

    companion object {
        private const val TAG = "ScannerViewModel"
    }

    init {
        // Assume network is available by default
        multiEngineOCR.setNetworkAvailability(true)
        
        // Log detector initialization
        val modelInfo = twoStageDetector.getModelInfo()
        Log.d(TAG, "TwoStageDetector initialized: $modelInfo")
    }

    /**
     * Enhanced scan method that uses two-stage detection for multi-coupon support
     */
    fun scanImage(imageUri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Starting enhanced multi-coupon scan for: $imageUri")

                // Load bitmap from URI
                val bitmap = loadBitmapFromUri(imageUri) ?: run {
                    _uiState.value = ScannerUiState.Error("Could not load image")
                    return@launch
                }

                // Run two-stage detection
                val couponInstances = withContext(Dispatchers.IO) {
                    twoStageDetector.detectMultiCoupons(bitmap)
                }

                Log.d(TAG, "Two-stage detection completed: ${couponInstances.size} coupons detected")

                when {
                    couponInstances.isEmpty() -> {
                        // Fallback to traditional OCR if no coupons detected
                        Log.d(TAG, "No coupons detected, falling back to traditional OCR")
                        fallbackToTraditionalOCR(imageUri)
                    }
                    couponInstances.size == 1 -> {
                        // Single coupon detected - process directly
                        val couponInstance = couponInstances.first()
                        processSingleCoupon(couponInstance, imageUri.toString())
                    }
                    else -> {
                        // Multiple coupons detected - show selection interface
                        _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri.toString())
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in enhanced scanning", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    /**
     * Process a captured bitmap to extract coupon information
     */
    fun processCapturedImage(bitmap: Bitmap, imageUri: Uri? = null) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Processing captured bitmap with two-stage detection")

                // Run two-stage detection
                val couponInstances = withContext(Dispatchers.IO) {
                    twoStageDetector.detectMultiCoupons(bitmap)
                }

                Log.d(TAG, "Two-stage detection on bitmap completed: ${couponInstances.size} coupons detected")

                when {
                    couponInstances.isEmpty() -> {
                        // Fallback to traditional OCR
                        Log.d(TAG, "No coupons detected in bitmap, falling back to traditional OCR")
                        fallbackToTraditionalOCRBitmap(bitmap, imageUri)
                    }
                    couponInstances.size == 1 -> {
                        // Single coupon detected
                        val couponInstance = couponInstances.first()
                        processSingleCoupon(couponInstance, imageUri?.toString())
                    }
                    else -> {
                        // Multiple coupons detected
                        _uiState.value = ScannerUiState.MultiCouponDetected(couponInstances, bitmap, imageUri?.toString())
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    /**
     * Process a single detected coupon instance
     */
    private suspend fun processSingleCoupon(couponInstance: CouponInstance, imageUri: String?) {
        try {
            Log.d(TAG, "Processing single coupon with ${couponInstance.fields.size} detected fields")

            // Extract text from detected fields using OCR
            val extractedInfo = extractTextFromFields(couponInstance)
            
            // Create coupon from extracted information
            val coupon = createCouponFromInstance(couponInstance, extractedInfo, imageUri)
            
            _uiState.value = ScannerUiState.Success(coupon)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing single coupon", e)
            _uiState.value = ScannerUiState.Error("Error processing coupon: ${e.message}")
        }
    }

    /**
     * Handle selection of a specific coupon from multiple detected coupons
     */
    fun selectCoupon(selectedInstance: CouponInstance, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                Log.d(TAG, "Processing selected coupon: ${selectedInstance.id}")

                // Process the selected coupon
                val instance = manualCouponOverrides[selectedInstance.id] ?: selectedInstance
                processSingleCoupon(instance, originalImageUri)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing selected coupon", e)
                _uiState.value = ScannerUiState.Error("Error processing selected coupon: ${e.message}")
            }
        }
    }

    /**
     * Process all detected coupons and save them
     */
    fun processAllCoupons(couponInstances: List<CouponInstance>, originalImageUri: String?) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning
                val adjustedInstances = getManualAdjustedInstances(couponInstances)
                Log.d(TAG, "Processing all ${adjustedInstances.size} detected coupons")

                val savedCoupons = mutableListOf<Coupon>()

                for ((index, instance) in adjustedInstances.withIndex()) {
                    try {
                        val extractedInfo = extractTextFromFields(instance)
                        val coupon = createCouponFromInstance(instance, extractedInfo, originalImageUri)
                        
                        // Save coupon to repository
                        couponRepository.insertCoupon(coupon)
                        savedCoupons.add(coupon)
                        
                        Log.d(TAG, "Saved coupon ${index + 1}/${couponInstances.size}: ${coupon.redeemCode}")
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing coupon $index", e)
                        // Continue with other coupons
                    }
                }

                if (savedCoupons.isNotEmpty()) {
                    _uiState.value = ScannerUiState.AllCouponsSaved(savedCoupons)
                } else {
                    _uiState.value = ScannerUiState.Error("Failed to save any coupons")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing all coupons", e)
                _uiState.value = ScannerUiState.Error("Error processing coupons: ${e.message}")
            }
        }
    }

    /**
     * Extract text from detected fields using OCR
     */
    private suspend fun extractTextFromFields(couponInstance: CouponInstance): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val extractedInfo = mutableMapOf<String, String>()

            try {
                // Use the cropped coupon bitmap for OCR
                when (val result = multiEngineOCR.processImage(couponInstance.cropBitmap)) {
                    is MultiEngineOCR.OCRResult.Success -> {
                        // Merge OCR results with field detection results
                        extractedInfo.putAll(result.extractedInfo)
                        
                        // Map detected fields to their types
                        couponInstance.fields.forEach { field ->
                            val fieldKey = when (field.fieldType) {
                                FieldType.CODE_REGION -> "code"
                                FieldType.BENEFIT_REGION -> "description"
                                FieldType.EXPIRY_REGION -> "expiryDate"
                                FieldType.APP_REGION -> "storeName"
                                FieldType.TERMS_REGION -> "terms"
                            }
                            
                            // If we have text from the field detection, use it
                            field.text?.let { text ->
                                extractedInfo[fieldKey] = text
                            }
                        }
                    }
                    is MultiEngineOCR.OCRResult.Error -> {
                        Log.w(TAG, "OCR failed for coupon instance: ${result.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting text from fields", e)
            }

            extractedInfo
        }
    }

    /**
     * Create a Coupon object from a detected coupon instance
     */
    private fun createCouponFromInstance(
        couponInstance: CouponInstance, 
        extractedInfo: Map<String, String>, 
        imageUri: String?
    ): Coupon {
        // Parse expiry date string to Date if available
        val expiryDate = parseExpiryDate(extractedInfo["expiryDate"])

        // Parse amount to double
        val amount = extractedInfo["amount"]?.let {
            val numericValue = Regex("\\d+(\\.\\d+)?").find(it)?.value
            numericValue?.toDoubleOrNull() ?: 0.0
        } ?: 0.0

        // Note: Quality determination could be used for analytics or sorting

        return Coupon(
            id = 0, // Auto-generated by Room
            storeName = extractedInfo["storeName"] ?: extractedInfo["app"] ?: "Unknown Store",
            description = extractedInfo["description"] ?: extractedInfo["benefit"] ?: "Multi-coupon detected",
            expiryDate = expiryDate ?: Date(),
            cashbackAmount = amount,
            redeemCode = extractedInfo["code"] ?: generateFallbackCode(),
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
            rating = null,
            status = when (couponInstance.status) {
                com.example.coupontracker.ml.CouponStatus.COMPLETE -> "ACTIVE"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_TOP -> "PARTIAL"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_BOTTOM -> "PARTIAL"
            },
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Fallback to traditional OCR when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCR(imageUri: Uri) {
        try {
            Log.d(TAG, "Using traditional OCR fallback")
            
            when (val result = multiEngineOCR.processImage(imageUri)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, imageUri.toString())
                        _uiState.value = ScannerUiState.Success(coupon)
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
        }
    }

    /**
     * Fallback to traditional OCR for bitmap when no coupons are detected
     */
    private suspend fun fallbackToTraditionalOCRBitmap(bitmap: Bitmap, imageUri: Uri?) {
        try {
            Log.d(TAG, "Using traditional OCR fallback for bitmap")
            
            when (val result = multiEngineOCR.processImage(bitmap)) {
                is MultiEngineOCR.OCRResult.Success -> {
                    val extractedInfo = result.extractedInfo
                    Log.d(TAG, "Traditional OCR extracted from bitmap: $extractedInfo")

                    if (extractedInfo.isEmpty()) {
                        _uiState.value = ScannerUiState.Error("Could not extract any coupon information from the image")
                    } else {
                        val coupon = createCouponFromExtractedInfo(extractedInfo, imageUri?.toString())
                        _uiState.value = ScannerUiState.Success(coupon)
                    }
                }
                is MultiEngineOCR.OCRResult.Error -> {
                    _uiState.value = ScannerUiState.Error(result.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in traditional OCR fallback for bitmap", e)
            _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
        }
    }

    /**
     * Load bitmap from URI
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(inputStream)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from URI", e)
                null
            }
        }
    }

    /**
     * Legacy method - Create a Coupon object from the extracted information
     */
    private fun createCouponFromExtractedInfo(extractedInfo: Map<String, String>, imageUri: String? = null): Coupon {
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
            redeemCode = extractedInfo["code"] ?: generateFallbackCode(),
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
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
            "dd-MM-yyyy", "MM-dd-yyyy", "yyyy-MM-dd",
            "dd MMM yyyy", "MMM dd, yyyy", "yyyy-MM-dd'T'HH:mm:ss"
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
     * Generate fallback coupon code
     */
    private fun generateFallbackCode(): String {
        return "COUPON_${System.currentTimeMillis().toString().takeLast(6)}"
    }

    /**
     * Determine category from extracted information
     */
    private fun determineCategory(extractedInfo: Map<String, String>): String {
        val text = extractedInfo.values.joinToString(" ").lowercase()
        
        return when {
            text.contains("food") || text.contains("restaurant") || text.contains("zomato") || text.contains("swiggy") -> "Food"
            text.contains("fashion") || text.contains("clothing") || text.contains("myntra") -> "Fashion"
            text.contains("grocery") || text.contains("bigbasket") || text.contains("grofers") -> "Grocery"
            text.contains("travel") || text.contains("booking") || text.contains("hotel") -> "Travel"
            text.contains("electronics") || text.contains("amazon") || text.contains("flipkart") -> "Electronics"
            else -> "Other"
        }
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

    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        try {
            twoStageDetector.cleanup()
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TwoStageDetector", e)
        }
    }

    fun resetManualOverrides() {
        manualCouponOverrides.clear()
    }

    fun updateManualCouponInstance(instance: CouponInstance) {
        manualCouponOverrides[instance.id] = instance
    }

    fun removeManualCoupons(ids: Set<String>) {
        ids.forEach { manualCouponOverrides.remove(it) }
    }

    fun getManualAdjustedInstances(instances: List<CouponInstance>): List<CouponInstance> {
        val updated = instances.map { manualCouponOverrides[it.id] ?: it }
        val extras = manualCouponOverrides.values.filter { manual ->
            instances.none { it.id == manual.id }
        }
        return updated + extras
    }
}

/**
 * Enhanced UI state for the scanner with multi-coupon support
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    object Scanning : ScannerUiState()
    data class Success(val coupon: Coupon) : ScannerUiState()
    data class Saved(val coupon: Coupon) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
    
    // New states for multi-coupon support
    data class MultiCouponDetected(
        val couponInstances: List<CouponInstance>, 
        val originalBitmap: Bitmap, 
        val imageUri: String?
    ) : ScannerUiState()
    
    data class AllCouponsSaved(val coupons: List<Coupon>) : ScannerUiState()
}
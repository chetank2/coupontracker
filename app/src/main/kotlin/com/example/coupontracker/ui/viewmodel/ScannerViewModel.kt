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
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.IntegratedCouponPipeline
import com.example.coupontracker.util.OCRProcessingException
import com.example.coupontracker.util.toUtcIsoString
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
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

    private val integratedPipeline: IntegratedCouponPipeline = IntegratedCouponPipeline(context)
    private var latestCouponInfo: CouponInfo? = null
    private var pendingImageUri: String? = null

    companion object {
        private const val TAG = "ScannerViewModel"
    }

    // No initialization needed for MultiEngineOCR

    /**
     * Scan and process the image to extract coupon information
     */
    fun scanImage(imageUri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = ScannerUiState.Scanning

                // Convert URI to bitmap for processing
                val bitmap = loadBitmapFromUri(imageUri)
                if (bitmap == null) {
                    _uiState.value = ScannerUiState.Error("Could not load image from URI")
                    return@launch
                }

                // Process with integrated pipeline
                val result = integratedPipeline.processCouponImage(bitmap)
                onPipelineSuccess(result, imageUri.toString())

            } catch (e: OCRProcessingException) {
                Log.w(TAG, "OCRProcessingException while scanning image", e)
                _uiState.value = ScannerUiState.Error(
                    "Couldn't read this coupon. Try retaking the photo or use Manual Entry."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning image", e)
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

                // Process with integrated pipeline
                val result = integratedPipeline.processCouponImage(bitmap)
                onPipelineSuccess(result, imageUri?.toString())

            } catch (e: OCRProcessingException) {
                Log.w(TAG, "OCRProcessingException while processing captured image", e)
                _uiState.value = ScannerUiState.Error(
                    "Couldn't read this coupon. Try retaking the photo or use Manual Entry."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing captured image", e)
                _uiState.value = ScannerUiState.Error("Error processing image: ${e.message}")
            }
        }
    }

    private fun onPipelineSuccess(result: IntegratedCouponPipeline.PipelineResult, imageUri: String?) {
        Log.d(TAG, "Pipeline processing successful: ${result.coupon.storeName}")

        if (imageUri != null && imageUri != pendingImageUri) {
            deleteTempImage(pendingImageUri)
            pendingImageUri = imageUri
        }

        // Create CouponInfo from the pipeline result
        val couponInfo = CouponInfo(
            storeName = result.coupon.storeName,
            description = result.coupon.description,
            redeemCode = result.coupon.redeemCode,
            cashbackAmount = result.coupon.cashbackAmount,
            benefitValue = result.coupon.benefitValue,
            expiryDate = result.coupon.expiryDate,
            category = result.coupon.category,
            rating = result.coupon.rating,
            status = result.coupon.status,
            minimumPurchase = result.coupon.minimumPurchase,
            maximumDiscount = result.coupon.maximumDiscount,
            paymentMethod = result.coupon.paymentMethod,
            platformType = result.coupon.platformType,
            fieldConfidences = result.fieldConfidences,
            confidence = result.coupon.confidence
        )

        latestCouponInfo = couponInfo

        _uiState.value = ScannerUiState.Success(
            coupon = result.coupon.copy(imageUri = imageUri),
            couponInfo = couponInfo,
            fieldConfidences = result.fieldConfidences,
            rawText = result.rawText,
            imageUri = imageUri
        )
    }

    /**
     * Load bitmap from URI
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                android.graphics.BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI", e)
            null
        }
    }

    /**
     * Create a Coupon object from the extracted information
     */
    private fun createCouponFromInfo(info: CouponInfo, imageUri: String?): Coupon {
        val amount = info.benefitValue ?: info.cashbackAmount ?: 0.0
        val expiryDate = info.expiryDate ?: Date()
        return Coupon(
            id = 0,
            storeName = info.storeName.ifBlank { "Unknown Store" },
            description = info.description.ifBlank { "Detected coupon" },
            expiryDate = expiryDate,
            cashbackAmount = amount,
            redeemCode = info.redeemCode,
            imageUri = imageUri,
            category = info.category,
            rating = info.rating,
            status = info.status ?: "ACTIVE",
            minimumPurchase = info.minimumPurchase,
            maximumDiscount = info.maximumDiscount,
            paymentMethod = info.paymentMethod,
            platformType = info.platformType,
            createdAt = Date(),
            updatedAt = Date(),
            code = info.redeemCode?.uppercase(Locale.getDefault()),
            benefitType = info.benefitType,
            benefitValue = amount.takeIf { it > 0 },
            currency = info.currency,
            expiryIso = info.expiryIso ?: expiryDate.toUtcIsoString(),
            app = info.app,
            confidence = if (info.confidence > 0f) info.confidence else 0.7f
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
                pendingImageUri = null
                _uiState.value = ScannerUiState.Saved(coupon, coupon.imageUri)
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
        deleteTempImage(pendingImageUri)
        pendingImageUri = null
        latestCouponInfo = null
        _uiState.value = ScannerUiState.Initial
    }

    fun saveEditedCoupon(
        storeName: String,
        description: String,
        amountInput: String,
        codeInput: String,
        expiryInput: String,
        category: String,
        imageUri: String?
    ) {
        viewModelScope.launch {
            try {
                val amount = amountInput.toDoubleOrNull() ?: 0.0
                val expiryDate = parseExpiryDateString(expiryInput) ?: Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, 30)
                }.time

                val base = latestCouponInfo ?: CouponInfo()
                val updatedFieldConf = base.fieldConfidences.toMutableMap().apply {
                    put("storeName", 1f)
                    put("description", 1f)
                    put("amount", if (amount > 0) 1f else 0f)
                    put("code", if (codeInput.isNotBlank()) 1f else 0f)
                    put("expiryDate", 1f)
                }

                val updatedInfo = base.copy(
                    storeName = storeName,
                    description = description,
                    cashbackAmount = if (amount > 0) amount else null,
                    benefitValue = amount.takeIf { it > 0 },
                    benefitType = when {
                        amount > 0 -> "cashback"
                        base.benefitType != null -> base.benefitType
                        else -> null
                    },
                    currency = if (amount > 0) "INR" else base.currency,
                    redeemCode = codeInput.ifBlank { null },
                    expiryDate = expiryDate,
                    expiryIso = expiryDate.toUtcIsoString(),
                    category = category.takeIf { it.isNotBlank() } ?: base.category,
                    confidence = if (base.confidence > 0f) base.confidence else 0.7f,
                    fieldConfidences = updatedFieldConf
                )

                latestCouponInfo = updatedInfo
                val coupon = createCouponFromInfo(updatedInfo, imageUri)
                saveCoupon(coupon)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving edited coupon", e)
                _uiState.value = ScannerUiState.Error("Failed to save coupon: ${e.message}")
            }
        }
    }

    private fun parseExpiryDateString(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        val formats = listOf("MM/dd/yyyy", "dd/MM/yyyy", "yyyy-MM-dd")
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                sdf.isLenient = false
                return sdf.parse(value)
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun deleteTempImage(uriString: String?) {
        if (uriString.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme != "file") return@runCatching
            val file = uri.path?.let { File(it) } ?: return@runCatching
            val baseDir = getApplication<Application>().getExternalFilesDir(null)
            if (baseDir != null && file.absolutePath.startsWith(baseDir.absolutePath) && file.exists()) {
                file.delete()
            }
        }
    }

    /**
     * Clean up resources when the ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        integratedPipeline.close()
    }
}

/**
 * Represents the UI state for the scanner
 */
sealed class ScannerUiState {
    object Initial : ScannerUiState()
    object Scanning : ScannerUiState()
    data class Success(
        val coupon: Coupon,
        val couponInfo: CouponInfo,
        val fieldConfidences: Map<String, Float>,
        val rawText: String,
        val imageUri: String?
    ) : ScannerUiState()
    data class Saved(val coupon: Coupon, val imageUri: String?) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
}

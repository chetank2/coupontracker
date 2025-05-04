package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponInfo
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
 * ViewModel for the coupon form screen
 */
@HiltViewModel
class CouponFormViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val couponRepository: CouponRepository,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CouponFormUiState())
    val uiState: StateFlow<CouponFormUiState> = _uiState.asStateFlow()

    private val couponInputManager = CouponInputManager(context)

    companion object {
        private const val TAG = "CouponFormViewModel"
    }

    init {
        // Get image URI from saved state
        savedStateHandle.get<String>("imageUri")?.let { uriString ->
            if (uriString.isNotEmpty()) {
                val uri = Uri.parse(uriString)
                processImageUri(uri)
            }
        }
    }

    /**
     * Process an image URI to extract coupon information
     * @param uri URI of the image to process
     */
    fun processImageUri(uri: Uri) {
        viewModelScope.launch {
            try {
                updateState { it.copy(isProcessing = true, error = null) }

                // Process the image
                val coupon = couponInputManager.processCouponFromImageUri(uri)

                // Convert to coupon info
                val couponInfo = mapCouponToCouponInfo(coupon)

                updateState { it.copy(isProcessing = false, couponInfo = couponInfo) }
            } catch (e: Exception) {
                handleError(e, "Error processing image")
            }
        }
    }

    /**
     * Save the coupon
     * @param storeName Store name
     * @param description Description
     * @param amount Amount
     * @param code Redeem code
     * @param expiryDate Expiry date
     * @param category Category
     * @param imageUri Image URI
     */
    fun saveCoupon(
        storeName: String,
        description: String,
        amount: Double,
        code: String,
        expiryDate: Date,
        category: String,
        imageUri: String?
    ) {
        viewModelScope.launch {
            try {
                // Validate input
                if (storeName.isBlank()) {
                    updateState { it.copy(error = "Store name is required") }
                    return@launch
                }

                updateState { it.copy(isSaving = true, error = null) }

                // Create and save coupon object
                val coupon = createCoupon(
                    storeName, description, amount, code,
                    expiryDate, category, imageUri
                )
                couponRepository.insertCoupon(coupon)

                updateState { it.copy(isSaving = false, isSaved = true) }
            } catch (e: Exception) {
                handleError(e, "Error saving coupon")
            }
        }
    }

    /**
     * Create a coupon object from the provided parameters
     */
    private fun createCoupon(
        storeName: String,
        description: String,
        amount: Double,
        code: String,
        expiryDate: Date,
        category: String,
        imageUri: String?
    ): Coupon {
        return Coupon(
            storeName = storeName,
            description = description,
            cashbackAmount = amount,
            redeemCode = code.takeIf { it.isNotBlank() },
            expiryDate = expiryDate,
            category = category.takeIf { it.isNotBlank() },
            imageUri = imageUri,
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Map a Coupon object to CouponInfo
     */
    private fun mapCouponToCouponInfo(coupon: Coupon): CouponInfo {
        return CouponInfo(
            storeName = coupon.storeName,
            description = coupon.description,
            cashbackAmount = coupon.cashbackAmount,
            redeemCode = coupon.redeemCode,
            expiryDate = coupon.expiryDate
        )
    }

    /**
     * Update the UI state
     */
    private fun updateState(update: (CouponFormUiState) -> CouponFormUiState) {
        _uiState.value = update(_uiState.value)
    }

    /**
     * Handle errors in a consistent way
     */
    private fun handleError(e: Exception, message: String) {
        Log.e(TAG, message, e)
        updateState {
            it.copy(
                isProcessing = false,
                isSaving = false,
                error = "$message: ${e.message}"
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        couponInputManager.cleanup()
    }
}

/**
 * UI state for the coupon form
 */
data class CouponFormUiState(
    val isProcessing: Boolean = false,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val couponInfo: CouponInfo? = null,
    val error: String? = null
)

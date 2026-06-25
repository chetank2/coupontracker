package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.extraction.rules.CouponInfo
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.CouponInputManager
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.worker.VerifyCouponWorker
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val couponRepository: CouponRepository,
    private val couponInputManager: CouponInputManager,
    savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CouponFormUiState())
    val uiState: StateFlow<CouponFormUiState> = _uiState.asStateFlow()

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
     * Track the currently running image processing request to avoid duplicate work.
     */
    private var currentlyProcessingImageUri: String? = null
    private var editCouponId: Long? = null

    /**
     * Process an image URI to extract coupon information
     * @param uri URI of the image to process
     * @param force When true, bypasses duplicate detection and re-processes the URI
     */
    fun processImageUri(uri: Uri, force: Boolean = false) {
        val uriString = uri.toString()

        if (!force && uriString == currentlyProcessingImageUri) {
            Log.d(TAG, "Skipping duplicate processing request for URI already in progress: $uriString")
            return
        }

        currentlyProcessingImageUri = uriString

        updateState {
            it.copy(
                isProcessing = true,
                error = null,
                saveResult = null
            )
        }

        viewModelScope.launch {
            try {
                val coupon = couponInputManager.processCouponFromImageUriWithPersistence(uri)

                val couponInfo = mapCouponToCouponInfo(coupon)
                updateState {
                    it.copy(
                        isProcessing = false,
                        couponInfo = couponInfo,
                        persistedImageUri = coupon.imageUri,
                        extractedCoupon = coupon
                    )
                }
            } catch (e: Exception) {
                handleError(e, "Error processing image")
            } finally {
                if (currentlyProcessingImageUri == uriString) {
                    currentlyProcessingImageUri = null
                }
            }
        }
    }

    fun loadPreviewCoupon(coupon: Coupon) {
        updateState {
            it.copy(
                isProcessing = false,
                error = null,
                saveResult = null,
                couponInfo = mapCouponToCouponInfo(coupon),
                persistedImageUri = coupon.imageUri,
                extractedCoupon = coupon
            )
        }
    }

    fun loadCouponForEdit(couponId: Long) {
        if (couponId <= 0L || editCouponId == couponId && _uiState.value.couponInfo != null) {
            return
        }

        editCouponId = couponId
        updateState {
            it.copy(
                isProcessing = true,
                error = null,
                saveResult = null
            )
        }

        viewModelScope.launch {
            try {
                val coupon = couponRepository.getCouponById(couponId)
                    ?: throw IllegalStateException("Coupon not found")
                updateState {
                    it.copy(
                        isProcessing = false,
                        couponInfo = mapCouponToCouponInfo(coupon),
                        persistedImageUri = coupon.imageUri,
                        editingCoupon = coupon,
                        extractedCoupon = null
                    )
                }
            } catch (e: Exception) {
                handleError(e, "Error loading coupon")
            }
        }
    }

    /**
     * Save the coupon
     * @param storeName Store name
     * @param description Description
     * @param code Redeem code
     * @param expiryDate Expiry date
     * @param category Category
     * @param imageUri Image URI
     */
    fun saveCoupon(
        storeName: String,
        description: String,
        code: String,
        expiryDate: Date?,
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

                updateState {
                    it.copy(
                        isSaving = true,
                        error = null,
                        saveResult = null,
                        isSaved = false
                    )
                }

                val editingCoupon = _uiState.value.editingCoupon
                if (editingCoupon != null) {
                    val updatedCoupon = editingCoupon.copy(
                        storeName = storeName,
                        description = description,
                        redeemCode = code.takeIf { it.isNotBlank() },
                        expiryDate = expiryDate,
                        category = category.takeIf { it.isNotBlank() },
                        imageUri = _uiState.value.persistedImageUri ?: imageUri ?: editingCoupon.imageUri,
                        normalizedDescription = CouponDedupUtils.normalizeDescription(description),
                        cleanupStatus = Coupon.CleanupStatus.NONE,
                        cleanupError = null,
                        extractionSource = Coupon.ExtractionSource.USER_EDITED,
                        needsAttention = false,
                        updatedAt = Date()
                    )
                    couponRepository.updateCoupon(updatedCoupon)
                    updateState {
                        it.copy(
                            isSaving = false,
                            isSaved = true,
                            saveResult = CouponSaveResult.UPDATED,
                            savedCoupon = updatedCoupon,
                            editingCoupon = updatedCoupon,
                            couponInfo = mapCouponToCouponInfo(updatedCoupon)
                        )
                    }
                    return@launch
                }

                // Create and save coupon object, preserving extraction metadata from scan.
                val persistedImageUri = _uiState.value.persistedImageUri ?: imageUri
                val coupon = createCoupon(
                    baseline = _uiState.value.extractedCoupon,
                    storeName = storeName,
                    description = description,
                    code = code,
                    expiryDate = expiryDate,
                    category = category,
                    imageUri = persistedImageUri
                )
                val normalizedDescription = CouponDedupUtils.normalizeDescription(coupon.description)
                val savedCouponId = couponRepository.saveOrMergeCoupon(
                    coupon = coupon,
                    normalizedDescription = normalizedDescription,
                    imagePhash = null,
                    imageSignature = null
                )

                val savedCoupon = couponRepository.getCouponById(savedCouponId) ?: coupon.copy(id = savedCouponId)
                val isDuplicate = savedCoupon.createdAt.before(coupon.createdAt)
                val result = if (isDuplicate) CouponSaveResult.ALREADY_SAVED else CouponSaveResult.SAVED
                if (result == CouponSaveResult.SAVED) {
                    maybeQueueAutomaticVerification(savedCoupon)
                }

                updateState {
                    it.copy(
                        isSaving = false,
                        isSaved = true,
                        saveResult = result,
                        savedCoupon = savedCoupon
                    )
                }
            } catch (e: Exception) {
                handleError(e, "Error saving coupon")
            }
        }
    }

    /**
     * Create a coupon object from the provided parameters
     */
    private fun createCoupon(
        baseline: Coupon?,
        storeName: String,
        description: String,
        code: String,
        expiryDate: Date?,
        category: String,
        imageUri: String?
    ): Coupon {
        val now = Date()
        return (baseline ?: Coupon(
            storeName = storeName,
            description = description,
            redeemCode = code.takeIf { it.isNotBlank() },
            expiryDate = expiryDate,
            category = category.takeIf { it.isNotBlank() },
            imageUri = imageUri,
            createdAt = now,
            updatedAt = now
        )).copy(
            id = 0,
            storeName = storeName,
            description = description,
            redeemCode = code.takeIf { it.isNotBlank() },
            expiryDate = expiryDate,
            category = category.takeIf { it.isNotBlank() },
            imageUri = imageUri ?: baseline?.imageUri,
            normalizedDescription = CouponDedupUtils.normalizeDescription(description),
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun maybeQueueAutomaticVerification(coupon: Coupon) {
        val assessment = CouponExtractionConfidenceScorer.score(coupon, coupon.rawOcrText)
        if (assessment.recommendation != ExtractionRecommendation.VERIFY_WITH_VISION) {
            return
        }
        if (coupon.cleanupStatus == Coupon.CleanupStatus.PENDING ||
            coupon.cleanupStatus == Coupon.CleanupStatus.RUNNING ||
            coupon.hasTrustedCleanup()
        ) {
            return
        }

        val pendingCoupon = coupon.copy(
            cleanupStatus = Coupon.CleanupStatus.PENDING,
            cleanupError = null,
            cleanupStartedAt = null,
            cleanupFinishedAt = null,
            updatedAt = Date()
        )
        couponRepository.updateCoupon(pendingCoupon)
        VerifyCouponWorker.enqueueAutomaticVerification(getApplication(), coupon.id)
    }

    private fun Coupon.hasTrustedCleanup(): Boolean {
        return cleanupStatus == Coupon.CleanupStatus.CLEANED &&
            !needsAttention &&
            extractionSource in setOf(
                Coupon.ExtractionSource.VISION_VERIFIED,
                Coupon.ExtractionSource.QWEN_CLEANED,
                Coupon.ExtractionSource.OCR_VERIFIED
            )
    }

    /**
     * Map a Coupon object to CouponInfo
     */
    private fun mapCouponToCouponInfo(coupon: Coupon): CouponInfo {
        val cashbackDetail = DescriptionUtils.extractCashbackLine(coupon.description)
        val discountType = when {
            cashbackDetail?.contains("%") == true -> "PERCENTAGE"
            cashbackDetail?.any { it.isDigit() } == true -> "AMOUNT"
            else -> null
        }

        return CouponInfo(
            storeName = coupon.storeName,
            description = coupon.description,
            expiryDate = coupon.expiryDate,
            cashbackDetail = cashbackDetail,
            redeemCode = coupon.redeemCode,
            category = coupon.category,
            rating = coupon.rating,
            status = coupon.status,
            discountType = discountType,
            minimumPurchase = coupon.minimumPurchase,
            maximumDiscount = coupon.maximumDiscount,
            paymentMethod = coupon.paymentMethod,
            platformType = coupon.platformType,
            usageLimit = coupon.usageLimit
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
                error = "$message: ${e.message}",
                saveResult = null
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
    val error: String? = null,
    val saveResult: CouponSaveResult? = null,
    val savedCoupon: Coupon? = null,
    val persistedImageUri: String? = null,
    val editingCoupon: Coupon? = null,
    val extractedCoupon: Coupon? = null
)

enum class CouponSaveResult {
    SAVED,
    UPDATED,
    ALREADY_SAVED
}

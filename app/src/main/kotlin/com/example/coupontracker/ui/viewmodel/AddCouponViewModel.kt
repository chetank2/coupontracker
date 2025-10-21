package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.UriPersistenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class AddCouponViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context,
    private val repository: CouponRepository
) : AndroidViewModel(application) {

    private val _savingState = MutableStateFlow<SavingState>(SavingState.Idle)
    val savingState: StateFlow<SavingState> = _savingState

    private val _couponSaved = MutableStateFlow(false)
    val couponSaved: StateFlow<Boolean> = _couponSaved

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _couponForEdit = MutableStateFlow<Coupon?>(null)
    val couponForEdit: StateFlow<Coupon?> = _couponForEdit

    var couponId: Long = 0
        private set

    private val uriPersistenceManager = UriPersistenceManager(context)
    private var currentImageUri: Uri? = null
    private var currentExpiryDate: Date? = null
    private var currentReminderDate: Date? = null
    private var reminderLeadTimeMinutes: Int? = null
    private var remindersEnabled: Boolean = false
    private var isPriority: Boolean = false
    private var isEditMode: Boolean = false
    private var originalCoupon: Coupon? = null

    fun setImageUri(uri: Uri) {
        currentImageUri = uri
    }

    fun setExpiryDate(date: Date?) {
        currentExpiryDate = date
        if (remindersEnabled && reminderLeadTimeMinutes != null && date != null) {
            currentReminderDate = computeReminderDate(date, reminderLeadTimeMinutes!!)
        }
    }

    fun setReminderDate(date: Date?) {
        currentReminderDate = date
        remindersEnabled = date != null
    }

    fun setReminderEnabled(enabled: Boolean) {
        remindersEnabled = enabled
        if (!enabled) {
            reminderLeadTimeMinutes = null
            currentReminderDate = null
        } else if (currentExpiryDate != null && reminderLeadTimeMinutes != null) {
            currentReminderDate = computeReminderDate(currentExpiryDate!!, reminderLeadTimeMinutes!!)
        }
    }

    fun setReminderLeadTime(minutes: Int?) {
        reminderLeadTimeMinutes = minutes
        if (remindersEnabled && minutes != null && currentExpiryDate != null) {
            currentReminderDate = computeReminderDate(currentExpiryDate!!, minutes)
        }
    }

    fun getReminderLeadTimeMinutes(): Int? = reminderLeadTimeMinutes

    fun isReminderEnabled(): Boolean = remindersEnabled

    fun getReminderDate(): Date? = currentReminderDate

    fun setPriority(priority: Boolean) {
        isPriority = priority
    }

    fun clearError() {
        _error.value = null
    }

    fun loadCouponForEdit(couponId: Long) {
        if (couponId <= 0) {
            return
        }

        isEditMode = true
        this.couponId = couponId
        viewModelScope.launch {
            val coupon = repository.getCouponById(couponId)
            originalCoupon = coupon
            _couponForEdit.value = coupon
            coupon?.let {
                currentImageUri = it.imageUri?.let(Uri::parse)
                currentExpiryDate = it.expiryDate
                currentReminderDate = it.reminderDate
                reminderLeadTimeMinutes = it.reminderLeadTimeMinutes
                remindersEnabled = it.reminderDate != null
                isPriority = it.isPriority
            }
            _couponSaved.value = false
        }
    }

    fun saveCoupon(
        storeName: String,
        description: String,
        cashbackAmount: Double,
        redeemCode: String? = null,
        category: String? = null,
        rating: String? = null,
        status: String? = null,
        minimumPurchase: Double? = null,
        maximumDiscount: Double? = null,
        paymentMethod: String? = null,
        platformType: String? = null,
        usageLimit: Int? = null
    ) {
        if (storeName.isBlank() || description.isBlank()) {
            _error.value = "Store name and description are required"
            return
        }

        viewModelScope.launch {
            _savingState.value = SavingState.Saving
            try {
                val persistedImageUri = currentImageUri?.let { uri ->
                    uriPersistenceManager.persistUri(uri)?.toString() ?: uri.toString()
                }
                val resolvedReminderLeadTime = if (remindersEnabled) reminderLeadTimeMinutes else null

                if (isEditMode) {
                    val existingCoupon = originalCoupon
                        ?: repository.getCouponById(couponId)
                        ?: throw IllegalStateException("Coupon to edit not found")

                    val updatedCoupon = existingCoupon.copy(
                        storeName = storeName,
                        description = description,
                        expiryDate = currentExpiryDate,
                        cashbackAmount = cashbackAmount,
                        redeemCode = redeemCode ?: existingCoupon.redeemCode,
                        imageUri = persistedImageUri ?: existingCoupon.imageUri,
                        category = category ?: existingCoupon.category,
                        rating = rating ?: existingCoupon.rating,
                        status = status ?: existingCoupon.status,
                        minimumPurchase = minimumPurchase,
                        maximumDiscount = maximumDiscount,
                        isPriority = isPriority,
                        paymentMethod = paymentMethod ?: existingCoupon.paymentMethod,
                        usageLimit = usageLimit ?: existingCoupon.usageLimit,
                        reminderLeadTimeMinutes = resolvedReminderLeadTime,
                        reminderDate = currentReminderDate,
                        platformType = platformType ?: existingCoupon.platformType,
                        updatedAt = Date()
                    )

                    repository.updateCoupon(updatedCoupon)
                    originalCoupon = updatedCoupon
                    couponId = updatedCoupon.id
                    _couponForEdit.value = updatedCoupon
                } else {
                    val coupon = Coupon(
                        storeName = storeName,
                        description = description,
                        expiryDate = currentExpiryDate,
                        cashbackAmount = cashbackAmount,
                        redeemCode = redeemCode,
                        imageUri = persistedImageUri,
                        category = category,
                        rating = rating,
                        status = status ?: "Active",
                        minimumPurchase = minimumPurchase,
                        maximumDiscount = maximumDiscount,
                        isPriority = isPriority,
                        paymentMethod = paymentMethod,
                        usageLimit = usageLimit,
                        usageCount = 0,
                        reminderDate = currentReminderDate,
                        reminderLeadTimeMinutes = resolvedReminderLeadTime,
                        platformType = platformType
                    )
                    couponId = repository.insertCoupon(coupon)
                }

                _couponSaved.value = true
                _savingState.value = SavingState.Success
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to save coupon"
                _savingState.value = SavingState.Error(e.message ?: "Failed to save coupon")
            }
        }
    }

    private fun computeReminderDate(expiry: Date, leadTimeMinutes: Int): Date? {
        val millis = TimeUnit.MINUTES.toMillis(leadTimeMinutes.toLong())
        val reminderTime = expiry.time - millis
        return if (reminderTime > 0) Date(reminderTime) else null
    }

    sealed class SavingState {
        object Idle : SavingState()
        object Saving : SavingState()
        object Success : SavingState()
        data class Error(val message: String) : SavingState()
    }
}

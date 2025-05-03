package com.example.coupontracker.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class AddCouponViewModel @Inject constructor(
    private val repository: CouponRepository
) : ViewModel() {

    private val _savingState = MutableStateFlow<SavingState>(SavingState.Idle)
    val savingState: StateFlow<SavingState> = _savingState

    private val _couponSaved = MutableStateFlow(false)
    val couponSaved: StateFlow<Boolean> = _couponSaved

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var couponId: Long = 0
        private set

    private var currentImageUri: Uri? = null
    private var currentExpiryDate: Date = Date()
    private var currentReminderDate: Date? = null
    private var isPriority: Boolean = false

    fun setImageUri(uri: Uri) {
        currentImageUri = uri
    }

    fun setExpiryDate(date: Date) {
        currentExpiryDate = date
    }

    fun setReminderDate(date: Date?) {
        currentReminderDate = date
    }

    fun setPriority(priority: Boolean) {
        isPriority = priority
    }

    fun clearError() {
        _error.value = null
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
                val coupon = Coupon(
                    storeName = storeName,
                    description = description,
                    expiryDate = currentExpiryDate,
                    cashbackAmount = cashbackAmount,
                    redeemCode = redeemCode,
                    imageUri = currentImageUri?.toString(),
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
                    platformType = platformType
                )
                couponId = repository.insertCoupon(coupon)
                _couponSaved.value = true
                _savingState.value = SavingState.Success
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to save coupon"
                _savingState.value = SavingState.Error(e.message ?: "Failed to save coupon")
            }
        }
    }

    sealed class SavingState {
        object Idle : SavingState()
        object Saving : SavingState()
        object Success : SavingState()
        data class Error(val message: String) : SavingState()
    }
}
package com.example.coupontracker.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: CouponRepository,
    private val notificationManager: CouponNotificationManager
) : ViewModel() {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    private val _coupon = MutableStateFlow<Coupon?>(null)
    val coupon: StateFlow<Coupon?> = _coupon

    private var couponId: Long = 0

    fun loadCoupon(id: Long) {
        couponId = id
        viewModelScope.launch {
            val couponData = repository.getCouponById(id)
            _coupon.value = couponData
        }
    }

    fun deleteCoupon() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                // Cancel any notifications for this coupon
                notificationManager.cancelNotification(coupon.id)

                // Delete the coupon
                repository.deleteCoupon(coupon)
            }
        }
    }

    /**
     * Toggle the priority status of the coupon
     */
    fun togglePriority() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                repository.updateCouponPriority(coupon.id, !coupon.isPriority)
                // Refresh coupon data
                loadCoupon(coupon.id)
            }
        }
    }

    /**
     * Track usage of the coupon
     */
    fun trackUsage(amountSaved: Double = 0.0) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update usage count
                    repository.updateCouponUsageCount(coupon.id)

                    // If this was the last use and the coupon has a usage limit, mark as used
                    if (coupon.usageLimit != null && coupon.usageCount + 1 >= coupon.usageLimit) {
                        repository.updateCouponStatus(coupon.id, "Used")
                    }

                    // Track savings if applicable
                    if (amountSaved > 0) {
                        // In a real app, you would update a savings tracker here
                        Log.d(TAG, "Saved $amountSaved with coupon ${coupon.id}")
                    }

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error tracking usage", e)
                }
            }
        }
    }

    /**
     * Set a reminder for the coupon
     */
    fun setReminder(date: Date) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update the reminder date
                    repository.updateCouponReminder(coupon.id, date)

                    // Schedule notification
                    notificationManager.showReminderNotification(
                        coupon.id,
                        coupon.storeName,
                        coupon.description,
                        "Reminder to use your coupon"
                    )

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting reminder", e)
                }
            }
        }
    }

    /**
     * Cancel the reminder for the coupon
     */
    fun cancelReminder() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                try {
                    // Update the reminder date to null
                    repository.updateCouponReminder(coupon.id, null)

                    // Cancel notification
                    notificationManager.cancelNotification(coupon.id)

                    // Refresh coupon data
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error canceling reminder", e)
                }
            }
        }
    }
}
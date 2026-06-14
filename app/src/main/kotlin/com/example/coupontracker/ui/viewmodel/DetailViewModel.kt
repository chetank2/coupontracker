package com.example.coupontracker.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.debug.ExtractionDebugRepository
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.util.CouponNotificationManager
import com.example.coupontracker.worker.CouponCleanupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CouponRepository,
    private val notificationManager: CouponNotificationManager,
    private val debugRepository: ExtractionDebugRepository
) : ViewModel() {

    companion object {
        private const val TAG = "DetailViewModel"
    }

    private val _coupon = MutableStateFlow<Coupon?>(null)
    val coupon: StateFlow<Coupon?> = _coupon.asStateFlow()

    private val currentCouponId = MutableStateFlow<Long?>(null)

    val debugSnapshot: StateFlow<ExtractionDebugSnapshot?> = currentCouponId
        .flatMapLatest { id ->
            if (id == null) {
                flowOf(null)
            } else {
                debugRepository.snapshots.map { it[id] }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    private var couponId: Long = 0
    private var couponObserverJob: Job? = null

    fun loadCoupon(id: Long) {
        couponId = id
        currentCouponId.value = id
        couponObserverJob?.cancel()
        couponObserverJob = viewModelScope.launch {
            repository.observeCouponById(id).collect { couponData ->
                _coupon.value = couponData
            }
        }
    }

    fun cleanCoupon() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                repository.updateCoupon(
                    coupon.copy(
                        cleanupStatus = Coupon.CleanupStatus.PENDING,
                        cleanupError = null,
                        cleanupStartedAt = null,
                        cleanupFinishedAt = null,
                        updatedAt = Date()
                    )
                )
                CouponCleanupWorker.enqueue(context, coupon.id)
            }
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
                    repository.updateCouponStatus(coupon.id, Coupon.Status.USED)

                    // If this was the last use and the coupon has a usage limit, mark as used
                    if (coupon.usageLimit != null && coupon.usageCount + 1 >= coupon.usageLimit) {
                        repository.updateCouponStatus(coupon.id, Coupon.Status.USED)
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
    fun setReminderLeadTime(leadTimeMinutes: Int) {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                val expiry = coupon.expiryDate ?: return@launch
                val reminderAt = Date(expiry.time - TimeUnit.MINUTES.toMillis(leadTimeMinutes.toLong()))
                try {
                    repository.updateCouponReminder(coupon.id, reminderAt, leadTimeMinutes)
                    loadCoupon(coupon.id)
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting reminder lead time", e)
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
                    val leadMinutes = coupon.expiryDate?.let { expiry ->
                        val diffMillis = expiry.time - date.time
                        if (diffMillis > 0) {
                            TimeUnit.MILLISECONDS.toMinutes(diffMillis).toInt()
                        } else {
                            0
                        }
                    } ?: 0

                    repository.updateCouponReminder(coupon.id, date, leadMinutes)

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
                    repository.updateCouponReminder(coupon.id, null, null)

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

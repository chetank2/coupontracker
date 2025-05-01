package com.example.coupontracker.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.util.CouponNotificationManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Worker for checking coupon expirations and sending reminders
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CouponRepository,
    private val notificationManager: CouponNotificationManager
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "ReminderWorker"
        const val WORK_NAME = "coupon_reminder_worker"
        
        /**
         * Schedule daily reminder checks
         */
        fun scheduleDaily(workManager: WorkManager) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val dailyWorkRequest = PeriodicWorkRequestBuilder<ReminderWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .build()
            
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                dailyWorkRequest
            )
        }
    }
    
    override suspend fun doWork(): Result {
        try {
            Log.d(TAG, "Starting coupon reminder check")
            
            // Get current date
            val today = Calendar.getInstance().time
            
            // Get date for tomorrow
            val tomorrow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 1)
            }.time
            
            // Get date for 3 days from now
            val threeDaysFromNow = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, 3)
            }.time
            
            // Get coupons expiring tomorrow
            val couponsExpiringTomorrow = repository.getCouponsExpiringBetween(today, tomorrow).first()
            
            // Get coupons expiring in 3 days
            val couponsExpiringIn3Days = repository.getCouponsExpiringBetween(tomorrow, threeDaysFromNow).first()
            
            // Send notifications for coupons expiring tomorrow
            couponsExpiringTomorrow.forEach { coupon ->
                notificationManager.showExpiryNotification(
                    coupon.id,
                    coupon.storeName,
                    coupon.description,
                    "Expires tomorrow"
                )
            }
            
            // Send notifications for coupons expiring in 3 days
            couponsExpiringIn3Days.forEach { coupon ->
                notificationManager.showExpiryNotification(
                    coupon.id,
                    coupon.storeName,
                    coupon.description,
                    "Expires in 3 days"
                )
            }
            
            Log.d(TAG, "Coupon reminder check completed")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in coupon reminder worker", e)
            return Result.failure()
        }
    }
}

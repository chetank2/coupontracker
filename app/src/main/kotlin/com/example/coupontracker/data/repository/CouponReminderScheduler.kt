package com.example.coupontracker.data.repository

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.work.CouponReminderWorker
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CouponReminderScheduler @Inject constructor(
    private val workManager: WorkManager
) {

    fun schedule(coupon: Coupon, computedReminderAt: Date?): Date? {
        val leadTimeMinutes = coupon.reminderLeadTimeMinutes ?: return cancelAndReturn(null, coupon.id)
        val reminderTime = computedReminderAt ?: return cancelAndReturn(null, coupon.id)

        val delayMillis = reminderTime.time - System.currentTimeMillis()
        if (delayMillis <= 0L) {
            Log.d(TAG, "Reminder for coupon ${coupon.id} is in the past; skipping schedule")
            workManager.cancelUniqueWork(uniqueName(coupon.id))
            return reminderTime
        }

        if (coupon.id == 0L) {
            Log.d(TAG, "Coupon ID not set yet; reminder scheduling deferred")
            return reminderTime
        }

        val request = OneTimeWorkRequestBuilder<CouponReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(CouponReminderWorker.inputData(coupon.id, leadTimeMinutes))
            .addTag(reminderTag(coupon.id))
            .build()

        workManager.enqueueUniqueWork(uniqueName(coupon.id), ExistingWorkPolicy.REPLACE, request)
        Log.d(TAG, "Scheduled reminder for coupon ${coupon.id} at $reminderTime with delay $delayMillis ms")
        return reminderTime
    }

    fun cancel(couponId: Long) {
        if (couponId == 0L) return
        workManager.cancelUniqueWork(uniqueName(couponId))
        Log.d(TAG, "Cancelled reminder work for coupon $couponId")
    }

    private fun cancelAndReturn(returnValue: Date?, couponId: Long): Date? {
        cancel(couponId)
        return returnValue
    }

    private fun uniqueName(couponId: Long): String = "coupon-reminder-$couponId"

    private fun reminderTag(couponId: Long): String = "coupon-reminder-tag-$couponId"

    companion object {
        private const val TAG = "CouponReminderScheduler"
    }
}

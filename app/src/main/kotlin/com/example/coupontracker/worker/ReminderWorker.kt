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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
            Log.d(TAG, "Starting coupon reminder check with ISO date support")
            
            val now = Date()
            val today = startOfDay(now)
            val tomorrow = addDays(today, 1)
            val sevenDaysFromNow = addDays(today, 7)
            
            // Get all active coupons
            val allCoupons = repository.getAllCoupons().first()
            
            // Filter coupons for D-1 (expires tomorrow) and D-7 (expires in 7 days) reminders
            val couponsForD1Reminder = allCoupons.filter { coupon ->
                shouldSendD1Reminder(coupon, today, tomorrow)
            }
            
            val couponsForD7Reminder = allCoupons.filter { coupon ->
                shouldSendD7Reminder(coupon, today, sevenDaysFromNow)
            }
            
            // Send D-1 reminders (expires tomorrow)
            couponsForD1Reminder.forEach { coupon ->
                val expiryDate = parseExpiryDate(coupon.expiryIso) ?: coupon.expiryDate
                val daysUntilExpiry = calculateDaysUntilExpiry(expiryDate, today)
                
                notificationManager.showExpiryNotification(
                    coupon.id,
                    coupon.storeName,
                    coupon.description,
                    "Expires in $daysUntilExpiry day${if (daysUntilExpiry == 1) "" else "s"}"
                )
                
                Log.d(TAG, "Sent D-1 reminder for coupon: ${coupon.storeName} (expires: $expiryDate)")
            }
            
            // Send D-7 reminders (expires in 7 days)
            couponsForD7Reminder.forEach { coupon ->
                val expiryDate = parseExpiryDate(coupon.expiryIso) ?: coupon.expiryDate
                val daysUntilExpiry = calculateDaysUntilExpiry(expiryDate, today)
                
                notificationManager.showExpiryNotification(
                    coupon.id,
                    coupon.storeName,
                    coupon.description,
                    "Expires in $daysUntilExpiry days"
                )
                
                Log.d(TAG, "Sent D-7 reminder for coupon: ${coupon.storeName} (expires: $expiryDate)")
            }
            
            Log.d(TAG, "Coupon reminder check completed: D-1=${couponsForD1Reminder.size}, D-7=${couponsForD7Reminder.size}")
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in coupon reminder worker", e)
            return Result.failure()
        }
    }
    
    /**
     * Check if a coupon should receive a D-1 reminder (expires tomorrow)
     */
    private fun shouldSendD1Reminder(coupon: com.example.coupontracker.data.model.Coupon, today: Date, tomorrow: Date): Boolean {
        val expiryDate = parseExpiryDate(coupon.expiryIso) ?: coupon.expiryDate
        if (expiryDate.before(today)) return false // Already expired
        
        val daysUntilExpiry = calculateDaysUntilExpiry(expiryDate, today)
        return daysUntilExpiry == 1
    }
    
    /**
     * Check if a coupon should receive a D-7 reminder (expires in 7 days)
     */
    private fun shouldSendD7Reminder(coupon: com.example.coupontracker.data.model.Coupon, today: Date, sevenDaysFromNow: Date): Boolean {
        val expiryDate = parseExpiryDate(coupon.expiryIso) ?: coupon.expiryDate
        if (expiryDate.before(today)) return false // Already expired
        
        val daysUntilExpiry = calculateDaysUntilExpiry(expiryDate, today)
        return daysUntilExpiry == 7
    }
    
    /**
     * Parse ISO date string to Date object
     */
    private fun parseExpiryDate(isoDate: String?): Date? {
        if (isoDate.isNullOrBlank()) return null
        
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy"
        )
        
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(isoDate)
            } catch (e: Exception) {
                // Try next format
            }
        }
        
        return null
    }
    
    /**
     * Calculate days until expiry
     */
    private fun calculateDaysUntilExpiry(expiryDate: Date, today: Date): Int {
        val calendar = Calendar.getInstance()
        calendar.time = today
        val todayStart = startOfDay(calendar.time)
        
        calendar.time = expiryDate
        val expiryStart = startOfDay(calendar.time)
        
        val diffInMillis = expiryStart.time - todayStart.time
        return (diffInMillis / (24 * 60 * 60 * 1000)).toInt()
    }
    
    /**
     * Get start of day (00:00:00)
     */
    private fun startOfDay(date: Date): Date {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }
    
    /**
     * Add days to a date
     */
    private fun addDays(date: Date, days: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.add(Calendar.DAY_OF_YEAR, days)
        return calendar.time
    }
}

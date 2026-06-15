package com.example.coupontracker.work

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.coupontracker.CouponTrackerApplication
import com.example.coupontracker.R
import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.activity.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.Date
import java.util.concurrent.TimeUnit

@HiltWorker
class CouponReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val couponDao: CouponDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val now = Date()
            val startWindow = now
            val endWindow = Date(now.time + TimeUnit.HOURS.toMillis(48))

            val upcomingCoupons = couponDao.getCouponsExpiringBetween(startWindow, endWindow)
                .filter { coupon ->
                    coupon.reminderLeadTimeMinutes != null && coupon.expiryDate != null
                }

            upcomingCoupons.forEach { coupon ->
                if (shouldNotify(coupon, now)) {
                    postNotification(coupon)
                }
            }

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to execute coupon reminder worker", t)
            Result.retry()
        }
    }

    private fun shouldNotify(coupon: Coupon, now: Date): Boolean {
        val expiry = coupon.expiryDate ?: return false
        val leadTimeMinutes = coupon.reminderLeadTimeMinutes ?: return false
        val millisUntilExpiry = expiry.time - now.time
        if (millisUntilExpiry <= 0) {
            return false
        }

        val leadTimeMillis = TimeUnit.MINUTES.toMillis(leadTimeMinutes.toLong())
        return millisUntilExpiry <= leadTimeMillis
    }

    private fun postNotification(coupon: Coupon) {
        val context = applicationContext
        val notificationsEnabled = context
            .getSharedPreferences("coupon_settings", Context.MODE_PRIVATE)
            .getBoolean("notifications_enabled", true)
        if (!notificationsEnabled) {
            Log.d(TAG, "Notifications disabled; skipping reminder for coupon ${coupon.id}")
            return
        }

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            Log.d(TAG, "System notifications disabled; skipping reminder for coupon ${coupon.id}")
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_COUPON_ID, coupon.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(coupon),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = context.getString(
            R.string.notification_coupon_reminder_body,
            coupon.description
        )

        val notification = NotificationCompat.Builder(context, CouponTrackerApplication.REMINDER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(
                    R.string.notification_coupon_reminder_title,
                    coupon.storeName
                )
            )
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()

        notificationManager.notify(notificationId(coupon), notification)
    }

    private fun notificationId(coupon: Coupon): Int = coupon.id.hashCode()

    companion object {
        private const val TAG = "CouponReminderWorker"
        const val KEY_COUPON_ID = "coupon_id"
        const val KEY_COUPON_LEAD_TIME_MINUTES = "coupon_lead_time_minutes"

        fun inputData(couponId: Long, leadTimeMinutes: Int) = workDataOf(
            KEY_COUPON_ID to couponId,
            KEY_COUPON_LEAD_TIME_MINUTES to leadTimeMinutes
        )
    }
}

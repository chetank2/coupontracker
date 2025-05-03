package com.example.coupontracker.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.coupontracker.R
import com.example.coupontracker.ui.activity.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for handling coupon notifications
 */
@Singleton
class CouponNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    companion object {
        private const val CHANNEL_ID = "coupon_reminders"
        private const val CHANNEL_NAME = "Coupon Reminders"
        private const val CHANNEL_DESCRIPTION = "Notifications for coupon expiry and reminders"
    }
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Create the notification channel for Android O and above
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show a notification for a coupon reminder
     */
    fun showReminderNotification(couponId: Long, storeName: String, description: String, reminderMessage: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("couponId", couponId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            couponId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Coupon Reminder: $storeName")
            .setContentText("$description - $reminderMessage")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        
        // Show the notification
        notificationManager.notify(couponId.toInt(), builder.build())
    }
    
    /**
     * Show a notification for a coupon expiry
     */
    fun showExpiryNotification(couponId: Long, storeName: String, description: String, expiryMessage: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("couponId", couponId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            couponId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Coupon Expiring: $storeName")
            .setContentText("$description - $expiryMessage")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        
        // Show the notification
        notificationManager.notify(couponId.toInt(), builder.build())
    }
    
    /**
     * Cancel a notification
     */
    fun cancelNotification(couponId: Long) {
        notificationManager.cancel(couponId.toInt())
    }
}

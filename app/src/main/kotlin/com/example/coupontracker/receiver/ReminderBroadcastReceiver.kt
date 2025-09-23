package com.example.coupontracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.coupontracker.util.CouponNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver for handling coupon reminders
 */
@AndroidEntryPoint
class ReminderBroadcastReceiver : BroadcastReceiver() {
    
    @Inject
    lateinit var notificationManager: CouponNotificationManager
    
    companion object {
        const val EXTRA_COUPON_ID = "coupon_id"
        const val EXTRA_STORE_NAME = "store_name"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TYPE = "type"
        
        const val TYPE_REMINDER = "reminder"
        const val TYPE_EXPIRY = "expiry"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        val couponId = intent.getLongExtra(EXTRA_COUPON_ID, -1)
        val storeName = intent.getStringExtra(EXTRA_STORE_NAME) ?: "Unknown Store"
        val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: "Coupon reminder"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Don't forget to use this coupon"
        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_REMINDER
        
        if (couponId != -1L) {
            if (type == TYPE_EXPIRY) {
                notificationManager.showExpiryNotification(couponId, storeName, description, message)
            } else {
                notificationManager.showReminderNotification(couponId, storeName, description, message)
            }
        }
    }
}

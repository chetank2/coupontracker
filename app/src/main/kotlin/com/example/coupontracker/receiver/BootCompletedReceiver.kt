package com.example.coupontracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.coupontracker.work.CouponReminderWorker
import dagger.hilt.android.AndroidEntryPoint

/**
 * BroadcastReceiver for handling device boot completed
 * Used to reschedule coupon reminders after device reboot
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Device boot completed, rescheduling coupon reminders")
            
            val workManager = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<CouponReminderWorker>().build()
            workManager.enqueueUniqueWork(
                "coupon-reminder-boot",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

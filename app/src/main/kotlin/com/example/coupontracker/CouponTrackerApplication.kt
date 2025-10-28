package com.example.coupontracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.coupontracker.R
import com.example.coupontracker.analytics.StoreNameMetricsTracker
import com.example.coupontracker.util.ExtractionConfig
import com.example.coupontracker.feedback.FeedbackFeatureToggle
import com.example.coupontracker.worker.OfflineRetrainingWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CouponTrackerApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var feedbackFeatureToggle: FeedbackFeatureToggle

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        try {
            super.onCreate()

            createReminderChannel()

            // V2: Initialize extraction strategy config (loads persisted strategy)
            // Defer to background thread for performance
            androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper()).post {
                ExtractionConfig.init(this)
            }

            // Initialize WorkManager-backed tasks
            initializeWorkers()

            Log.d("CouponTracker", "Application onCreate completed successfully")
        } catch (e: Exception) {
            Log.e("CouponTracker", "Error in Application onCreate", e)
        }
    }

    private fun initializeWorkers() {
        try {
            // Initialize AI telemetry guardrails
            StoreNameMetricsTracker.initialize(this)

            if (feedbackFeatureToggle.isOfflineRetrainingEnabled()) {
                OfflineRetrainingWorker.schedule(WorkManager.getInstance(this))
                Log.d("CouponTracker", "Scheduled offline retraining worker")
            } else {
                Log.d("CouponTracker", "Offline retraining worker disabled by feature flag")
            }
        } catch (e: Exception) {
            Log.e("CouponTracker", "Error scheduling reminder worker", e)
        }
    }

    private fun createReminderChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                REMINDER_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val REMINDER_CHANNEL_ID = "coupon_reminders"
    }
}
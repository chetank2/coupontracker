package com.example.coupontracker

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.coupontracker.analytics.StoreNameMetricsTracker
import com.example.coupontracker.worker.ReminderWorker
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

            // V2: Initialize extraction strategy config (loads persisted strategy)
            // Defer to background thread for performance
            androidx.core.os.HandlerCompat.createAsync(android.os.Looper.getMainLooper()).post {
                com.example.coupontracker.util.ExtractionConfig.init(this)
            }

            // Initialize WorkManager and schedule daily reminder checks
            // This is lightweight and won't block startup
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

            // Schedule daily reminder checks
            ReminderWorker.scheduleDaily(WorkManager.getInstance(this))
            Log.d("CouponTracker", "Scheduled reminder worker")

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
}
package com.example.coupontracker

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.coupontracker.worker.ReminderWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CouponTrackerApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        try {
            super.onCreate()

            // Initialize WorkManager and schedule daily reminder checks
            initializeWorkers()

            Log.d("CouponTracker", "Application onCreate completed successfully")
        } catch (e: Exception) {
            Log.e("CouponTracker", "Error in Application onCreate", e)
        }
    }

    private fun initializeWorkers() {
        try {
            // Schedule daily reminder checks
            ReminderWorker.scheduleDaily(WorkManager.getInstance(this))
            Log.d("CouponTracker", "Scheduled reminder worker")
        } catch (e: Exception) {
            Log.e("CouponTracker", "Error scheduling reminder worker", e)
        }
    }
}
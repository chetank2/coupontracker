package com.example.coupontracker

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
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
            Log.d("CouponTracker", "Application onCreate completed successfully")
        } catch (e: Exception) {
            Log.e("CouponTracker", "Error in Application onCreate", e)
        }
    }
} 
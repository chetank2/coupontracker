package com.example.coupontracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker to update widget periodically
 */
@HiltWorker
class CouponWidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "WidgetUpdateWorker"
        private const val WORK_NAME = "coupon_widget_update"
        
        /**
         * Schedule periodic widget updates (every 6 hours)
         */
        fun schedulePeriodicUpdate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val updateRequest = PeriodicWorkRequestBuilder<CouponWidgetUpdateWorker>(
                6, TimeUnit.HOURS,  // Repeat every 6 hours
                15, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                updateRequest
            )
            
            android.util.Log.d(TAG, "Scheduled periodic widget updates (every 6 hours)")
        }
        
        /**
         * Cancel periodic widget updates
         */
        fun cancelPeriodicUpdate(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            android.util.Log.d(TAG, "Cancelled periodic widget updates")
        }
        
        /**
         * Trigger immediate widget update (for manual refresh)
         */
        fun triggerUpdate(context: Context) {
            val updateRequest = OneTimeWorkRequestBuilder<CouponWidgetUpdateWorker>()
                .build()
            
            WorkManager.getInstance(context).enqueue(updateRequest)
        }
    }
    
    override suspend fun doWork(): Result {
        return try {
            android.util.Log.d(TAG, "Widget update worker started")
            
            // Trigger widget update broadcast
            val intent = Intent(applicationContext, CouponWidgetProvider::class.java).apply {
                action = CouponWidgetProvider.ACTION_WIDGET_UPDATE
            }
            
            // Get all widget IDs
            val appWidgetManager = AppWidgetManager.getInstance(applicationContext)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(applicationContext, CouponWidgetProvider::class.java)
            )
            
            // Update each widget
            appWidgetIds.forEach { appWidgetId ->
                updateAppWidget(applicationContext, appWidgetManager, appWidgetId)
            }
            
            android.util.Log.d(TAG, "Widget update completed: ${appWidgetIds.size} widgets updated")
            Result.success()
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Widget update failed", e)
            Result.failure()
        }
    }
}


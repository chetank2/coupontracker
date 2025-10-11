package com.example.coupontracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.coupontracker.R
import com.example.coupontracker.ui.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

/**
 * Home screen widget showing expiring coupons count
 * Updates automatically via WorkManager periodic job
 */
class CouponWidgetProvider : AppWidgetProvider() {
    
    companion object {
        private const val TAG = "CouponWidgetProvider"
        const val ACTION_WIDGET_UPDATE = "com.example.coupontracker.ACTION_WIDGET_UPDATE"
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update all widget instances
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        if (intent.action == ACTION_WIDGET_UPDATE) {
            // Manual update triggered by WorkManager
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, CouponWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, appWidgetIds)
        }
    }
    
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Schedule periodic widget updates via WorkManager
        CouponWidgetUpdateWorker.schedulePeriodicUpdate(context)
    }
    
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Cancel widget updates when last widget is removed
        CouponWidgetUpdateWorker.cancelPeriodicUpdate(context)
    }
}

/**
 * Update the widget UI
 */
internal fun updateAppWidget(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetId: Int
) {
    // Launch coroutine to fetch data
    CoroutineScope(Dispatchers.Main).launch {
        try {
            // Get coupon counts
            val counts = CouponWidgetDataProvider.getCouponCounts(context)
            
            // Create RemoteViews
            val views = RemoteViews(context.packageName, R.layout.widget_coupon).apply {
                // Set expiring count
                setTextViewText(R.id.widget_expiring_count, counts.expiringCount.toString())
                
                // Set total count
                setTextViewText(R.id.widget_total_count, counts.totalCount.toString())
                
                // Set expiring text (singular/plural)
                val expiringText = if (counts.expiringCount == 1) {
                    "coupon expiring soon"
                } else {
                    "coupons expiring soon"
                }
                setTextViewText(R.id.widget_expiring_text, expiringText)
                
                // Set urgency color
                val textColor = when {
                    counts.expiringCount == 0 -> android.graphics.Color.parseColor("#4CAF50") // Green
                    counts.expiringCount <= 3 -> android.graphics.Color.parseColor("#FF9800") // Orange
                    else -> android.graphics.Color.parseColor("#F44336") // Red
                }
                setTextColor(R.id.widget_expiring_count, textColor)
                
                // Set click intent to open app
                val intent = Intent(context, MainActivity::class.java)
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_container, pendingIntent)
            }
            
            // Update widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
            
        } catch (e: Exception) {
            android.util.Log.e("CouponWidget", "Failed to update widget", e)
            
            // Show error state
            val views = RemoteViews(context.packageName, R.layout.widget_coupon).apply {
                setTextViewText(R.id.widget_expiring_count, "--")
                setTextViewText(R.id.widget_total_count, "--")
                setTextViewText(R.id.widget_expiring_text, "Error loading data")
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

/**
 * Data class for widget counts
 */
data class WidgetCounts(
    val expiringCount: Int,
    val totalCount: Int,
    val activeCount: Int
)


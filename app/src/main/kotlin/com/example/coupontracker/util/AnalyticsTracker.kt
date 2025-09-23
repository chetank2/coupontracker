package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Analytics tracker for monitoring app usage patterns
 */
@Singleton
class AnalyticsTracker @Inject constructor(
    private val context: Context
) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val TAG = "AnalyticsTracker"
        private const val PREFS_NAME = "analytics_prefs"
        private const val KEY_EVENTS = "analytics_events"
        private const val KEY_USER_ID = "user_id"
        private const val MAX_STORED_EVENTS = 1000
        
        // Event types
        const val EVENT_CAPTURE_STARTED = "capture_started"
        const val EVENT_CAPTURE_COMPLETED = "capture_completed"
        const val EVENT_CAPTURE_FAILED = "capture_failed"
        const val EVENT_MODE_SELECTED = "mode_selected"
        const val EVENT_COUPON_DETECTED = "coupon_detected"
        const val EVENT_MULTIPLE_COUPONS_DETECTED = "multiple_coupons_detected"
        const val EVENT_QR_CODE_DETECTED = "qr_code_detected"
        const val EVENT_PROCESSING_TIME = "processing_time"
    }
    
    // Get or create a unique user ID
    private val userId: String
        get() {
            var id = sharedPreferences.getString(KEY_USER_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                sharedPreferences.edit().putString(KEY_USER_ID, id).apply()
            }
            return id
        }
    
    /**
     * Track an event
     * @param eventType The type of event
     * @param properties Additional properties for the event
     */
    suspend fun trackEvent(eventType: String, properties: Map<String, Any> = emptyMap()) {
        withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val formattedDate = dateFormat.format(Date(timestamp))
                
                val eventJson = JSONObject().apply {
                    put("event_type", eventType)
                    put("timestamp", timestamp)
                    put("formatted_time", formattedDate)
                    put("user_id", userId)
                    
                    // Add custom properties
                    val propertiesJson = JSONObject()
                    for ((key, value) in properties) {
                        propertiesJson.put(key, value.toString())
                    }
                    put("properties", propertiesJson)
                }
                
                // Get existing events
                val eventsJsonString = sharedPreferences.getString(KEY_EVENTS, "[]")
                val eventsArray = JSONArray(eventsJsonString)
                
                // Add new event
                eventsArray.put(eventJson)
                
                // Trim if too many events
                val trimmedArray = if (eventsArray.length() > MAX_STORED_EVENTS) {
                    JSONArray().apply {
                        for (i in (eventsArray.length() - MAX_STORED_EVENTS) until eventsArray.length()) {
                            put(eventsArray.get(i))
                        }
                    }
                } else {
                    eventsArray
                }
                
                // Save updated events
                sharedPreferences.edit()
                    .putString(KEY_EVENTS, trimmedArray.toString())
                    .apply()
                
                Log.d(TAG, "Tracked event: $eventType with properties: $properties")
            } catch (e: Exception) {
                Log.e(TAG, "Error tracking event", e)
            }
        }
    }
    
    /**
     * Track capture mode selection
     * @param mode The selected capture mode
     */
    suspend fun trackModeSelection(mode: String) {
        trackEvent(EVENT_MODE_SELECTED, mapOf("mode" to mode))
    }
    
    /**
     * Track processing time
     * @param processingTimeMs The processing time in milliseconds
     * @param operation The operation being timed
     */
    suspend fun trackProcessingTime(processingTimeMs: Long, operation: String) {
        trackEvent(EVENT_PROCESSING_TIME, mapOf(
            "time_ms" to processingTimeMs,
            "operation" to operation
        ))
    }
    
    /**
     * Get analytics data as JSON string
     * @return JSON string containing all tracked events
     */
    fun getAnalyticsData(): String {
        return sharedPreferences.getString(KEY_EVENTS, "[]") ?: "[]"
    }
    
    /**
     * Clear all analytics data
     */
    fun clearAnalyticsData() {
        sharedPreferences.edit().putString(KEY_EVENTS, "[]").apply()
    }
    
    /**
     * Get event counts by type
     * @return Map of event types to counts
     */
    suspend fun getEventCounts(): Map<String, Int> = withContext(Dispatchers.IO) {
        try {
            val eventsJsonString = sharedPreferences.getString(KEY_EVENTS, "[]")
            val eventsArray = JSONArray(eventsJsonString)
            val counts = mutableMapOf<String, Int>()
            
            for (i in 0 until eventsArray.length()) {
                val event = eventsArray.getJSONObject(i)
                val eventType = event.getString("event_type")
                counts[eventType] = (counts[eventType] ?: 0) + 1
            }
            
            return@withContext counts
        } catch (e: Exception) {
            Log.e(TAG, "Error getting event counts", e)
            return@withContext emptyMap<String, Int>()
        }
    }
}

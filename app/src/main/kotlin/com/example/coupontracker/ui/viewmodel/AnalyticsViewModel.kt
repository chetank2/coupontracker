package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.coupontracker.util.AnalyticsTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

/**
 * ViewModel for the analytics screen
 */
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analyticsTracker: AnalyticsTracker
) : ViewModel() {
    
    /**
     * Get event counts by type
     */
    suspend fun getEventCounts(): Map<String, Int> {
        return analyticsTracker.getEventCounts()
    }
    
    /**
     * Get average processing times by operation
     */
    suspend fun getAverageProcessingTimes(): Map<String, Long> {
        val eventCounts = analyticsTracker.getEventCounts()
        val processingTimeCount = eventCounts[AnalyticsTracker.EVENT_PROCESSING_TIME] ?: 0
        
        if (processingTimeCount == 0) {
            return emptyMap()
        }
        
        val eventsJsonString = analyticsTracker.getAnalyticsData()
        val eventsArray = JSONArray(eventsJsonString)
        
        val totalTimes = mutableMapOf<String, Long>()
        val counts = mutableMapOf<String, Int>()
        
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.getJSONObject(i)
            if (event.getString("event_type") == AnalyticsTracker.EVENT_PROCESSING_TIME) {
                val properties = event.getJSONObject("properties")
                val timeMs = properties.getString("time_ms").toLongOrNull() ?: continue
                val operation = properties.getString("operation")
                
                totalTimes[operation] = (totalTimes[operation] ?: 0L) + timeMs
                counts[operation] = (counts[operation] ?: 0) + 1
            }
        }
        
        return totalTimes.mapValues { (operation, totalTime) ->
            val count = counts[operation] ?: 1
            totalTime / count
        }
    }
    
    /**
     * Get recent events
     * @param limit Maximum number of events to return
     */
    suspend fun getRecentEvents(limit: Int): List<JSONObject> {
        val eventsJsonString = analyticsTracker.getAnalyticsData()
        val eventsArray = JSONArray(eventsJsonString)
        
        val events = mutableListOf<JSONObject>()
        
        // Get the most recent events (from the end of the array)
        val startIndex = maxOf(0, eventsArray.length() - limit)
        for (i in startIndex until eventsArray.length()) {
            events.add(eventsArray.getJSONObject(i))
        }
        
        return events
    }
    
    /**
     * Clear all analytics data
     */
    fun clearAnalyticsData() {
        analyticsTracker.clearAnalyticsData()
    }
}

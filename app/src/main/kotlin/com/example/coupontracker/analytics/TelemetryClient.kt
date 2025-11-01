package com.example.coupontracker.analytics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.coupontracker.util.AnalyticsTracker
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lightweight telemetry client that records counter-style metrics to SharedPreferences
 * and mirrors the counts into the analytics event log for dashboard ingestion.
 */
class TelemetryClient private constructor(
    private val appContext: Context,
    private val analyticsTracker: AnalyticsTracker
) {

    companion object {
        private const val TAG = "TelemetryClient"
        private const val PREFS_NAME = "telemetry_counters"
        private const val KEY_PREFIX = "counter_"
        private const val ANALYTICS_EVENT = "telemetry_counter"

        @Volatile
        private var INSTANCE: TelemetryClient? = null

        /**
         * Obtain the shared instance. Falls back to constructing its own AnalyticsTracker
         * when one is not provided (e.g., legacy manual construction paths).
         */
        fun getInstance(
            context: Context,
            analyticsTracker: AnalyticsTracker? = null
        ): TelemetryClient {
            val applicationContext = context.applicationContext
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelemetryClient(
                    applicationContext,
                    analyticsTracker ?: AnalyticsTracker(applicationContext)
                ).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inMemoryCounts = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Increment a named counter and emit an analytics event describing the new value.
     */
    fun incrementCounter(name: String, metadata: Map<String, Any?> = emptyMap()) {
        val key = name.trim()
        if (key.isEmpty()) {
            Log.w(TAG, "Ignoring telemetry increment with blank name")
            return
        }

        val sanitizedMetadata = metadata
            .filterValues { it != null }
            .mapValues { (_, value) -> value.toString() }

        val newValue = incrementPersistedCounter(key)
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "telemetry_counter name=%s count=%d metadata=%s",
                key,
                newValue,
                sanitizedMetadata
            )
        )

        scope.launch {
            runCatching {
                analyticsTracker.trackEvent(
                    ANALYTICS_EVENT,
                    buildMap {
                        put("name", key)
                        put("count", newValue.toString())
                        putAll(sanitizedMetadata)
                    }
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to record telemetry counter analytics event", error)
            }
        }
    }

    /**
     * Read the current value of a counter.
     */
    fun getCounterValue(name: String): Long {
        val key = name.trim()
        if (key.isEmpty()) return 0
        return inMemoryCounts[key]?.get() ?: prefs.getLong(KEY_PREFIX + key, 0L)
    }

    private fun incrementPersistedCounter(name: String): Long {
        val atomic = inMemoryCounts.getOrPut(name) { AtomicLong(prefs.getLong(KEY_PREFIX + name, 0L)) }
        val newValue = atomic.incrementAndGet()
        prefs.edit().putLong(KEY_PREFIX + name, newValue).apply()
        return newValue
    }
}

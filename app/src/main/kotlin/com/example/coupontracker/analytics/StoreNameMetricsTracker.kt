package com.example.coupontracker.analytics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.coupontracker.extraction.validation.StoreNameValidator
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.worker.StoreNameEvidenceWorker
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

object StoreNameMetricsTracker {
    private const val TAG = "StoreNameMetrics"
    private const val PREFS_NAME = "store_name_metrics"
    private const val KEY_TOTAL = "total_assessments"
    private const val KEY_MULTI_SIGNAL = "multi_signal"
    private const val KEY_HEURISTIC_ONLY = "heuristic_only"
    private const val KEY_NEEDS_ATTENTION = "needs_attention"
    private const val KEY_CTA_FALSE = "cta_false_positive"
    private const val KEY_PENDING = "pending_provenance"
    private const val SECURE_KEY_PROVENANCE = "store_name_provenance_samples"
    private const val MAX_PENDING = 200
    private const val MAX_SECURE = 200
    // Applied when deciding whether to enqueue evidence so ~1% of assessments are retained.
    private const val PROVENANCE_ENQUEUE_RATE = 0.01
    private const val WORK_NAME = "store_name_provenance_sampling"

    private val lock = Any()
    private var initialized = false
    private var jobScheduled = false
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var securePrefs: SecurePreferencesManager
    private val random = java.util.Random()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            securePrefs = SecurePreferencesManager(appContext).apply { initialize() }
            scheduleEvidenceSampling(appContext)
            initialized = true
        }
    }

    fun recordAssessment(source: String, assessment: StoreNameValidator.Assessment) {
        if (!initialized) {
            Log.w(TAG, "recordAssessment called before initialization")
            return
        }
        synchronized(lock) {
            val total = prefs.getInt(KEY_TOTAL, 0) + 1
            val uniqueCategories = assessment.signals.map { it.category }.toSet()
            val multiSignalIncrement = if (uniqueCategories.size >= 2) 1 else 0
            val heuristicOnlyIncrement = if (
                uniqueCategories.size == 1 && uniqueCategories.firstOrNull() == "heuristic"
            ) 1 else 0
            val needsAttentionIncrement = if (assessment.needsAttention) 1 else 0
            val ctaIncrement = if (assessment.issues.any { it.contains("cta_stopword") }) 1 else 0

            prefs.edit()
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_MULTI_SIGNAL, prefs.getInt(KEY_MULTI_SIGNAL, 0) + multiSignalIncrement)
                .putInt(KEY_HEURISTIC_ONLY, prefs.getInt(KEY_HEURISTIC_ONLY, 0) + heuristicOnlyIncrement)
                .putInt(KEY_NEEDS_ATTENTION, prefs.getInt(KEY_NEEDS_ATTENTION, 0) + needsAttentionIncrement)
                .putInt(KEY_CTA_FALSE, prefs.getInt(KEY_CTA_FALSE, 0) + ctaIncrement)
                .apply()

            if (ctaIncrement > 0) {
                Log.w(TAG, "CTA stop-word triggered for source=$source value=${assessment.original}")
            }

            emitPercentages(total)
            enqueuePendingEvidence(source, assessment)
        }
    }

    fun recordCtaFalsePositive(source: String, candidate: String?) {
        if (!initialized) return
        synchronized(lock) {
            val total = prefs.getInt(KEY_TOTAL, 0)
            prefs.edit()
                .putInt(KEY_CTA_FALSE, prefs.getInt(KEY_CTA_FALSE, 0) + 1)
                .apply()
            Log.w(TAG, "CTA false positive recorded for source=$source candidate=$candidate (total assessments=$total)")
        }
    }

    private fun emitPercentages(total: Int) {
        if (total == 0) return
        val multi = prefs.getInt(KEY_MULTI_SIGNAL, 0)
        val heuristicOnly = prefs.getInt(KEY_HEURISTIC_ONLY, 0)
        val needsAttention = prefs.getInt(KEY_NEEDS_ATTENTION, 0)
        val cta = prefs.getInt(KEY_CTA_FALSE, 0)

        val multiPct = 100f * multi / total
        val heuristicPct = 100f * heuristicOnly / total
        val needsPct = 100f * needsAttention / total

        Log.i(
            TAG,
            String.format(
                Locale.US,
                "store-metrics total=%d multi=%.1f%% heuristic_only=%.1f%% needs_attention=%.1f%% cta_false=%d",
                total,
                multiPct,
                heuristicPct,
                needsPct,
                cta
            )
        )
    }

    private fun enqueuePendingEvidence(source: String, assessment: StoreNameValidator.Assessment) {
        try {
            if (random.nextDouble() >= PROVENANCE_ENQUEUE_RATE) {
                return
            }
            val pendingRaw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val pendingArray = JSONArray(pendingRaw)
            val entry = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("source", source)
                put("original", assessment.original)
                put("canonical", assessment.canonical)
                put("normalized", assessment.normalized)
                put(
                    "signals",
                    JSONArray().apply {
                        assessment.signals.forEach { signal ->
                            put(
                                JSONObject().apply {
                                    put("category", signal.category)
                                    put("detail", signal.detail)
                                    put("tier", signal.tier)
                                }
                            )
                        }
                    }
                )
                put("issues", JSONArray(assessment.issues))
                put("needsAttention", assessment.needsAttention)
            }
            pendingArray.put(entry)

            val trimmed = JSONArray()
            val start = max(0, pendingArray.length() - MAX_PENDING)
            for (i in start until pendingArray.length()) {
                trimmed.put(pendingArray.get(i))
            }

            prefs.edit().putString(KEY_PENDING, trimmed.toString()).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to enqueue provenance evidence", t)
        }
    }

    fun scheduleEvidenceSampling(context: Context) {
        synchronized(lock) {
            if (jobScheduled) return
            val request = PeriodicWorkRequestBuilder<StoreNameEvidenceWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            jobScheduled = true
        }
    }

    fun samplePendingEvidence() {
        if (!initialized) return
        synchronized(lock) {
            val pendingRaw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
            val pendingArray = JSONArray(pendingRaw)
            if (pendingArray.length() == 0) {
                return
            }

            val indices = (0 until pendingArray.length()).shuffled(random)
            val selected = JSONArray()
            indices.forEach { index ->
                selected.put(pendingArray.get(index))
            }

            val existingRaw = securePrefs.getString(SECURE_KEY_PROVENANCE, "[]") ?: "[]"
            val secureArray = JSONArray(existingRaw)
            for (i in 0 until selected.length()) {
                secureArray.put(selected.get(i))
            }

            val trimmed = JSONArray()
            val start = max(0, secureArray.length() - MAX_SECURE)
            for (i in start until secureArray.length()) {
                trimmed.put(secureArray.get(i))
            }
            securePrefs.saveString(SECURE_KEY_PROVENANCE, trimmed.toString())

            prefs.edit().putString(KEY_PENDING, "[]").apply()

            Log.i(TAG, "Promoted ${selected.length()} provenance records into secure storage")
        }
    }
}

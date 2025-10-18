package com.example.coupontracker.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.repository.FeedbackDatasetRepository
import com.example.coupontracker.feedback.FeedbackFeatureToggle
import com.example.coupontracker.feedback.ValidatorFeedbackEvent
import com.example.coupontracker.util.ExtractionConfig
import com.example.coupontracker.util.SecurePreferencesManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class OfflineRetrainingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val feedbackDatasetRepository: FeedbackDatasetRepository,
    private val featureToggle: FeedbackFeatureToggle,
    private val securePreferencesManager: SecurePreferencesManager,
    private val gson: Gson
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "OfflineRetraining"
        private const val PREF_KEY_SUMMARY = "offline_retraining_summary"
        private const val UNIQUE_WORK_NAME = "offline_retraining_job"

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<OfflineRetrainingWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!featureToggle.isOfflineRetrainingEnabled()) {
            Log.d(TAG, "Offline retraining disabled via feature flag")
            return Result.success()
        }

        return try {
            val records = feedbackDatasetRepository.getRecentEvents(
                ValidatorFeedbackEvent.EventType.USER_CORRECTION,
                limit = 200
            )

            if (records.isEmpty()) {
                Log.d(TAG, "No user corrections available for offline retraining")
                return Result.success()
            }

            val type = object : TypeToken<List<ValidatorFeedbackEvent.FieldOutcome>>() {}.type
            val correctionCounts = mutableMapOf<FieldType, Int>()
            val violationCounts = mutableMapOf<String, Int>()

            records.forEach { record ->
                val outcomes: List<ValidatorFeedbackEvent.FieldOutcome> = gson.fromJson(
                    record.fieldOutcomesJson,
                    type
                )
                outcomes.forEach { outcome ->
                    correctionCounts[outcome.field] = correctionCounts.getOrDefault(outcome.field, 0) + 1
                    outcome.ruleViolations.forEach { violation ->
                        violationCounts[violation] = violationCounts.getOrDefault(violation, 0) + 1
                    }
                }
            }

            val adjustments = adjustThresholds(correctionCounts)

            val summary = mapOf(
                "timestamp" to System.currentTimeMillis(),
                "corrections" to correctionCounts.mapKeys { it.key.name.lowercase() },
                "violations" to violationCounts,
                "thresholdAdjustments" to adjustments
            )

            securePreferencesManager.saveString(PREF_KEY_SUMMARY, gson.toJson(summary))
            Log.i(TAG, "Offline retraining summary generated: $summary")

            feedbackDatasetRepository.purgeOlderThan(days = 45)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Offline retraining failed", e)
            Result.retry()
        }
    }

    private fun adjustThresholds(correctionCounts: Map<FieldType, Int>): Map<String, Float> {
        val adjustments = mutableMapOf<String, Float>()

        correctionCounts.forEach { (field, count) ->
            if (count < 5) return@forEach
            val adjustment = (count / 25f).coerceAtMost(0.15f)
            when (field) {
                FieldType.STORE_NAME -> {
                    val newValue = (ExtractionConfig.Thresholds.storeName + adjustment).coerceAtMost(0.9f)
                    if (newValue != ExtractionConfig.Thresholds.storeName) {
                        ExtractionConfig.Thresholds.storeName = newValue
                        adjustments["storeName"] = newValue
                    }
                }
                FieldType.COUPON_CODE -> {
                    val newValue = (ExtractionConfig.Thresholds.code + adjustment).coerceAtMost(0.95f)
                    if (newValue != ExtractionConfig.Thresholds.code) {
                        ExtractionConfig.Thresholds.code = newValue
                        adjustments["code"] = newValue
                    }
                }
                FieldType.EXPIRY_DATE -> {
                    val newValue = (ExtractionConfig.Thresholds.expiry + adjustment).coerceAtMost(0.9f)
                    if (newValue != ExtractionConfig.Thresholds.expiry) {
                        ExtractionConfig.Thresholds.expiry = newValue
                        adjustments["expiry"] = newValue
                    }
                }
                FieldType.AMOUNT -> {
                    val newValue = (ExtractionConfig.Thresholds.cashback + adjustment).coerceAtMost(0.9f)
                    if (newValue != ExtractionConfig.Thresholds.cashback) {
                        ExtractionConfig.Thresholds.cashback = newValue
                        adjustments["cashback"] = newValue
                    }
                }
                else -> Unit
            }
        }

        return adjustments
    }
}

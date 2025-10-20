package com.example.coupontracker.data.repository

import android.util.Log
import com.example.coupontracker.data.local.ValidatorFeedbackDao
import com.example.coupontracker.data.local.ValidatorFeedbackRecord
import com.example.coupontracker.feedback.FeedbackFeatureToggle
import com.example.coupontracker.feedback.OcrAnonymizer
import com.example.coupontracker.feedback.ValidatorFeedbackEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

interface FeedbackDatasetRepository {
    fun recordEvent(event: ValidatorFeedbackEvent)
    suspend fun getRecentEvents(
        eventType: ValidatorFeedbackEvent.EventType,
        limit: Int
    ): List<ValidatorFeedbackRecord>
    suspend fun purgeOlderThan(days: Int): Int
}

@Singleton
class FeedbackDatasetRepositoryImpl @Inject constructor(
    private val validatorFeedbackDao: ValidatorFeedbackDao,
    private val featureToggle: FeedbackFeatureToggle,
    private val gson: Gson
) : FeedbackDatasetRepository {

    companion object {
        private const val TAG = "FeedbackDatasetRepo"
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun recordEvent(event: ValidatorFeedbackEvent) {
        val featureEnabled = featureToggle.isValidationDatasetEnabled()
        if (!featureEnabled) {
            Log.d(TAG, "Validation dataset capture disabled - skipping ${event.eventType}")
            return
        }

        scope.launch {
            try {
                val record = ValidatorFeedbackRecord(
                    eventType = event.eventType.name.lowercase(),
                    fieldOutcomesJson = gson.toJson(event.fieldOutcomes),
                    rationaleJson = gson.toJson(event.rationale),
                    metadataJson = gson.toJson(event.metadata),
                    ocrHash = OcrAnonymizer.sha256(event.ocrText),
                    ocrPreview = OcrAnonymizer.preview(event.ocrText)
                )
                validatorFeedbackDao.insert(record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist feedback event", e)
            }
        }
    }

    override suspend fun getRecentEvents(
        eventType: ValidatorFeedbackEvent.EventType,
        limit: Int
    ): List<ValidatorFeedbackRecord> = withContext(Dispatchers.IO) {
        validatorFeedbackDao.getRecent(eventType.name.lowercase(), limit)
    }

    override suspend fun purgeOlderThan(days: Int): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - (days * MILLIS_PER_DAY)
        validatorFeedbackDao.purgeOlderThan(cutoff)
    }
}

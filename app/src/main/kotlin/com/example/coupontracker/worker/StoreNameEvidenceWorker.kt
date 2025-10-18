package com.example.coupontracker.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.coupontracker.analytics.StoreNameMetricsTracker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class StoreNameEvidenceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            StoreNameMetricsTracker.initialize(applicationContext)
            StoreNameMetricsTracker.samplePendingEvidence()
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to sample provenance evidence", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "StoreNameEvidenceWkr"
    }
}

package com.example.coupontracker.feedback

import com.example.coupontracker.util.SecurePreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature flag access for feedback-driven learning flows.
 */
@Singleton
class FeedbackFeatureToggle @Inject constructor(
    private val securePreferencesManager: SecurePreferencesManager
) {
    companion object {
        const val KEY_CAPTURE_VALIDATION_DATA = "feature_capture_validation_feedback"
        const val KEY_ENABLE_OFFLINE_RETRAINING = "feature_enable_offline_retraining"
    }

    fun isValidationDatasetEnabled(): Boolean {
        return securePreferencesManager.getBoolean(KEY_CAPTURE_VALIDATION_DATA, true)
    }

    fun isOfflineRetrainingEnabled(): Boolean {
        return securePreferencesManager.getBoolean(KEY_ENABLE_OFFLINE_RETRAINING, false)
    }

    fun setValidationDatasetEnabled(enabled: Boolean) {
        securePreferencesManager.saveBoolean(KEY_CAPTURE_VALIDATION_DATA, enabled)
    }

    fun setOfflineRetrainingEnabled(enabled: Boolean) {
        securePreferencesManager.saveBoolean(KEY_ENABLE_OFFLINE_RETRAINING, enabled)
    }
}

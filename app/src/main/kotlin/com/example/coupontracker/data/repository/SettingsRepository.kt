package com.example.coupontracker.data.repository

import android.content.Context
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.util.ModelMetadataReader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val modelMetadataReader: ModelMetadataReader
) {

    private val prefs = context.getSharedPreferences("coupon_settings", Context.MODE_PRIVATE)
    private val _settings: MutableStateFlow<Settings>

    init {
        // Read model version from metadata
        val (modelVersion, numPatterns) = modelMetadataReader.getModelVersion()
        _settings = MutableStateFlow(
            Settings(
                sortOrder = runCatching {
                    SortOrder.valueOf(prefs.getString(KEY_SORT_ORDER, SortOrder.EXPIRY_DATE.name) ?: SortOrder.EXPIRY_DATE.name)
                }.getOrDefault(SortOrder.EXPIRY_DATE),
                notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
                notificationTiming = prefs.getInt(KEY_NOTIFICATION_TIMING, 1),
                darkMode = prefs.getBoolean(KEY_DARK_MODE, false),
                modelVersion = modelVersion,
                numPatterns = numPatterns
            )
        )
    }

    fun getSettings(): Flow<Settings> = _settings

    suspend fun updateSortOrder(sortOrder: SortOrder) {
        prefs.edit().putString(KEY_SORT_ORDER, sortOrder.name).apply()
        _settings.value = _settings.value.copy(sortOrder = sortOrder)
    }

    suspend fun updateNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(notificationsEnabled = enabled)
    }

    suspend fun updateNotificationTiming(days: Int) {
        prefs.edit().putInt(KEY_NOTIFICATION_TIMING, days).apply()
        _settings.value = _settings.value.copy(notificationTiming = days)
    }

    suspend fun updateDarkMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply()
        _settings.value = _settings.value.copy(darkMode = enabled)
    }

    companion object {
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_NOTIFICATION_TIMING = "notification_timing"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}

package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSettings(settings: Settings) {
        prefs.edit()
            .putBoolean(KEY_NOTIFICATIONS_ENABLED, settings.notificationsEnabled)
            .putInt(KEY_NOTIFICATION_TIMING, settings.notificationTiming)
            .putString(KEY_SORT_ORDER, settings.sortOrder.name)
            .putBoolean(KEY_DARK_MODE, settings.darkMode)
            .apply()
    }

    fun loadSettings(): Settings {
        return Settings(
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, true),
            notificationTiming = prefs.getInt(KEY_NOTIFICATION_TIMING, 1),
            sortOrder = SortOrder.valueOf(
                prefs.getString(KEY_SORT_ORDER, SortOrder.EXPIRY_DATE.name) ?: SortOrder.EXPIRY_DATE.name
            ),
            darkMode = prefs.getBoolean(KEY_DARK_MODE, false)
        )
    }

    companion object {
        private const val PREFS_NAME = "coupon_tracker_prefs"
        private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        private const val KEY_NOTIFICATION_TIMING = "notification_timing"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_DARK_MODE = "dark_mode"
    }
} 
package com.example.coupontracker.data.repository

import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor() {
    
    private val _settings = MutableStateFlow(Settings())
    
    fun getSettings(): Flow<Settings> = _settings
    
    suspend fun updateSortOrder(sortOrder: SortOrder) {
        _settings.value = _settings.value.copy(sortOrder = sortOrder)
    }
    
    suspend fun updateNotificationsEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(notificationsEnabled = enabled)
    }
    
    suspend fun updateNotificationTiming(days: Int) {
        _settings.value = _settings.value.copy(notificationTiming = days)
    }
    
    suspend fun updateDarkMode(enabled: Boolean) {
        _settings.value = _settings.value.copy(darkMode = enabled)
    }
} 
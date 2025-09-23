package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import com.example.coupontracker.util.ThemeManager
import com.example.coupontracker.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val couponRepository: CouponRepository,
    private val themeManager: ThemeManager
) : ViewModel() {

    val settings: StateFlow<Settings> = repository.getSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = Settings()
        )

    /**
     * Current theme mode as a StateFlow
     */
    val themeMode: StateFlow<ThemeMode> = themeManager.themeMode
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = themeManager.getCurrentThemeMode()
        )

    fun updateSortOrder(sortOrder: SortOrder) {
        viewModelScope.launch {
            repository.updateSortOrder(sortOrder)
        }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateNotificationsEnabled(enabled)
        }
    }

    fun updateDarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateDarkMode(enabled)
            // Also update the theme mode
            themeManager.setThemeMode(if (enabled) ThemeMode.DARK else ThemeMode.LIGHT)
        }
    }

    /**
     * Set the theme mode
     */
    fun setThemeMode(mode: ThemeMode) {
        themeManager.setThemeMode(mode)
        // Also update the legacy dark mode setting for backward compatibility
        viewModelScope.launch {
            repository.updateDarkMode(mode == ThemeMode.DARK)
        }
    }

    fun exportData() {
        viewModelScope.launch {
            // Implementation for exporting data
            // This would typically involve writing to a file
        }
    }

    fun importData() {
        viewModelScope.launch {
            // Implementation for importing data
            // This would typically involve reading from a file
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            couponRepository.deleteAllCoupons()
        }
    }
}
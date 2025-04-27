package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val couponRepository: CouponRepository
) : ViewModel() {

    val settings: StateFlow<Settings> = repository.getSettings()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = Settings()
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
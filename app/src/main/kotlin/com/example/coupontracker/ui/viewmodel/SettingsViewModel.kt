package com.example.coupontracker.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import com.example.coupontracker.util.SecureBackupManager
import com.example.coupontracker.util.ThemeManager
import com.example.coupontracker.util.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val couponRepository: CouponRepository,
    private val themeManager: ThemeManager,
    private val secureBackupManager: SecureBackupManager
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

    /**
     * Backup/restore state
     */
    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    sealed class BackupState {
        object Idle : BackupState()
        object Exporting : BackupState()
        object Importing : BackupState()
        data class Success(val message: String, val count: Int = 0) : BackupState()
        data class Error(val message: String) : BackupState()
    }

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

    /**
     * Export all coupons to encrypted backup file
     */
    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.Exporting
            
            try {
                // Get all coupons
                val coupons = couponRepository.getAllCoupons().first()
                
                // Export using secure backup manager
                when (val result = secureBackupManager.exportSecureBackup(uri, coupons)) {
                    is SecureBackupManager.BackupResult.Success -> {
                        _backupState.value = BackupState.Success(result.message, result.count)
                    }
                    is SecureBackupManager.BackupResult.Error -> {
                        _backupState.value = BackupState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                _backupState.value = BackupState.Error("Export failed: ${e.message}")
            }
        }
    }

    /**
     * Import coupons from encrypted backup file
     */
    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.Importing
            
            try {
                // Read coupons from backup
                val coupons = secureBackupManager.readCouponsFromBackup(uri)
                
                if (coupons.isEmpty()) {
                    _backupState.value = BackupState.Error("Backup file is empty or invalid")
                    return@launch
                }
                
                // Import coupons (replace existing data)
                var successCount = 0
                coupons.forEach { coupon ->
                    try {
                        couponRepository.insertCoupon(coupon)
                        successCount++
                    } catch (e: Exception) {
                        // Continue with other coupons even if one fails
                    }
                }
                
                _backupState.value = BackupState.Success(
                    "Imported successfully",
                    successCount
                )
            } catch (e: Exception) {
                _backupState.value = BackupState.Error("Import failed: ${e.message}")
            }
        }
    }

    /**
     * Reset backup state to idle
     */
    fun resetBackupState() {
        _backupState.value = BackupState.Idle
    }

    fun clearAllData() {
        viewModelScope.launch {
            couponRepository.deleteAllCoupons()
        }
    }
}
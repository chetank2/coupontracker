package com.example.coupontracker.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Settings
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import com.example.coupontracker.data.util.CouponDedupUtils
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

    private val _cleanupState = MutableStateFlow<CleanupState>(CleanupState.Idle)
    val cleanupState: StateFlow<CleanupState> = _cleanupState.asStateFlow()

    sealed class BackupState {
        object Idle : BackupState()
        object Exporting : BackupState()
        object Importing : BackupState()
        data class Success(val message: String, val count: Int = 0) : BackupState()
        data class Error(val message: String) : BackupState()
    }

    sealed class CleanupState {
        object Idle : CleanupState()
        object Running : CleanupState()
        data class Success(val removedCount: Int) : CleanupState()
        data class Error(val message: String) : CleanupState()
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

    fun cleanupDuplicateCoupons() {
        viewModelScope.launch {
            _cleanupState.value = CleanupState.Running
            try {
                val coupons = couponRepository.getAllCoupons().first()
                val duplicates = coupons
                    .groupBy { coupon -> duplicateKey(coupon) }
                    .filterKeys { it != null }
                    .values
                    .flatMap { group ->
                        if (group.size <= 1) {
                            emptyList()
                        } else {
                            group.sortedWith(
                                compareByDescending<com.example.coupontracker.data.model.Coupon> { it.updatedAt }
                                    .thenByDescending { it.id }
                            ).drop(1)
                        }
                    }

                duplicates.forEach { couponRepository.deleteCoupon(it) }
                _cleanupState.value = CleanupState.Success(duplicates.size)
            } catch (e: Exception) {
                _cleanupState.value = CleanupState.Error(e.message ?: "Duplicate cleanup failed")
            }
        }
    }

    private fun duplicateKey(coupon: com.example.coupontracker.data.model.Coupon): String? {
        val code = coupon.redeemCode
            ?.trim()
            ?.uppercase()
            ?.takeIf { it.length >= 6 && it.any(Char::isDigit) && it.any(Char::isLetter) }
        if (code != null) return "code:$code"

        val normalizedDescription = coupon.normalizedDescription
            ?: CouponDedupUtils.normalizeDescription(coupon.description)
        val store = coupon.storeName.trim().lowercase()
        if (store.isBlank() || normalizedDescription.length < 12) return null
        return "store:$store|desc:$normalizedDescription"
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
                
                // Import coupons atomically (replace existing data)
                val insertedCount = couponRepository.replaceAllCoupons(coupons)

                _backupState.value = BackupState.Success(
                    "Imported successfully",
                    insertedCount
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

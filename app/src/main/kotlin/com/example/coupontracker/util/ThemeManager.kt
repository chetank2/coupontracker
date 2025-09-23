package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Theme mode options for the app
 */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

/**
 * Manages theme preferences for the app
 */
@Singleton
class ThemeManager @Inject constructor(
    private val sharedPreferences: SharedPreferences
) {
    companion object {
        private const val PREF_THEME_MODE = "theme_mode"
        private const val DEFAULT_THEME_MODE = "system"
    }

    private val _themeMode = MutableStateFlow(getCurrentThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    /**
     * Get the current theme mode from preferences
     */
    fun getCurrentThemeMode(): ThemeMode {
        val themeModeString = sharedPreferences.getString(PREF_THEME_MODE, DEFAULT_THEME_MODE)
        return when (themeModeString) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    /**
     * Set the theme mode and save to preferences
     */
    fun setThemeMode(mode: ThemeMode) {
        val modeString = when (mode) {
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
            ThemeMode.SYSTEM -> "system"
        }
        
        sharedPreferences.edit().putString(PREF_THEME_MODE, modeString).apply()
        _themeMode.value = mode
    }
}

/**
 * Composable function to remember the current theme mode
 */
@Composable
fun rememberThemeMode(themeManager: ThemeManager): State<ThemeMode> {
    val themeMode = remember { mutableStateOf(themeManager.getCurrentThemeMode()) }
    return themeMode
}

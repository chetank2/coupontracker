package com.example.coupontracker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.coupontracker.util.ThemeMode

private val BrandLightColors = lightColorScheme(
    primary = BrandColors.Accent,
    onPrimary = BrandColors.OnAccent,
    primaryContainer = BrandColors.Accent.copy(alpha = 0.16f),
    onPrimaryContainer = BrandColors.OnAccent,
    secondary = BrandColors.SecondaryButton,
    onSecondary = BrandColors.OnSecondaryButton,
    secondaryContainer = BrandColors.SecondaryButton.copy(alpha = 0.2f),
    onSecondaryContainer = BrandColors.OnSecondaryButton,
    tertiary = BrandColors.Primary,
    onTertiary = BrandColors.OnPrimary,
    error = BrandColors.Error,
    onError = Color.White,
    background = BrandColors.Background,
    onBackground = BrandColors.OnBackground,
    surface = BrandColors.Surface,
    onSurface = BrandColors.OnSurface,
    surfaceVariant = BrandColors.SurfaceVariant,
    onSurfaceVariant = BrandColors.OnSurfaceVariant,
    outline = BrandColors.Stroke,
    outlineVariant = BrandColors.Divider
)

private val BrandDarkColors = darkColorScheme(
    primary = BrandColors.Accent,
    onPrimary = BrandColors.OnAccent,
    primaryContainer = BrandColors.Accent.copy(alpha = 0.18f),
    onPrimaryContainer = BrandColors.OnAccent,
    secondary = BrandColors.SecondaryButton,
    onSecondary = BrandColors.OnSecondaryButton,
    secondaryContainer = BrandColors.SecondaryButton.copy(alpha = 0.3f),
    onSecondaryContainer = BrandColors.OnSecondaryButton,
    tertiary = BrandColors.SurfaceElevated,
    onTertiary = BrandColors.OnSurface,
    error = BrandColors.Error,
    onError = Color.White,
    background = BrandColors.Background,
    onBackground = BrandColors.OnBackground,
    surface = BrandColors.Surface,
    onSurface = BrandColors.OnSurface,
    surfaceVariant = BrandColors.SurfaceVariant,
    onSurfaceVariant = BrandColors.OnSurfaceVariant,
    outline = BrandColors.Stroke,
    outlineVariant = BrandColors.Divider
)

@Composable
fun CouponTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled to ensure our black and grayscale theme is used
    dynamicColor: Boolean = false,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    // Determine if dark theme should be used based on the theme mode
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> darkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> BrandDarkColors
        else -> BrandLightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
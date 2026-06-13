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
    primary = BrandColors.Light.Accent,
    onPrimary = BrandColors.Light.OnAccent,
    primaryContainer = BrandColors.Light.Accent.copy(alpha = 0.16f),
    onPrimaryContainer = BrandColors.Light.OnAccent,
    secondary = BrandColors.Light.SecondaryButton,
    onSecondary = BrandColors.Light.OnSecondaryButton,
    secondaryContainer = BrandColors.Light.SecondaryButton.copy(alpha = 0.2f),
    onSecondaryContainer = BrandColors.Light.OnSecondaryButton,
    tertiary = BrandColors.Light.Primary,
    onTertiary = BrandColors.Light.OnPrimary,
    error = BrandColors.Light.Error,
    onError = Color.White,
    background = BrandColors.Light.Background,
    onBackground = BrandColors.Light.OnBackground,
    surface = BrandColors.Light.Surface,
    onSurface = BrandColors.Light.OnSurface,
    surfaceVariant = BrandColors.Light.SurfaceVariant,
    onSurfaceVariant = BrandColors.Light.OnSurfaceVariant,
    outline = BrandColors.Light.Stroke,
    outlineVariant = BrandColors.Light.Divider
)

private val BrandDarkColors = darkColorScheme(
    primary = BrandColors.Dark.Accent,
    onPrimary = BrandColors.Dark.OnAccent,
    primaryContainer = BrandColors.Dark.Accent.copy(alpha = 0.18f),
    onPrimaryContainer = BrandColors.Dark.OnAccent,
    secondary = BrandColors.Dark.SecondaryButton,
    onSecondary = BrandColors.Dark.OnSecondaryButton,
    secondaryContainer = BrandColors.Dark.SecondaryButton.copy(alpha = 0.3f),
    onSecondaryContainer = BrandColors.Dark.OnSecondaryButton,
    tertiary = BrandColors.Dark.SurfaceElevated,
    onTertiary = BrandColors.Dark.OnSurface,
    error = BrandColors.Dark.Error,
    onError = Color.White,
    background = BrandColors.Dark.Background,
    onBackground = BrandColors.Dark.OnBackground,
    surface = BrandColors.Dark.Surface,
    onSurface = BrandColors.Dark.OnSurface,
    surfaceVariant = BrandColors.Dark.SurfaceVariant,
    onSurfaceVariant = BrandColors.Dark.OnSurfaceVariant,
    outline = BrandColors.Dark.Stroke,
    outlineVariant = BrandColors.Dark.Divider
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
            window.statusBarColor = colorScheme.background.toArgb()
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

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

private val LightColors = lightColorScheme(
    primary = BrandColors.Primary,
    onPrimary = BrandColors.OnPrimary,
    primaryContainer = BrandColors.Primary.copy(alpha = 0.12f),
    onPrimaryContainer = BrandColors.Primary,
    secondary = BrandColors.Secondary,
    onSecondary = BrandColors.OnSecondary,
    secondaryContainer = BrandColors.Secondary.copy(alpha = 0.12f),
    onSecondaryContainer = BrandColors.Secondary,
    tertiary = BrandColors.Accent,
    onTertiary = BrandColors.OnAccent,
    tertiaryContainer = BrandColors.Accent.copy(alpha = 0.12f),
    onTertiaryContainer = BrandColors.Accent,
    error = BrandColors.Error,
    onError = Color.White,
    errorContainer = BrandColors.Error.copy(alpha = 0.12f),
    onErrorContainer = BrandColors.Error,
    background = BrandColors.Background,
    onBackground = BrandColors.OnBackground,
    surface = BrandColors.Surface,
    onSurface = BrandColors.OnSurface,
    surfaceVariant = BrandColors.SurfaceVariant,
    onSurfaceVariant = BrandColors.OnSurfaceVariant,
    outline = BrandColors.CardStroke
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF333333),  // Dark gray for dark theme
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF444444),
    onPrimaryContainer = Color(0xFFEEEEEE),
    secondary = Color(0xFF777777),  // Medium gray for dark theme
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF555555),
    onSecondaryContainer = Color(0xFFEEEEEE),
    tertiary = Color(0xFF999999),  // Light gray for dark theme
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF777777),
    onTertiaryContainer = Color(0xFFEEEEEE),
    error = Color(0xFFEF5350),  // Keeping red for error states
    onError = Color(0xFF000000),
    errorContainer = Color(0xFFB00020),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE1E1E1),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF3E3E3E)
)

@Composable
fun CouponTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled to ensure our black and grayscale theme is used
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
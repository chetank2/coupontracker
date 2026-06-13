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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.example.coupontracker.util.ThemeMode

private val BrandDarkColors = darkColorScheme(
    primary             = BrandColors.Dark.Accent,
    onPrimary           = BrandColors.Dark.OnAccent,
    primaryContainer    = BrandColors.Dark.AccentContainer,
    onPrimaryContainer  = BrandColors.Dark.OnAccent,
    secondary           = BrandColors.Dark.SurfaceVariant,
    onSecondary         = BrandColors.Dark.OnSurface,
    secondaryContainer  = BrandColors.Dark.SurfaceVariant,
    onSecondaryContainer = BrandColors.Dark.OnSurface,
    tertiary            = BrandColors.Dark.SurfaceElevated,
    onTertiary          = BrandColors.Dark.OnSurface,
    error               = BrandColors.Dark.Error,
    onError             = BrandColors.Dark.OnBackground,
    background          = BrandColors.Dark.Background,
    onBackground        = BrandColors.Dark.OnBackground,
    surface             = BrandColors.Dark.Surface,
    onSurface           = BrandColors.Dark.OnSurface,
    surfaceVariant      = BrandColors.Dark.SurfaceVariant,
    onSurfaceVariant    = BrandColors.Dark.OnSurfaceVariant,
    outline             = BrandColors.Dark.Stroke,
    outlineVariant      = BrandColors.Dark.Divider,
)

private val BrandLightColors = lightColorScheme(
    primary             = BrandColors.Light.Accent,
    onPrimary           = BrandColors.Light.OnAccent,
    primaryContainer    = BrandColors.Light.AccentContainer,
    onPrimaryContainer  = BrandColors.Light.OnAccent,
    secondary           = BrandColors.Light.SurfaceVariant,
    onSecondary         = BrandColors.Light.OnSurface,
    secondaryContainer  = BrandColors.Light.SurfaceVariant,
    onSecondaryContainer = BrandColors.Light.OnSurface,
    tertiary            = BrandColors.Light.SurfaceElevated,
    onTertiary          = BrandColors.Light.OnSurface,
    error               = BrandColors.Light.Error,
    onError             = BrandColors.Light.OnBackground,
    background          = BrandColors.Light.Background,
    onBackground        = BrandColors.Light.OnBackground,
    surface             = BrandColors.Light.Surface,
    onSurface           = BrandColors.Light.OnSurface,
    surfaceVariant      = BrandColors.Light.SurfaceVariant,
    onSurfaceVariant    = BrandColors.Light.OnSurfaceVariant,
    outline             = BrandColors.Light.Stroke,
    outlineVariant      = BrandColors.Light.Divider,
)

@Composable
fun CouponTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT  -> false
        ThemeMode.DARK   -> true
        ThemeMode.SYSTEM -> darkTheme
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (useDarkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        useDarkTheme -> BrandDarkColors
        else         -> BrandLightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        shapes      = Shapes,
        content     = content,
    )
}

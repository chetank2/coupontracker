package com.example.coupontracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CouponTracker Brand Style Guide
 *
 * This file defines the brand identity elements for the CouponTracker app,
 * including colors, typography, shapes, and spacing.
 */

/**
 * Brand Colors
 */
object BrandColors {
    data class Palette(
        val Primary: Color,
        val PrimaryVariant: Color,
        val OnPrimary: Color,
        val Accent: Color,
        val AccentVariant: Color,
        val OnAccent: Color,
        val Background: Color,
        val Surface: Color,
        val SurfaceVariant: Color,
        val SurfaceElevated: Color,
        val OnBackground: Color,
        val OnSurface: Color,
        val OnSurfaceVariant: Color,
        val Muted: Color,
        val Success: Color,
        val Warning: Color,
        val Error: Color,
        val Info: Color,
        val PrimaryButton: Color,
        val SecondaryButton: Color,
        val OnSecondaryButton: Color,
        val TertiaryButton: Color,
        val OnTertiaryButton: Color,
        val Divider: Color,
        val Stroke: Color,
        val Highlight: Color,
    )

    val Dark = Palette(
        Primary = Color(0xFF2563EB),
        PrimaryVariant = Color(0xFF1D4ED8),
        OnPrimary = Color(0xFFFFFFFF),
        Accent = Color(0xFF2563EB),
        AccentVariant = Color(0xFF1D4ED8),
        OnAccent = Color(0xFFFFFFFF),
        Background = Color(0xFF171717),
        Surface = Color(0xFF242424),
        SurfaceVariant = Color(0xFF2F2F2F),
        SurfaceElevated = Color(0xFF303030),
        OnBackground = Color(0xFFF5F5F5),
        OnSurface = Color(0xFFF5F5F5),
        OnSurfaceVariant = Color(0xFFC7C7C7),
        Muted = Color(0xFFA3A3A3),
        Success = Color(0xFF22C55E),
        Warning = Color(0xFFF59E0B),
        Error = Color(0xFFEF4444),
        Info = Color(0xFF60A5FA),
        PrimaryButton = Color(0xFF2563EB),
        SecondaryButton = Color(0xFF333333),
        OnSecondaryButton = Color(0xFFF5F5F5),
        TertiaryButton = Color.Transparent,
        OnTertiaryButton = Color(0xFF93C5FD),
        Divider = Color(0xFF404040),
        Stroke = Color(0xFF525252),
        Highlight = Color(0xFF1E3A8A),
    )

    val Light = Palette(
        Primary = Color(0xFF2563EB),
        PrimaryVariant = Color(0xFF1D4ED8),
        OnPrimary = Color(0xFFFFFFFF),
        Accent = Color(0xFF2563EB),
        AccentVariant = Color(0xFF1D4ED8),
        OnAccent = Color(0xFFFFFFFF),
        Background = Color(0xFFF7F7F7),
        Surface = Color(0xFFFFFFFF),
        SurfaceVariant = Color(0xFFFFFFFF),
        SurfaceElevated = Color(0xFFFFFFFF),
        OnBackground = Color(0xFF222222),
        OnSurface = Color(0xFF222222),
        OnSurfaceVariant = Color(0xFF717171),
        Muted = Color(0xFF717171),
        Success = Color(0xFF16A34A),
        Warning = Color(0xFFD97706),
        Error = Color(0xFFDC2626),
        Info = Color(0xFF2563EB),
        PrimaryButton = Color(0xFF2563EB),
        SecondaryButton = Color(0xFFFFFFFF),
        OnSecondaryButton = Color(0xFF222222),
        TertiaryButton = Color.Transparent,
        OnTertiaryButton = Color(0xFF2563EB),
        Divider = Color(0xFFDDDDDD),
        Stroke = Color(0xFFDDDDDD),
        Highlight = Color(0xFFEFF6FF),
    )

    // Legacy properties used throughout the UI default to the dark palette for backward compatibility.
    val Primary get() = Dark.Primary
    val PrimaryVariant get() = Dark.PrimaryVariant
    val OnPrimary get() = Dark.OnPrimary
    val Accent get() = Dark.Accent
    val AccentVariant get() = Dark.AccentVariant
    val OnAccent get() = Dark.OnAccent
    val Background get() = Dark.Background
    val Surface get() = Dark.Surface
    val SurfaceVariant get() = Dark.SurfaceVariant
    val SurfaceElevated get() = Dark.SurfaceElevated
    val OnBackground get() = Dark.OnBackground
    val OnSurface get() = Dark.OnSurface
    val OnSurfaceVariant get() = Dark.OnSurfaceVariant
    val Muted get() = Dark.Muted
    val Success get() = Dark.Success
    val Warning get() = Dark.Warning
    val Error get() = Dark.Error
    val Info get() = Dark.Info
    val PrimaryButton get() = Dark.PrimaryButton
    val SecondaryButton get() = Dark.SecondaryButton
    val OnSecondaryButton get() = Dark.OnSecondaryButton
    val TertiaryButton get() = Dark.TertiaryButton
    val OnTertiaryButton get() = Dark.OnTertiaryButton
    val Divider get() = Dark.Divider
    val Stroke get() = Dark.Stroke
    val Highlight get() = Dark.Highlight
}

/**
 * Brand Typography
 */
object BrandTypography {
    // Display styles
    val DisplayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    )

    val DisplayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    )

    val DisplaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    )

    // Headline styles
    val HeadlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    )

    val HeadlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    )

    val HeadlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    )

    // Title styles
    val TitleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    )

    val TitleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    val TitleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    // Body styles
    val BodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    val BodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    )

    val BodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )

    // Label styles
    val LabelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    )

    val LabelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp
    )

    val LabelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    )
}

/**
 * Brand Shapes
 */
object BrandShapes {
    // Corner shapes
    val SmallCornerShape = RoundedCornerShape(8.dp)
    val MediumCornerShape = RoundedCornerShape(12.dp)
    val LargeCornerShape = RoundedCornerShape(16.dp)
    val ExtraLargeCornerShape = RoundedCornerShape(16.dp)

    // Button shapes
    val ButtonShape = RoundedCornerShape(14.dp)
    val PillShape = RoundedCornerShape(50)

    // Card shapes
    val CardShape = RoundedCornerShape(16.dp)
    val CardShapeSmall = RoundedCornerShape(12.dp)

    // Input shapes
    val InputShape = RoundedCornerShape(12.dp)

    // Dialog shapes
    val DialogShape = RoundedCornerShape(16.dp)
}

/**
 * Brand Spacing
 */
object BrandSpacing {
    // Base spacing units
    val Micro = 2.dp
    val Tiny = 4.dp
    val ExtraSmall = 8.dp
    val Small = 12.dp
    val Medium = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
    val Huge = 48.dp
    val Giant = 64.dp

    // Specific spacing
    val ContentPadding = Large
    val CardPadding = Medium
    val ButtonPadding = Small
    val ListItemSpacing = ExtraSmall
    val SectionSpacing = Large

    // Grid
    val GridSpacing = ExtraSmall
}

/**
 * Brand Elevation
 */
object BrandElevation {
    val None = 0.dp
    val Tiny = 1.dp
    val Small = 2.dp
    val Medium = 4.dp
    val Large = 8.dp
    val ExtraLarge = 16.dp

    // Component-specific elevations
    val CardElevation = Small
    val FloatingActionButtonElevation = Medium
    val ModalElevation = Large
    val AppBarElevation = Small
}

/**
 * Brand Animation Durations
 */
object BrandAnimationDuration {
    const val VeryFast = 100
    const val Fast = 200
    const val Medium = 300
    const val Slow = 400
    const val VerySlow = 500
}

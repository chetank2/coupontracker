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
        Primary = Color(0xFF0F1115),
        PrimaryVariant = Color(0xFF1A1C21),
        OnPrimary = Color(0xFFE0E0E0),
        Accent = Color(0xFF2979FF),
        AccentVariant = Color(0xFF1E5ED0),
        OnAccent = Color(0xFFFFFFFF),
        Background = Color(0xFF0A0B0E),
        Surface = Color(0xFF121418),
        SurfaceVariant = Color(0xFF1C1F24),
        SurfaceElevated = Color(0xFF22262D),
        OnBackground = Color(0xFFE0E0E0),
        OnSurface = Color(0xFFDBE1F5),
        OnSurfaceVariant = Color(0xFFBDBDBD),
        Muted = Color(0xFF9AA0AD),
        Success = Color(0xFF4CAF50),
        Warning = Color(0xFFFF9800),
        Error = Color(0xFFF44336),
        Info = Color(0xFF00BCD4),
        PrimaryButton = Color(0xFF2979FF),
        SecondaryButton = Color(0xFF2F323A),
        OnSecondaryButton = Color(0xFFDBE1F5),
        TertiaryButton = Color.Transparent,
        OnTertiaryButton = Color(0xFF2979FF),
        Divider = Color(0xFF2C3038),
        Stroke = Color(0xFF2A2E36),
        Highlight = Color(0xFF1C263D),
    )

    val Light = Palette(
        Primary = Color(0xFF1976D2),
        PrimaryVariant = Color(0xFF1565C0),
        OnPrimary = Color(0xFFFFFFFF),
        Accent = Color(0xFF2979FF),
        AccentVariant = Color(0xFF1E5ED0),
        OnAccent = Color(0xFFFFFFFF),
        Background = Color(0xFFFAFAFA),
        Surface = Color(0xFFFFFFFF),
        SurfaceVariant = Color(0xFFF1F3F4),
        SurfaceElevated = Color(0xFFFFFFFF),
        OnBackground = Color(0xFF000000),
        OnSurface = Color(0xFF1F1F1F),
        OnSurfaceVariant = Color(0xFF5F6368),
        Muted = Color(0xFF757575),
        Success = Color(0xFF4CAF50),
        Warning = Color(0xFFFF9800),
        Error = Color(0xFFF44336),
        Info = Color(0xFF2196F3),
        PrimaryButton = Color(0xFF2979FF),
        SecondaryButton = Color(0xFFE0E0E0),
        OnSecondaryButton = Color(0xFF1F1F1F),
        TertiaryButton = Color.Transparent,
        OnTertiaryButton = Color(0xFF2979FF),
        Divider = Color(0xFFE0E0E0),
        Stroke = Color(0xFFD0D5DD),
        Highlight = Color(0xFFE3F2FD),
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
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    )

    val TitleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.1.sp
    )

    val TitleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.05.sp
    )

    // Body styles
    val BodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )

    val BodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )

    val BodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    )

    // Label styles
    val LabelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.05.sp
    )

    val LabelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    )

    val LabelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    )
}

/**
 * Brand Shapes
 */
object BrandShapes {
    // Corner shapes
    val SmallCornerShape = RoundedCornerShape(4.dp)
    val MediumCornerShape = RoundedCornerShape(8.dp)
    val LargeCornerShape = RoundedCornerShape(12.dp)
    val ExtraLargeCornerShape = RoundedCornerShape(16.dp)

    // Button shapes
    val ButtonShape = RoundedCornerShape(8.dp)
    val PillShape = RoundedCornerShape(50)

    // Card shapes
    val CardShape = RoundedCornerShape(12.dp)
    val CardShapeSmall = RoundedCornerShape(8.dp)

    // Input shapes
    val InputShape = RoundedCornerShape(8.dp)

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
    val ContentPadding = Medium
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

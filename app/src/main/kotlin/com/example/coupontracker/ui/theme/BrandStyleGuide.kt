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
    // Primary palette
    val Primary = Color(0xFF1E88E5)         // Vibrant blue - main brand color
    val PrimaryVariant = Color(0xFF1565C0)  // Darker blue for emphasis
    val OnPrimary = Color(0xFFFFFFFF)       // White text on primary
    
    // Secondary palette
    val Secondary = Color(0xFF26A69A)       // Teal - complementary to primary
    val SecondaryVariant = Color(0xFF00897B) // Darker teal
    val OnSecondary = Color(0xFFFFFFFF)     // White text on secondary
    
    // Accent colors
    val Accent = Color(0xFFFF6D00)          // Orange - for highlights and CTAs
    val AccentVariant = Color(0xFFE65100)   // Darker orange
    val OnAccent = Color(0xFFFFFFFF)        // White text on accent
    
    // Neutral palette
    val Background = Color(0xFFF5F7FA)      // Light gray with blue tint - background
    val Surface = Color(0xFFFFFFFF)         // White - surface
    val SurfaceVariant = Color(0xFFF0F4F8)  // Light blue-gray - alternative surface
    val OnBackground = Color(0xFF1A1A1A)    // Near black - text on background
    val OnSurface = Color(0xFF1A1A1A)       // Near black - text on surface
    val OnSurfaceVariant = Color(0xFF5F6368) // Medium gray - secondary text
    
    // Status colors
    val Success = Color(0xFF43A047)         // Green - success states
    val Error = Color(0xFFE53935)           // Red - error states
    val Warning = Color(0xFFFFA000)         // Amber - warning states
    val Info = Color(0xFF2196F3)            // Blue - information states
    
    // Expiration colors
    val Valid = Color(0xFF43A047)           // Green - valid coupons
    val ExpiringSoon = Color(0xFFFFA000)    // Amber - expiring soon
    val Expired = Color(0xFFE53935)         // Red - expired coupons
    
    // Card colors
    val CardBackground = Color(0xFFFFFFFF)  // White - card background
    val CardStroke = Color(0xFFE0E0E0)      // Light gray - card stroke
    val CardHighlight = Color(0xFFE3F2FD)   // Very light blue - highlighted card
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
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    )
    
    val TitleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    
    // Body styles
    val BodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    )
    
    val BodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    )
    
    val BodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
    
    // Label styles
    val LabelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    )
    
    val LabelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    
    val LabelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
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

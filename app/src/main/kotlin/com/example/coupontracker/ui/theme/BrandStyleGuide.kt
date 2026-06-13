package com.example.coupontracker.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupontracker.R

/**
 * Vault Brand Style Guide — see docs/superpowers/specs/2026-06-13-vault-redesign-design.md
 */
object BrandColors {
    data class Palette(
        val Background: Color,
        val Surface: Color,
        val SurfaceVariant: Color,
        val SurfaceElevated: Color,
        val OnBackground: Color,
        val OnSurface: Color,
        val OnSurfaceVariant: Color,
        val Muted: Color,
        val Stroke: Color,
        val Divider: Color,
        val Highlight: Color,
        val Accent: Color,
        val AccentVariant: Color,
        val OnAccent: Color,
        val AccentContainer: Color,
        val Success: Color,
        val Warning: Color,
        val Error: Color,
        val Info: Color,
    ) {
        // Legacy aliases for any consumer that still references "Primary".
        val Primary: Color get() = Accent
        val PrimaryVariant: Color get() = AccentVariant
        val OnPrimary: Color get() = OnAccent
        val PrimaryButton: Color get() = Accent
        val SecondaryButton: Color get() = SurfaceVariant
        val OnSecondaryButton: Color get() = OnSurface
        val TertiaryButton: Color get() = Color.Transparent
        val OnTertiaryButton: Color get() = Accent
    }

    val Dark = Palette(
        Background       = Color(0xFF0D0C10),
        Surface          = Color(0xFF16151B),
        SurfaceVariant   = Color(0xFF22212A),
        SurfaceElevated  = Color(0xFF1E1D24),
        OnBackground     = Color(0xFFF7F4EE),
        OnSurface        = Color(0xFFE6E2D9),
        OnSurfaceVariant = Color(0xFF9C988F),
        Muted            = Color(0xFF6B6859),
        Stroke           = Color(0xFF2A2832),
        Divider          = Color(0xFF1F1E27),
        Highlight        = Color(0xFF1A1922),
        Accent           = Color(0xFF00D69E),
        AccentVariant    = Color(0xFF00B488),
        OnAccent         = Color(0xFF0D0C10),
        AccentContainer  = Color(0xFF0E2A22),
        Success          = Color(0xFF00D69E),
        Warning          = Color(0xFFF2C84B),
        Error            = Color(0xFFFF4D6D),
        Info             = Color(0xFF7BB9F2),
    )

    val Light = Palette(
        Background       = Color(0xFFF2EDE4),
        Surface           = Color(0xFFFFFCF5),
        SurfaceVariant   = Color(0xFFEAE4D9),
        SurfaceElevated  = Color(0xFFFFFFFF),
        OnBackground     = Color(0xFF0D0C10),
        OnSurface        = Color(0xFF1B1A1F),
        OnSurfaceVariant = Color(0xFF6E6A60),
        Muted            = Color(0xFFA39E92),
        Stroke           = Color(0xFFD9D2C5),
        Divider          = Color(0xFFE6E1D5),
        Highlight        = Color(0xFFFAF6EC),
        Accent           = Color(0xFF00D69E),
        AccentVariant    = Color(0xFF00B488),
        OnAccent         = Color(0xFF0D0C10),
        AccentContainer  = Color(0xFFD6F5EA),
        Success          = Color(0xFF00D69E),
        Warning          = Color(0xFFD9A800),
        Error            = Color(0xFFDC3553),
        Info             = Color(0xFF3B82F6),
    )

    /** Sunset gradient — celebratory single-use only. */
    val SunsetGradient: Brush = Brush.linearGradient(
        0.0f to Color(0xFFF58529),
        0.5f to Color(0xFFDD2A7B),
        1.0f to Color(0xFF8134AF),
    )

    // Legacy top-level aliases default to Dark for any code that still
    // imports BrandColors.X directly without going through Dark/Light.
    val Background        get() = Dark.Background
    val Surface           get() = Dark.Surface
    val SurfaceVariant    get() = Dark.SurfaceVariant
    val SurfaceElevated   get() = Dark.SurfaceElevated
    val OnBackground      get() = Dark.OnBackground
    val OnSurface         get() = Dark.OnSurface
    val OnSurfaceVariant  get() = Dark.OnSurfaceVariant
    val Muted             get() = Dark.Muted
    val Stroke            get() = Dark.Stroke
    val Divider           get() = Dark.Divider
    val Highlight         get() = Dark.Highlight
    val Accent            get() = Dark.Accent
    val AccentVariant     get() = Dark.AccentVariant
    val OnAccent          get() = Dark.OnAccent
    val AccentContainer   get() = Dark.AccentContainer
    val Success           get() = Dark.Success
    val Warning           get() = Dark.Warning
    val Error             get() = Dark.Error
    val Info              get() = Dark.Info
    val Primary           get() = Dark.Accent
    val PrimaryVariant    get() = Dark.AccentVariant
    val OnPrimary         get() = Dark.OnAccent
    val PrimaryButton     get() = Dark.Accent
    val SecondaryButton   get() = Dark.SurfaceVariant
    val OnSecondaryButton get() = Dark.OnSurface
    val TertiaryButton    get() = Color.Transparent
    val OnTertiaryButton  get() = Dark.Accent
}

/**
 * Vault font families bundled in res/font.
 */
val DisplayFamily: FontFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val BodyFamily: FontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_variable, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_variable, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_variable, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_variable, FontWeight.Bold),
)

/** Apply tabular figures to any numeric body / title / label style. */
fun TextStyle.tabularNumerals(): TextStyle = copy(fontFeatureSettings = "tnum")

object BrandTypography {
    val DisplayHero = TextStyle(
        fontFamily = DisplayFamily,
        fontWeight = FontWeight.Normal,
        fontStyle = FontStyle.Italic,
        fontSize = 72.sp,
        lineHeight = 76.sp,
        letterSpacing = (-2.0).sp,
    )
    val DisplayLarge = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.5).sp,
    )
    val DisplayMedium = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-1.0).sp,
    )
    val DisplaySmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.75).sp,
    )
    val HeadlineLarge = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp,
    )
    val HeadlineMedium = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.25).sp,
    )
    val HeadlineSmall = TextStyle(
        fontFamily = DisplayFamily, fontWeight = FontWeight.Normal,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp,
    )
    val TitleLarge = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.1).sp,
    )
    val TitleMedium = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp,
    )
    val TitleSmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    )
    val BodyLarge = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp,
    )
    val BodyMedium = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.15.sp,
    )
    val BodySmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    )
    val LabelLarge = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp,
    )
    val LabelMedium = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp,
    )
    val LabelSmall = TextStyle(
        fontFamily = BodyFamily, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.6.sp,
    )
}

object BrandShapes {
    val Sharp        = RoundedCornerShape(0.dp)
    val ExtraSmall   = RoundedCornerShape(4.dp)
    val Small        = RoundedCornerShape(8.dp)
    val Medium       = RoundedCornerShape(12.dp)
    val Large        = RoundedCornerShape(16.dp)
    val XLarge       = RoundedCornerShape(20.dp)
    val CouponCard   = RoundedCornerShape(14.dp)
    val Pill         = RoundedCornerShape(percent = 50)

    // Backwards compatibility for existing consumers
    val SmallCornerShape      = Small
    val MediumCornerShape     = Medium
    val LargeCornerShape      = Large
    val ExtraLargeCornerShape = XLarge
    val ButtonShape           = Medium
    val PillShape             = Pill
    val CardShape             = Large
    val CardShapeSmall        = Medium
    val InputShape            = Medium
    val DialogShape           = Large
}

object BrandSpacing {
    val Micro      = 2.dp
    val Tiny       = 4.dp
    val ExtraSmall = 8.dp
    val Small      = 12.dp
    val Medium     = 16.dp
    val Large      = 24.dp
    val ExtraLarge = 32.dp
    val Huge       = 48.dp
    val Giant      = 64.dp

    val Hairline           = 1.dp
    val Hairline2          = 1.5.dp
    val ContentEdge        = 24.dp
    val WalletCardOverlap  = (-16).dp
    val WalletCardSpacing  = 24.dp
    val HeroSpacing        = 48.dp

    val ContentPadding   = Large
    val CardPadding      = 20.dp
    val ButtonPadding    = Small
    val ListItemSpacing  = ExtraSmall
    val SectionSpacing   = ExtraLarge
    val GridSpacing      = ExtraSmall
}

object BrandElevation {
    val None       = 0.dp
    val Subtle     = 1.dp
    val Lifted     = 2.dp
    val Hero       = 4.dp

    // Component-specific
    val CardElevation             = None
    val FabElevation              = Hero
    val FloatingActionButtonElevation = FabElevation
    val BottomSheetElevation      = Subtle
    val DialogElevation           = Lifted
    val ModalElevation            = DialogElevation
    val AppBarElevation           = None

    // Backwards compatibility
    val Tiny       = Subtle
    val Small      = Lifted
    val Medium     = Hero
    val Large      = 8.dp
    val ExtraLarge = 16.dp
}

object BrandAnimationDuration {
    const val VeryFast = 80
    const val Fast     = 180
    const val Medium   = 280
    const val Slow     = 420
    const val VerySlow = 600
}

object BrandEasing {
    val Standard   = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
}

object BrandSpring {
    val Press = spring<Float>(
        dampingRatio = 0.65f,
        stiffness = Spring.StiffnessMediumLow,
    )
    val Reveal = spring<IntOffset>(
        dampingRatio = 0.78f,
        stiffness = Spring.StiffnessLow,
    )
}

# Vault Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the CouponTracker Compose token system and every visible screen surface with the Vault aesthetic — CRED-led dark luxury, warm-bone light parity, Editorial Serif display + warm geometric sans body, one mint accent, sunset gradient reserved for celebratory moments, wallet-style coupon cards.

**Architecture:** Token-first refactor. Rewrite `BrandStyleGuide.kt` (colors, typography, shapes, spacing, elevation, motion, easing) and `Theme.kt` / `Type.kt` / `Shape.kt`. Build five new shared composables (`GlassSurface`, `BrandButton`, `BrandTextField`, `BrandTopBar`, `CouponCard`). Repaint each Compose screen using the new tokens and composables. Sync legacy XML resources to avoid drift on any remaining XML-rendered surfaces.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, minSdk 24 / compileSdk 36. Fonts: Instrument Serif + Plus Jakarta Sans (SIL OFL 1.1, bundled as `.ttf`).

**Spec:** `docs/superpowers/specs/2026-06-13-vault-redesign-design.md` — every dimension referenced here is defined there.

---

## Phase 0 — Token foundation (sequential, must land first)

Everything downstream depends on this. Run as one batched commit per task; no parallelization within Phase 0.

### Task 0.1: Acquire & wire fonts

**Files:**
- Create: `app/src/main/res/font/instrument_serif_regular.ttf` (binary, ~80 KB)
- Create: `app/src/main/res/font/instrument_serif_italic.ttf` (binary, ~80 KB)
- Create: `app/src/main/res/font/plus_jakarta_sans_variable.ttf` (binary, ~240 KB)
- Create: `app/src/main/res/font/instrument_serif.xml`
- Create: `app/src/main/res/font/plus_jakarta_sans.xml`
- Create: `app/src/main/res/drawable-nodpi/glass_noise.webp` (256×256, ~3 KB)
- Delete: `app/src/main/res/font/roboto_regular.ttf`
- Delete: `app/src/main/res/font/roboto.xml`
- Delete: `app/src/main/res/font/uber_move.xml`

- [ ] **Step 1: Download fonts**

User must download from Google Fonts and drop into `app/src/main/res/font/`:
- Instrument Serif Regular: <https://fonts.google.com/specimen/Instrument+Serif>
- Instrument Serif Italic: same family page
- Plus Jakarta Sans Variable: <https://fonts.google.com/specimen/Plus+Jakarta+Sans>

If binaries are not available at execution time, **the implementing agent must stop and surface this dependency to the user.** Do not synthesise placeholders or use Roboto as a fallback in committed code.

- [ ] **Step 2: Create `instrument_serif.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:app="http://schemas.android.com/apk/res-auto">
    <font app:fontStyle="normal" app:fontWeight="400" app:font="@font/instrument_serif_regular" />
    <font app:fontStyle="italic" app:fontWeight="400" app:font="@font/instrument_serif_italic" />
</font-family>
```

- [ ] **Step 3: Create `plus_jakarta_sans.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:app="http://schemas.android.com/apk/res-auto">
    <font app:fontStyle="normal" app:fontWeight="400" app:font="@font/plus_jakarta_sans_variable" />
    <font app:fontStyle="normal" app:fontWeight="500" app:font="@font/plus_jakarta_sans_variable" />
    <font app:fontStyle="normal" app:fontWeight="600" app:font="@font/plus_jakarta_sans_variable" />
    <font app:fontStyle="normal" app:fontWeight="700" app:font="@font/plus_jakarta_sans_variable" />
</font-family>
```

(Compose `Font(...)` declarations with `FontVariation` settings will drive the actual weight axis at runtime.)

- [ ] **Step 4: Create the glass noise asset**

A 256×256 WebP with ~6% alpha dithered grain. Easiest path: generate via Python + Pillow:

```python
from PIL import Image
import numpy as np
np.random.seed(0)
arr = np.zeros((256, 256, 4), dtype=np.uint8)
arr[..., :3] = 255
arr[..., 3] = (np.random.rand(256, 256) * 32).astype(np.uint8)
Image.fromarray(arr, mode="RGBA").save(
    "app/src/main/res/drawable-nodpi/glass_noise.webp", format="WEBP", quality=80
)
```

- [ ] **Step 5: Delete legacy fonts**

```bash
git rm app/src/main/res/font/roboto_regular.ttf \
       app/src/main/res/font/roboto.xml \
       app/src/main/res/font/uber_move.xml
```

- [ ] **Step 6: Verify nothing in the codebase references the deleted files**

Run:
```bash
grep -rn "uber_move\|@font/roboto\|R\.font\.roboto" app/src/main/ \
  --include="*.kt" --include="*.xml"
```
Expected: zero matches. If matches exist, replace with `R.font.plus_jakarta_sans_variable` for body uses or `R.font.instrument_serif_regular` for headings.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/font/ app/src/main/res/drawable-nodpi/glass_noise.webp
git commit -m "feat(vault): bundle Instrument Serif + Plus Jakarta Sans, drop legacy fonts"
```

---

### Task 0.2: Rewrite `BrandStyleGuide.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt` (full rewrite)

- [ ] **Step 1: Replace `BrandColors` with new palettes**

The new file has the following structure (full content; paste verbatim into the existing file, replacing the entire content):

```kotlin
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
import androidx.compose.ui.text.font.FontVariation
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
        Surface          = Color(0xFFFFFCF5),
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
 * Font families. Variable-axis weights are bound per FontWeight via FontVariation.
 */
val DisplayFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic,  FontWeight.Normal, FontStyle.Italic),
)

val BodyFamily = FontFamily(
    Font(
        R.font.plus_jakarta_sans_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.plus_jakarta_sans_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.plus_jakarta_sans_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.plus_jakarta_sans_variable,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
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
```

- [ ] **Step 2: Build verification**

Run:
```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL. If any consumer references a deleted symbol (e.g., the old `Palette` fields), fix the call site or extend the backwards-compat aliases above.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt
git commit -m "feat(vault): rewrite BrandStyleGuide.kt — new tokens, fonts, motion"
```

---

### Task 0.3: Update `Type.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Type.kt`

- [ ] **Step 1: Replace content**

```kotlin
package com.example.coupontracker.ui.theme

import androidx.compose.material3.Typography

val Typography = Typography(
    displayLarge   = BrandTypography.DisplayLarge,
    displayMedium  = BrandTypography.DisplayMedium,
    displaySmall   = BrandTypography.DisplaySmall,
    headlineLarge  = BrandTypography.HeadlineLarge,
    headlineMedium = BrandTypography.HeadlineMedium,
    headlineSmall  = BrandTypography.HeadlineSmall,
    titleLarge     = BrandTypography.TitleLarge,
    titleMedium    = BrandTypography.TitleMedium,
    titleSmall     = BrandTypography.TitleSmall,
    bodyLarge      = BrandTypography.BodyLarge,
    bodyMedium     = BrandTypography.BodyMedium,
    bodySmall      = BrandTypography.BodySmall,
    labelLarge     = BrandTypography.LabelLarge,
    labelMedium    = BrandTypography.LabelMedium,
    labelSmall     = BrandTypography.LabelSmall,
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/theme/Type.kt
git commit -m "feat(vault): wire Compose Typography to new BrandTypography"
```

---

### Task 0.4: Update `Shape.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Shape.kt`

- [ ] **Step 1: Replace content**

```kotlin
package com.example.coupontracker.ui.theme

import androidx.compose.material3.Shapes

val Shapes = Shapes(
    extraSmall = BrandShapes.ExtraSmall,
    small      = BrandShapes.Small,
    medium     = BrandShapes.Medium,
    large      = BrandShapes.Large,
    extraLarge = BrandShapes.XLarge,
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/theme/Shape.kt
git commit -m "feat(vault): wire Compose Shapes to new BrandShapes"
```

---

### Task 0.5: Update `Theme.kt`

**Files:**
- Modify: `app/src/main/kotlin/com/example/coupontracker/ui/theme/Theme.kt`

- [ ] **Step 1: Replace content**

```kotlin
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
```

- [ ] **Step 2: Build verification**

```bash
./gradlew :app:assembleDebug --no-daemon
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/theme/Theme.kt
git commit -m "feat(vault): re-wire Material3 colorScheme to new BrandColors palettes"
```

---

### Task 0.6: Sync legacy XML resources

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values-night/themes.xml`

- [ ] **Step 1: Read existing files**

Use the Read tool on all four. Note any color/style entries that map to the old blue accent.

- [ ] **Step 2: Update `values/colors.xml`**

Replace primary/accent color values to match the Light palette. Map exactly:

```xml
<color name="primary">#00D69E</color>
<color name="primary_variant">#00B488</color>
<color name="on_primary">#0D0C10</color>
<color name="background">#F2EDE4</color>
<color name="surface">#FFFCF5</color>
<color name="on_background">#0D0C10</color>
<color name="on_surface">#1B1A1F</color>
<color name="stroke">#D9D2C5</color>
<color name="divider">#E6E1D5</color>
<color name="error">#DC3553</color>
```

Preserve any other color entries used by non-theme XML resources unless they refer to the deleted blue accent — those should adopt `@color/primary`.

- [ ] **Step 3: Update `values-night/colors.xml` similarly**

```xml
<color name="primary">#00D69E</color>
<color name="primary_variant">#00B488</color>
<color name="on_primary">#0D0C10</color>
<color name="background">#0D0C10</color>
<color name="surface">#16151B</color>
<color name="on_background">#F7F4EE</color>
<color name="on_surface">#E6E2D9</color>
<color name="stroke">#2A2832</color>
<color name="divider">#1F1E27</color>
<color name="error">#FF4D6D</color>
```

- [ ] **Step 4: Update both `themes.xml` files**

Remove any `<item name="android:fontFamily">@font/uber_move</item>` or `@font/roboto` references. Update primary/secondary attributes to point at `@color/primary` etc.

- [ ] **Step 5: Build verification**

```bash
./gradlew :app:assembleDebug --no-daemon
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/values/colors.xml \
        app/src/main/res/values-night/colors.xml \
        app/src/main/res/values/themes.xml \
        app/src/main/res/values-night/themes.xml
git commit -m "feat(vault): align legacy XML resources to new tokens"
```

---

### Task 0.7: Phase 0 verification

- [ ] **Step 1: Full debug build**

```bash
./gradlew :app:assembleDebug --no-daemon
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run unit tests**

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```
Expected: BUILD SUCCESSFUL (no semantic test changes; if a test fails it indicates a token rename mismatch — fix the call site, never the test name).

- [ ] **Step 3: Launch the app on an emulator** (manual verification by user)

Boot the existing build, navigate to HomeScreen + SettingsScreen. Expect: warm-bone background in light mode, near-black in dark mode, mint accent on primary actions, hairline strokes on cards. Screens will look raw (no editorial type, no wallet card yet) — that's expected because the screen-level work happens in Phase 3. The token shift should be obvious.

If the app crashes or fails to load fonts, the most common cause is missing `.ttf` binaries — confirm step 0.1.

---

## Phase 1 — Component primitives (parallelizable after Phase 0)

Four independent files. Build all four in parallel agents. Each finishes with a build that passes plus a Preview composable that renders the component in both light and dark.

### Task 1.1: `GlassSurface`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/GlassSurface.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.coupontracker.ui.components

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.example.coupontracker.R
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = BrandShapes.Large,
    tint: Color = BrandColors.SurfaceVariant,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.clip(shape)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .fillMaxSize()
                    .blur(24.dp)
                    .background(tint.copy(alpha = 0.72f))
            )
        } else {
            val noise: Painter = painterResource(id = R.drawable.glass_noise)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tint.copy(alpha = 0.92f))
                    .paint(noise, contentScale = ContentScale.Crop, alpha = 0.06f)
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .border(BorderStroke(BrandSpacing.Hairline, BrandColors.Stroke), shape)
        )
        content()
    }
}
```

(Imports include `dp` from `androidx.compose.ui.unit.dp` — add if compiler complains.)

- [ ] **Step 2: Add Preview composable in same file**

```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.CouponTrackerTheme

@Preview(name = "Glass — Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Glass — Light")
@Composable
private fun GlassSurfacePreview() {
    CouponTrackerTheme {
        GlassSurface(Modifier.size(240.dp, 120.dp)) {
            Text("Glass", Modifier.padding(16.dp))
        }
    }
}
```

- [ ] **Step 3: Build verify + commit**

```bash
./gradlew :app:assembleDebug --no-daemon
git add app/src/main/kotlin/com/example/coupontracker/ui/components/GlassSurface.kt
git commit -m "feat(vault): GlassSurface composable with API-aware blur fallback"
```

---

### Task 1.2: `BrandButton`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/BrandButton.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.coupontracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography

enum class BrandButtonTier { Primary, Secondary, Tertiary }

@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tier: BrandButtonTier = BrandButtonTier.Primary,
    enabled: Boolean = true,
) {
    val padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
    when (tier) {
        BrandButtonTier.Primary -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = BrandShapes.Medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrandColors.Accent,
                contentColor   = BrandColors.OnAccent,
            ),
            contentPadding = padding,
        ) { Text(text, style = BrandTypography.LabelLarge) }
        BrandButtonTier.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = BrandShapes.Medium,
            border = BorderStroke(BrandSpacing.Hairline, BrandColors.Stroke),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = BrandColors.OnSurface),
            contentPadding = padding,
        ) { Text(text, style = BrandTypography.LabelLarge) }
        BrandButtonTier.Tertiary -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = BrandColors.Accent),
            contentPadding = padding,
        ) { Text(text, style = BrandTypography.LabelLarge) }
    }
}
```

- [ ] **Step 2: Preview + build + commit**

Add a `@Preview` composable showing the three tiers in dark + light, then:
```bash
./gradlew :app:assembleDebug --no-daemon
git add app/src/main/kotlin/com/example/coupontracker/ui/components/BrandButton.kt
git commit -m "feat(vault): BrandButton three-tier primary/secondary/tertiary"
```

---

### Task 1.3: `BrandTextField`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/BrandTextField.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.coupontracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography

@Composable
fun BrandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val sanitisedLabel = remember(label) { label.lowercase() }
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = BrandTypography.LabelMedium,
            color = BrandColors.OnSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .semantics { contentDescription = sanitisedLabel },
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = BrandTypography.BodyLarge.copy(color = BrandColors.OnSurface),
            modifier = Modifier
                .clip(BrandShapes.Medium)
                .background(BrandColors.Surface)
                .border(
                    BorderStroke(BrandSpacing.Hairline, BrandColors.Stroke),
                    BrandShapes.Medium,
                )
                .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = BrandTypography.BodyLarge, color = BrandColors.Muted)
                }
                inner()
            },
        )
    }
}
```

- [ ] **Step 2: Preview + build + commit**

```bash
./gradlew :app:assembleDebug --no-daemon
git add app/src/main/kotlin/com/example/coupontracker/ui/components/BrandTextField.kt
git commit -m "feat(vault): BrandTextField canonical input with small-caps label"
```

---

### Task 1.4: `BrandTopBar`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/BrandTopBar.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.coupontracker.ui.components

import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = { Text(title, style = BrandTypography.HeadlineSmall, color = BrandColors.OnBackground) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarColors(
            containerColor = BrandColors.Background,
            scrolledContainerColor = BrandColors.Background,
            navigationIconContentColor = BrandColors.OnBackground,
            titleContentColor = BrandColors.OnBackground,
            actionIconContentColor = BrandColors.OnSurfaceVariant,
        ),
    )
}
```

- [ ] **Step 2: Preview + build + commit**

```bash
./gradlew :app:assembleDebug --no-daemon
git add app/src/main/kotlin/com/example/coupontracker/ui/components/BrandTopBar.kt
git commit -m "feat(vault): BrandTopBar flush, no elevation, editorial title"
```

---

## Phase 2 — `CouponCard` signature component

After Phase 1 lands. Build CouponCard in one focused agent — it is the most design-sensitive surface.

### Task 2.1: State + variant types

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/CouponCardModel.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.coupontracker.ui.components

import androidx.compose.ui.graphics.Color

enum class CouponCardVariant { WalletStack, Carousel, Preview, List }

sealed interface CouponCardState {
    data object Default : CouponCardState
    data object Selected : CouponCardState
    data object Redeemed : CouponCardState
    data object Expired : CouponCardState
    data object Loading : CouponCardState
}

/** Render model — the data the card needs to draw itself. */
data class CouponCardModel(
    val brandName: String,
    val brandInitial: Char,
    val brandColor: Color?,        // null falls back to Stroke
    val valueLabel: String,        // formatted ("$50.00", "Free", "20% OFF")
    val code: String,
    val expiresAt: String,         // formatted ("Aug 14, 2026")
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/components/CouponCardModel.kt
git commit -m "feat(vault): CouponCard state/variant/model types"
```

### Task 2.2: Implement `CouponCard.kt`

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/CouponCard.kt`

Implement the full component per spec §5. Key behaviors:
- Aspect ratio 16:10 via `Modifier.aspectRatio(16f/10f)`.
- Standard state: Surface bg + Hairline Stroke.
- Hero foil: 1.5.dp gradient stroke + behind-card sunset blur layer.
- Redeemed/Expired states: alpha 0.4 + diagonal watermark.
- Loading: shimmer sweep skeleton (`SurfaceVariant` rectangles + `Decelerate` alpha gradient).
- Press scale 0.97 via `Modifier.scale(animateFloatAsState(if (pressed) 0.97f else 1f, spring = BrandSpring.Press))`.
- Code masked initially; tap reveals + auto-mask after 4 s (use `LaunchedEffect(revealed) { delay(4000); revealed = false }`).
- Variant `Preview` uses dashed Stroke (`stroke = Stroke(width = Hairline.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))` on a Canvas).

- [ ] **Step 1: Implement composable.**
- [ ] **Step 2: Add Previews — Default, Hero foil, Redeemed, Expired, Loading, Preview variant — in both Dark and Light uiMode.**
- [ ] **Step 3: Build verify and commit.**

```bash
./gradlew :app:assembleDebug --no-daemon
git add app/src/main/kotlin/com/example/coupontracker/ui/components/CouponCard.kt
git commit -m "feat(vault): CouponCard signature component with foil + variants"
```

### Task 2.3: `WalletStackCard` layout helper

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/components/WalletStack.kt`

`WalletStack` is a vertical `LazyColumn` with negative spacing using `verticalArrangement = Arrangement.spacedBy(BrandSpacing.WalletCardOverlap)`. Top card animates to full height; the rest show only the first 60.dp via `Modifier.height(60.dp)` when not active.

- [ ] **Step 1: Implement.**
- [ ] **Step 2: Add Preview with 5 stub coupons.**
- [ ] **Step 3: Build + commit.**

```bash
git commit -m "feat(vault): WalletStack lazy column with overlap and peek-cards"
```

---

## Phase 3 — Screen updates (parallelizable in four groups)

Each group is an independent dispatchable agent. Each agent owns its screen files; no two agents touch the same file. Refer to spec §6 and the relevant `CouponCard`/`BrandButton`/`BrandTextField`/`BrandTopBar` from Phases 1-2.

### Group A — Wallet (1 agent)

**Owns:**
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/HomeScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponDetailScreen.kt`

**Per-screen change list:**

- **HomeScreen**
  - Replace toolbar with `BrandTopBar(title = "Vault")`.
  - Below the bar, render `DisplayLarge` wordmark only when scrolled-to-top; collapse into the top bar on scroll.
  - Replace the existing list with `WalletStack` of `CouponCard(variant = WalletStack)`.
  - Hero card on top: pass `isHero = true` when the entry is the top-decile value or favorited.
  - Empty state: `DisplayMedium` Instrument Serif "Your wallet is empty"; `BodyLarge` description; `BrandButton` primary "Scan a coupon".
  - Remove the floating "Download Model" FAB whenever the model card is visible (audit fix).
  - Status bar must not contrast — set to `Background` exactly.

- **CouponDetailScreen**
  - Top: `BrandTopBar` with back navigation.
  - Hero card: `CouponCard(variant = WalletStack, isHero = ...)` at full width.
  - Below the card, meta rows: `LabelMedium` small-caps label left, `BodyLarge` value right. Sections: "ADDED", "SOURCE", "NOTES".
  - Bottom-pinned `BrandButton` primary "Mark as redeemed".
  - On redeem tap, run 200ms sunset gradient sweep across the hero card (use `Animatable<Float>` driving `Brush.linearGradient` offset).

Commit after each file (so the diff stays reviewable):

```bash
git commit -m "feat(vault): repaint HomeScreen with wallet stack"
git commit -m "feat(vault): repaint CouponDetailScreen with hero card + redeem celebration"
```

### Group B — Capture (1 agent)

**Owns:**
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/ScannerScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/UnifiedCameraScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCameraScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/SmartCaptureScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/UnifiedUploadScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/BatchScannerScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponPreviewScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/MultiCouponReviewScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/QRScannerScreen.kt`

**Common changes:**
- Replace all toolbars with `BrandTopBar`.
- Camera shutter: mint solid 72.dp circle (`Box` with `background(BrandColors.Accent, CircleShape)`), surrounded by a `BorderStroke(Hairline2, BrandColors.OnBackground)` ring.
- Bottom sheets adopt `GlassSurface`.
- Preview/review tiles use `CouponCard(variant = Preview)` with dashed Stroke.
- Bulk-action bars: bottom-pinned `GlassSurface` with `BrandButton` primary + `BrandButton` secondary.
- QR reticle: 1.5.dp `Accent` stroke with 4.dp corner segments only.

Commit per file.

### Group C — Forms (1 agent)

**Owns:**
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/CouponFormScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/ManualEntryScreen.kt`

**Changes:**
- All `OutlinedTextField` → `BrandTextField`.
- All buttons → `BrandButton`.
- Audit-flagged URL-field collision (`Alignment.BottomCenter` overlay inside the same `Box`): move the URL field into the scrollable form column, just above the Save action. No more `Box`-overlay layout.
- Save bar pinned to bottom inside `GlassSurface`.

### Group D — Settings & secondary (1 agent)

**Owns:**
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/SettingsScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/OnboardingScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/AnalyticsScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/ExtractionDashboardScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/PrivacyPolicyScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/LicenseGateScreen.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/screen/ApiTestScreen.kt`

**Per-screen highlights:**
- **SettingsScreen** — Move developer/analytics entries inside a collapsible "Advanced" section (audit fix). Section titles `LabelMedium` small-caps with 32.dp `SectionSpacing` above.
- **OnboardingScreen** — Each step centered: `DisplayMedium` headline, `BodyLarge` description, `BrandButton` primary, `BrandButton` tertiary "Skip". Step dots: mint when active, `Stroke` when not.
- **AnalyticsScreen** — Stat cards: hairline cards, `LabelMedium` small-caps label, `DisplaySmall` tabular value. Bars `Accent`, gridlines `Divider`.
- **ExtractionDashboardScreen** — Hairline cards; status pills use `BrandShapes.Pill`, accent tinted by `Success`/`Warning`/`Error` token.
- **PrivacyPolicyScreen** — `BodyLarge` long-form, 32.dp `ContentEdge`, 1.5× line height. Headings `HeadlineSmall`.
- **LicenseGateScreen** — Centered editorial layout per spec.
- **ApiTestScreen** — Add `LabelMedium` small-caps "ADVANCED" badge top-right.

Commit per file.

---

## Phase 4 — Component repaints (parallelizable, 1 agent)

**Owns:**
- `app/src/main/kotlin/com/example/coupontracker/ui/components/SimplifiedCaptureBottomSheet.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/SimpleCaptureBottomSheet.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/ExtractionDashboard.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/TooltipOverlay.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/ImagePreviewDialog.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/ExtractionFeedbackDialog.kt`
- `app/src/main/kotlin/com/example/coupontracker/ui/components/DataSafetyDialog.kt`

**Changes:**
- Bottom sheets: replace background with `GlassSurface`.
- Consolidate `SimplifiedCaptureBottomSheet` + `SimpleCaptureBottomSheet` into one — delete the one with fewer call sites; redirect call sites.
- `ExtractionDashboard`: repaint to match AnalyticsScreen treatment.
- `TooltipOverlay`: `SurfaceVariant` bg + Stroke + small-caps `LabelSmall` body.
- Dialogs: `BrandShapes.Large`, `DialogElevation = Lifted`, body in `BodyLarge`, primary action `BrandButton`.

Commit per file.

---

## Phase 5 — Cleanup & verification (sequential, 1 agent, final pass)

### Task 5.1: Cross-screen grep for blue accent leftovers

- [ ] **Step 1: Run**

```bash
grep -rn "0xFF2979FF\|0xFF2563EB\|2979FF\|2563EB\|UberMove\|uber_move\|Roboto\|R\.font\.roboto" \
  app/src/main/ --include="*.kt" --include="*.xml" || echo "Clean"
```
Expected: `Clean` (no matches).

If matches exist: replace each with the appropriate new token (`BrandColors.Accent` for blue refs, delete font refs).

### Task 5.2: Adaptive icon update

**Files:**
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml` (a vault-logo glyph over `BrandColors.SunsetGradient`)

Adopt the sunset gradient as the adaptive-icon foreground background; foreground glyph in `OnAccent` (`#0D0C10`).

### Task 5.3: Final build + tests

```bash
./gradlew :app:assembleDebug --no-daemon
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
```

Expected: all green. Address any lint warnings introduced by the redesign (typically unused old `BrandSpacing` aliases — keep them; they preserve the back-compat surface).

### Task 5.4: Manual emulator pass

Launch on an emulator and walk every screen group:
- Wallet (Home + Detail) — wallet stack with overlap, hero foil, redeem animation
- Capture (camera + bottom sheets + multi-coupon flow) — mint shutter, glass sheets
- Forms (manual entry + form) — small-caps labels, sticky save bar, URL no longer overlapping
- Settings + secondary — small-caps sections, Advanced collapsible, mint accents

Commit: `chore(vault): final cleanup and manual QA pass`

---

## Self-review checklist (run by writer)

- ✅ Spec §1 (foundation) → covered by Phase 0 token rewrite + adaptive icon (Phase 5).
- ✅ Spec §2 (color) → Task 0.2.
- ✅ Spec §3 (typography) → Task 0.1 (fonts) + Task 0.2 (`BrandTypography`).
- ✅ Spec §4 (shape/spacing/elevation/motion + glass) → Task 0.2 + Task 1.1 (`GlassSurface`).
- ✅ Spec §5 (`CouponCard`) → Phase 2.
- ✅ Spec §6 (screen-by-screen) → Phase 3 + Phase 4.
- ✅ Spec §7 (impl considerations) → addressed in Task 0.1 (font acquisition), Task 1.1 (API gating), Task 5.3 (tests).

Type consistency: `CouponCardState`/`CouponCardVariant`/`CouponCardModel` defined Task 2.1; consumed Tasks 2.2-2.3 and Phase 3 Group A. `BrandButtonTier` defined Task 1.2; consumed across Phase 3-4. `GlassSurface` defined Task 1.1; consumed Phase 3-4. No undefined references.

No placeholders, TBDs, or "TODO" in committed code. Step "implement composable" in Tasks 2.2 / 2.3 references spec §5 directly — the spec is the authoritative reference.

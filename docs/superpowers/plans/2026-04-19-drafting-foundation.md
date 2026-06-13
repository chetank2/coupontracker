# Drafting Aesthetic — Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the drafting-aesthetic design system for the Android app — new theme, typography (3 Google Fonts via downloadable fonts), shared Compose components (TitleBlock, DimensionLine, NumberedCallout, BuildLogRow, SheetTabBar, TheodoliteReticle, StatusCodeChip, ScaleBar, YieldDial), and a drafting-grid `Modifier`. Additive only — no existing screen breaks.

**Architecture:** A second `DraftingTheme` composable lives alongside the current `CouponTrackerTheme`. Screens opt into the new aesthetic when they migrate in Plans Two and Three; the existing 20 screens continue to run on Material 3 until individually touched. Fonts come from Google Fonts downloadable-fonts — no bundled TTF assets. Every shared component is a thin, testable composable with a `@Preview` so the design is verifiable on the Compose preview pane without running the full app.

**Tech Stack:** Kotlin 1.9, Jetpack Compose 1.6.2, Material 3 1.2.0, `androidx.compose.ui:ui-text-google-fonts:1.6.2`.

---

## Pre-flight

- Branch: `feature/qwen-multi-coupon-extraction`. HEAD at plan-writing time includes 62 + 20 + 1 = 83 commits from prior sessions.
- Visual reference: `docs/design/coupon_dashboard_mock.html` + `docs/design/coupon_scanner_mock.html` — the aesthetic we are translating.
- Existing theme: `app/src/main/kotlin/com/example/coupontracker/ui/theme/` — `Theme.kt`, `Type.kt`, `Shape.kt`, `BrandStyleGuide.kt`. Do NOT remove these; they back every existing screen.
- Google Fonts selections (all on fonts.google.com):
  - **Big Shoulders Display** — condensed grotesque for display / numerals / headings
  - **IBM Plex Mono** — monospace for technical labels + ledger data
  - **Manrope** — humanist sans for body copy (Geist replacement; Geist is not on Google Fonts)

## File Structure

All new code lives under `app/src/main/kotlin/com/example/coupontracker/ui/drafting/` to keep it clearly separated from the existing theme until migration is complete.

### Files to create

**Theme primitives**
- `ui/drafting/DraftingColors.kt` — `Color` tokens (paper, ink, amber, etc.) + `draftingColorScheme()`
- `ui/drafting/DraftingTypography.kt` — `Typography` with Big Shoulders Display / IBM Plex Mono / Manrope applied to M3 type roles
- `ui/drafting/DraftingShapes.kt` — zero-radius `Shapes` (architectural corners)
- `ui/drafting/DraftingTheme.kt` — `@Composable DraftingTheme(content: @Composable () -> Unit)`
- `ui/drafting/DraftingTokens.kt` — sizing / stroke / spacing constants used across components

**Utility**
- `ui/drafting/DraftingGrid.kt` — `Modifier.draftingGrid()` that draws 8px + 64px grid via `drawBehind`

**Shared components (one file each)**
- `ui/drafting/components/TitleBlock.kt`
- `ui/drafting/components/SheetStamp.kt`
- `ui/drafting/components/RevisionTriangle.kt`
- `ui/drafting/components/DimensionLine.kt`
- `ui/drafting/components/NumberedCallout.kt`
- `ui/drafting/components/BuildLogRow.kt`
- `ui/drafting/components/SheetTabBar.kt`
- `ui/drafting/components/TheodoliteReticle.kt`
- `ui/drafting/components/StatusCodeChip.kt`
- `ui/drafting/components/ScaleBar.kt`
- `ui/drafting/components/YieldDial.kt`

### Files to modify

- `app/build.gradle.kts` — add `implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")`
- `app/src/main/AndroidManifest.xml` — add Google Fonts provider certs meta-data (required once per app)
- `app/src/main/res/values/preloaded_fonts.xml` — create (empty array is fine; declares the font-provider query)

### Files NOT touched

- Existing `ui/theme/*.kt` files — unchanged
- All existing `ui/screen/*.kt` files — unchanged
- All ViewModels, navigation, strings — unchanged

---

## Task 1: Add Google Fonts dependency

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Locate the compose dependencies block**

```bash
grep -n "compose.ui:ui:" app/build.gradle.kts
```
Expected: line 358 with `implementation("androidx.compose.ui:ui:1.6.2")`.

- [ ] **Step 2: Add the Google Fonts dependency**

Immediately after the existing `implementation("androidx.compose.ui:ui:1.6.2")` line, add:

```kotlin
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")
```

- [ ] **Step 3: Build sanity**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. If Gradle resolves without network, you may see `Could not resolve` — retry with network; dep is on mavenCentral / google().

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore(deps): add compose ui-text-google-fonts for drafting theme"
```

---

## Task 2: Provider certificates for Google Fonts

**Files:**
- Create: `app/src/main/res/values/preloaded_fonts.xml`
- Modify: `app/src/main/AndroidManifest.xml` (no structural change — metadata only)

- [ ] **Step 1: Create preloaded_fonts.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="preloaded_fonts" translatable="false">
        <!-- Downloadable fonts resolved at composition time; no assets declared. -->
    </array>
</resources>
```

- [ ] **Step 2: Verify provider certs are available**

```bash
grep -n "preloaded_fonts\|fontProviderCerts" app/src/main/AndroidManifest.xml
```
If no hit, the app already relies on runtime `GoogleFont.Provider` which provides its own certs via `com.google.android.gms.fonts`. No manifest edit needed in that case.

If the app uses pre-provisioned certs (you see a `<meta-data>` entry), leave the manifest alone.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/preloaded_fonts.xml
git commit -m "chore(res): preloaded_fonts scaffold for drafting downloadable fonts"
```

---

## Task 3: DraftingTokens

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTokens.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.ui.unit.dp

/**
 * Named sizing / stroke / spacing constants for the drafting aesthetic.
 * Match the HTML mocks at docs/design/coupon_dashboard_mock.html so the
 * Android implementation reads as the same design system.
 */
object DraftingTokens {
    // Hairline strokes
    val HairlineThin = 0.6.dp
    val Hairline = 1.dp
    val HairlineBold = 1.5.dp
    val HairlineAccent = 2.dp

    // Grid
    val GridMinor = 8.dp   // minor rule
    val GridMajor = 64.dp  // major rule

    // Title block
    val TitleBlockHeight = 64.dp
    val TitleBlockPadH = 16.dp
    val TitleBlockPadV = 14.dp

    // Sheet stamp
    val SheetStampPadH = 8.dp

    // Dimension line
    val DimensionArrowSize = 6.dp
    val DimensionLabelPadH = 6.dp

    // Callout
    val CalloutCircleSize = 17.dp

    // Dial
    val DialSize = 240.dp
    val DialTickRimInner = 110.dp
    val DialTickRimOuter = 116.dp
    val DialInstrumentInner = 76.dp

    // Reticle
    val ReticleSize = 38.dp

    // Sheet tab bar
    val SheetTabBarHeight = 64.dp
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTokens.kt
git commit -m "feat(drafting): add DraftingTokens with sizing + stroke constants"
```

---

## Task 4: DraftingColors

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingColors.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Drafting palette — midnight drafting paper with mint ink.
 * Mirrors the CSS tokens in docs/design/coupon_dashboard_mock.html :root.
 */
object DraftingColors {
    // Paper (backgrounds)
    val Paper       = Color(0xFF0A1426)
    val Paper2      = Color(0xFF0D1A2F)
    val Paper3      = Color(0xFF07101E)
    val Void        = Color(0xFF02060F)

    // Ink
    val Ink         = Color(0xFF5EEAD4)
    val InkBold     = Color(0xFF7FFFD4)
    val InkSoft     = Color(0x595EEAD4) // alpha ~0.35
    val InkFaint    = Color(0x295EEAD4) // alpha ~0.16
    val InkTrace    = Color(0x125EEAD4) // alpha ~0.07

    // Secondary accents
    val Amber       = Color(0xFFE9B872)
    val AmberSoft   = Color(0x59E9B872)
    val AmberFaint  = Color(0x1AE9B872)
    val Red         = Color(0xFFF47878)

    // Text
    val Text        = Color(0xFFEFF6F4)
    val Text2       = Color(0x9EEFF6F4) // 62%
    val Text3       = Color(0x57EFF6F4) // 34%
    val Text4       = Color(0x23EFF6F4) // 14%
}

/**
 * Material 3 dark color scheme that maps drafting tokens onto M3 roles.
 * Screens that use MaterialTheme inside DraftingTheme get these.
 */
fun draftingColorScheme(): ColorScheme = darkColorScheme(
    primary            = DraftingColors.Ink,
    onPrimary          = DraftingColors.Paper,
    primaryContainer   = DraftingColors.InkFaint,
    onPrimaryContainer = DraftingColors.Ink,
    secondary          = DraftingColors.Amber,
    onSecondary        = DraftingColors.Paper,
    secondaryContainer = DraftingColors.AmberFaint,
    onSecondaryContainer = DraftingColors.Amber,
    tertiary           = DraftingColors.InkBold,
    onTertiary         = DraftingColors.Paper,
    background         = DraftingColors.Paper,
    onBackground       = DraftingColors.Text,
    surface            = DraftingColors.Paper3,
    onSurface          = DraftingColors.Text,
    surfaceVariant     = DraftingColors.Paper2,
    onSurfaceVariant   = DraftingColors.Text2,
    outline            = DraftingColors.InkFaint,
    outlineVariant     = DraftingColors.InkTrace,
    error              = DraftingColors.Red,
    onError            = DraftingColors.Paper
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingColors.kt
git commit -m "feat(drafting): add DraftingColors palette + M3 colorScheme"
```

---

## Task 5: DraftingTypography (downloadable Google Fonts)

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTypography.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.example.coupontracker.R

private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage   = "com.google.android.gms",
    certificates      = R.array.com_google_android_gms_fonts_certs
)

private val BigShouldersFamily = FontFamily(
    Font(googleFont = GoogleFont("Big Shoulders Display"), fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("Big Shoulders Display"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Big Shoulders Display"), fontProvider = googleFontProvider, weight = FontWeight.Bold),
    Font(googleFont = GoogleFont("Big Shoulders Display"), fontProvider = googleFontProvider, weight = FontWeight.ExtraBold)
)

private val PlexMonoFamily = FontFamily(
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("IBM Plex Mono"), fontProvider = googleFontProvider, weight = FontWeight.Medium)
)

private val ManropeFamily = FontFamily(
    Font(googleFont = GoogleFont("Manrope"), fontProvider = googleFontProvider, weight = FontWeight.Light),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = GoogleFont("Manrope"), fontProvider = googleFontProvider, weight = FontWeight.SemiBold)
)

object DraftingFonts {
    val Display = BigShouldersFamily
    val Mono    = PlexMonoFamily
    val Sans    = ManropeFamily
}

/**
 * Typography mapping matching the HTML mocks. Display roles are Big Shoulders
 * all-caps for numerals/headings; labels use IBM Plex Mono (tracked-out);
 * body copy uses Manrope.
 */
val DraftingTypography: Typography = Typography(
    displayLarge   = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.ExtraBold, fontSize = 64.sp, lineHeight = 56.sp, letterSpacing = 0.5.sp),
    displayMedium  = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Bold,      fontSize = 48.sp, lineHeight = 48.sp, letterSpacing = 0.5.sp),
    displaySmall   = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Bold,      fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = 0.4.sp),

    headlineLarge  = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Bold,      fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = 1.4.sp),
    headlineMedium = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Bold,      fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 1.2.sp),
    headlineSmall  = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Medium,    fontSize = 16.sp, lineHeight = 20.sp, letterSpacing = 1.0.sp),

    titleLarge     = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Medium,    fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 1.8.sp),
    titleMedium    = TextStyle(fontFamily = BigShouldersFamily, fontWeight = FontWeight.Medium,    fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 2.0.sp),
    titleSmall     = TextStyle(fontFamily = PlexMonoFamily,     fontWeight = FontWeight.Medium,    fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 2.4.sp),

    bodyLarge      = TextStyle(fontFamily = ManropeFamily,      fontWeight = FontWeight.Normal,    fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium     = TextStyle(fontFamily = ManropeFamily,      fontWeight = FontWeight.Normal,    fontSize = 12.sp, lineHeight = 16.sp),
    bodySmall      = TextStyle(fontFamily = ManropeFamily,      fontWeight = FontWeight.Normal,    fontSize = 11.sp, lineHeight = 14.sp),

    labelLarge     = TextStyle(fontFamily = PlexMonoFamily,     fontWeight = FontWeight.Medium,    fontSize = 11.sp, lineHeight = 12.sp, letterSpacing = 0.4.sp),
    labelMedium    = TextStyle(fontFamily = PlexMonoFamily,     fontWeight = FontWeight.Medium,    fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.6.sp),
    labelSmall     = TextStyle(fontFamily = PlexMonoFamily,     fontWeight = FontWeight.Medium,    fontSize =  9.sp, lineHeight = 10.sp, letterSpacing = 1.0.sp)
)
```

- [ ] **Step 2: Build sanity**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL. If it fails on `R.array.com_google_android_gms_fonts_certs`, add this resource file:

`app/src/main/res/values/font_certs.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <array name="com_google_android_gms_fonts_certs">
        <item>@array/com_google_android_gms_fonts_certs_dev</item>
        <item>@array/com_google_android_gms_fonts_certs_prod</item>
    </array>
    <string-array name="com_google_android_gms_fonts_certs_dev">
        <item>
MIIEqDCCA5CgAwIBAgIJANWFuGx90071MA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA0MTUyMzM2NTZaFw0zNTA5MDEyMzM2NTZaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBANbOIDuDMYFRUfMmVr0y4NGQMjy1BpRUFQeAH7ZgQE9fGdnNixK+E3LznIbZC8ssUG6FO9sAG6W6DoIlcwWwi/34zdvlmhzPyIk83wb8cF1jV0xH4ONTtojA9b+nhd1+fEwlV5f/2A5iWn9A0DYBtm4ZH/wR0RWP7bkF+E0x36gXXzoa/H4U3xUyMmiBgWytBCkFDcwYqsBnsvKoUEFGH3ZitFHcUxwFyC0k/jvZX7Yvw6XthSYi6ISftv23fxfZJNfDS0mGbBhDWgBqWRFuYj3ZCdpxYQaqCk0N7zsrwFjKOImKWSZMNYkiBkGkXAR9/GFEn2QClBsA9ZYcPizk63UCAQOjggErMIIBJzAdBgNVHQ4EFgQUwWGAoF1+ZKUfnLguYQKEsBy+kpUwgfcGA1UdIwSB7zCB7IAUwWGAoF1+ZKUfnLguYQKEsBy+kpWhgZikdqB0MIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJANWFuGx90071MAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBAH4b3DZkRHgAAfgGnabyCPbWLUKfRWrsWWTKvR6zAxRmafmuVOjTfBXYi6bTqQqCq3KpZV0zUSO9g2ilpq2JXDhtFzITR1bFEZJzQxGEKHtaoAiI9SM/2sfDUb0BZkMh9P7xeIX3ajUsXgFpxhSlhB1OBZLoCWKqR2Wj2iiavaDVUKxNyGIEcWgWPLoIaBYBIfDIOGGhbRyIbmJnyo6nrWNfxDUbThvlfJH0OqWQnM/AOePxg0rfuqN4qGDx3dwUyv3TJMnQBJzoxe5bIc1HaFTpwl0/RcHyTjvmmk3AprlI3G/UkaLLB7H0uAO4DYHPltoUmNEOLTwoeLLtPPW7ywfg=
        </item>
    </string-array>
    <string-array name="com_google_android_gms_fonts_certs_prod">
        <item>
MIIEqDCCA5CgAwIBAgIJAPYPhDr6JnctMA0GCSqGSIb3DQEBBAUAMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAeFw0wODA4MjEyMzEzMzRaFw0zNjAxMDcyMzEzMzRaMIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZIhvcNAQEBBQADggENADCCAQgCggEBAKtWLgDYO6IIrgqWbxJOKdoR8qtW0I9Y4sypEwPpt1TTcvZApxsdyxMJZ2JORland2qSGT2y5b+3JKkedxiLDmpHpDsz2WCbdxgxRczfey5YZnTJ4VZbH0xqWVW/8lGmPav5xVwnIiJS6HXk+BVKZF+JcWjAsb/GEuq/eFdpuzSqeYTcfi6idkyugwfYwXFU1+5fZKUaRKYCwkkFQVfcAs1fXA5V+++FGfvjJ/CxURaSxaBvGdGDhfXE28LWuT9ozCl5xw4Yq5OGazvV24mZVSoOO0yZ31j7kYvtwYK6NeADwbSxDdJEqO4k6BzSzNmHRuCzv+UX6amOodMl4/VySQ+dAgMBAAGjggEpMIIBJTAdBgNVHQ4EFgQUho4EwpFhrL2Y8UU8yt7wvFtAeAYwgfUGA1UdIwSB7TCB6oAUho4EwpFhrL2Y8UU8yt7wvFtAeAahgZekdZB0MIGUMQswCQYDVQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAPYPhDr6JnctMAwGA1UdEwQFMAMBAf8wDQYJKoZIhvcNAQEEBQADggEBAGkoxH1GujwzkjsgSmyFQk5jCqoMXTHjSLKUmCYl50Gj4tXLSMcbZXQyyzlU6RpxBqn5H+pZ8KDRpq3G1IatZSikt9fhILm6O6EXlLwkpLThHAk5/O/+qDqf8bMqzc1vGudIXpywIhwEInD9Te/WaojDyL/uMTSCIWxIq2P4WAExTXU7XEUabzMjX5WxbvtvCRCaLhe4fGc1hCfxPrxWsA6sEmPsjeKt4Qg1UFcBOOqXowCsm9Q1iN9BRVQlxM3aWBtr2PwVFKE2KNxlG7hnuzKwHu5f0C/X3Pbsz7mJsGBzMMs8Y3dGXdsBy2hVfDkhEANMr0MjV7qGv5IWc14hMlB4g==
        </item>
    </string-array>
</resources>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTypography.kt \
        app/src/main/res/values/font_certs.xml
git commit -m "feat(drafting): add Big Shoulders + Plex Mono + Manrope via Google Fonts"
```

(If `font_certs.xml` was not needed, drop it from staging.)

---

## Task 6: DraftingShapes

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingShapes.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Architectural shapes: every corner is square. M3 defaults round every
 * component; we override all six shape roles with 0.dp.
 */
val DraftingShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small      = RoundedCornerShape(0.dp),
    medium     = RoundedCornerShape(0.dp),
    large      = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingShapes.kt
git commit -m "feat(drafting): add zero-radius DraftingShapes"
```

---

## Task 7: DraftingTheme composable

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTheme.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Drop-in replacement for MaterialTheme that supplies the drafting palette,
 * typography, and shapes. Screens migrating to the drafting aesthetic wrap
 * their root with DraftingTheme { ... } instead of CouponTrackerTheme.
 */
@Composable
fun DraftingTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = draftingColorScheme(),
        typography  = DraftingTypography,
        shapes      = DraftingShapes,
        content     = content
    )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingTheme.kt
git commit -m "feat(drafting): add DraftingTheme composable"
```

---

## Task 8: Modifier.draftingGrid()

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingGrid.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Paints a two-tier drafting grid behind the composable it modifies:
 *   - minor rule every 8 dp at ~4% alpha
 *   - major rule every 64 dp at ~10% alpha
 * Both rules are drawn in the ink color.
 */
fun Modifier.draftingGrid(
    ink: Color = DraftingColors.Ink,
    minorAlpha: Float = 0.04f,
    majorAlpha: Float = 0.10f
): Modifier = this.drawBehind {
    val minor = 8.dp.toPx()
    val major = 64.dp.toPx()

    // Minor grid
    val minorColor = ink.copy(alpha = minorAlpha)
    var x = 0f
    while (x <= size.width) {
        drawLine(minorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += minor
    }
    var y = 0f
    while (y <= size.height) {
        drawLine(minorColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += minor
    }

    // Major grid (drawn on top)
    val majorColor = ink.copy(alpha = majorAlpha)
    x = 0f
    while (x <= size.width) {
        drawLine(majorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += major
    }
    y = 0f
    while (y <= size.height) {
        drawLine(majorColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += major
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/DraftingGrid.kt
git commit -m "feat(drafting): add Modifier.draftingGrid()"
```

---

## Task 9: SheetStamp + RevisionTriangle

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/SheetStamp.kt`
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/RevisionTriangle.kt`

- [ ] **Step 1: Create SheetStamp.kt**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Small paper-background pill that labels a drawing sheet ("SHEET",
 * "PROJECT", "CLIENT"). Typically overlays a title-block border so the
 * border reads as interrupted by the stamp.
 */
@Composable
fun SheetStamp(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = DraftingColors.Ink,
        modifier = modifier
            .background(DraftingColors.Paper)
            .padding(horizontal = 8.dp, vertical = 0.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun SheetStampPreview() {
    DraftingTheme {
        SheetStamp("SHEET")
    }
}
```

- [ ] **Step 2: Create RevisionTriangle.kt**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Architectural revision marker: a hairline-stroked diamond with a single
 * character (usually a revision number).
 */
@Composable
fun RevisionTriangle(
    revision: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(16.dp)
            .background(DraftingColors.Paper),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val s = size.minDimension
            val path = Path().apply {
                moveTo(s * 0.5f, 0f)
                lineTo(s,        s * 0.5f)
                lineTo(s * 0.5f, s)
                lineTo(0f,       s * 0.5f)
                close()
            }
            drawPath(path, DraftingColors.Ink, style = Stroke(width = 1f))
        }
        Text(
            text = revision,
            style = MaterialTheme.typography.labelSmall,
            color = DraftingColors.Ink
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun RevisionTrianglePreview() {
    DraftingTheme {
        RevisionTriangle("1")
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/SheetStamp.kt \
        app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/RevisionTriangle.kt
git commit -m "feat(drafting): add SheetStamp + RevisionTriangle"
```

---

## Task 10: TitleBlock

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/TitleBlock.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

data class TitleBlockField(
    val label: String,   // kerned-out mono caps, e.g. "PROJECT · OWNER"
    val value: String,   // big shoulders display
    val sub: String? = null  // optional mono sub, e.g. "FY26 SAVINGS"
)

/**
 * Architectural sheet title block. Three fields split across the full width
 * with hairline dashed separators and a mint bottom edge. A SheetStamp
 * floats above the top-right corner.
 */
@Composable
fun TitleBlock(
    fields: List<TitleBlockField>,
    modifier: Modifier = Modifier,
    stamp: String? = "SHEET",
    revision: String? = null
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DraftingColors.Paper3)
                .border(width = 1.dp, color = DraftingColors.InkFaint)
                .drawBehind {
                    // bottom mint accent edge
                    val y = size.height - 1.dp.toPx()
                    drawLine(DraftingColors.Ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                fields.forEachIndexed { idx, f ->
                    Column(
                        modifier = Modifier
                            .weight(if (idx == 0) 1.4f else 1f)
                            .padding(end = if (idx == fields.lastIndex) 0.dp else 12.dp)
                            .drawBehind {
                                if (idx < fields.lastIndex) {
                                    val x = size.width - 1.dp.toPx()
                                    drawLine(
                                        DraftingColors.InkFaint,
                                        Offset(x, 0f), Offset(x, size.height),
                                        strokeWidth = 1f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                                    )
                                }
                            },
                        horizontalAlignment = if (idx == fields.lastIndex) Alignment.End else Alignment.Start
                    ) {
                        Text(
                            text = f.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = DraftingColors.Text3
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = f.value,
                                style = MaterialTheme.typography.titleLarge,
                                color = DraftingColors.Text
                            )
                            if (f.sub != null) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = f.sub,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DraftingColors.Text3
                                )
                            }
                        }
                    }
                }
            }
        }

        if (stamp != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 14.dp)
                    .offsetY(-10.dp)
            ) {
                SheetStamp(stamp)
            }
        }
        if (revision != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp)
                    .offsetY(8.dp)
            ) {
                RevisionTriangle(revision)
            }
        }
    }
}

private fun Modifier.offsetY(dy: androidx.compose.ui.unit.Dp) =
    this.then(Modifier.padding(top = if (dy.value < 0) 0.dp else dy, bottom = if (dy.value < 0) (-dy.value).dp else 0.dp))

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun TitleBlockPreview() {
    DraftingTheme {
        TitleBlock(
            fields = listOf(
                TitleBlockField("PROJECT · OWNER", "CHETAN K",   "FY26 SAVINGS"),
                TitleBlockField("SHEET · DRWG",    "A-101",      "1 OF 04"),
                TitleBlockField("ISSUED · REV",    "19·APR",     "R1")
            ),
            stamp = "SHEET",
            revision = "1"
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/TitleBlock.kt
git commit -m "feat(drafting): add TitleBlock composable"
```

---

## Task 11: DimensionLine (horizontal + vertical)

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/DimensionLine.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Horizontal dimension line drawn as two extension ticks at the ends,
 * a hairline spine with arrowheads at both ends, and a label pill
 * centred on the spine.
 */
@Composable
fun DimensionLineHorizontal(
    label: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = DraftingColors.Ink
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h * 0.5f
            val stroke = 1f

            // Extension ticks
            drawLine(color, Offset(0f, h * 0.27f), Offset(0f, h * 0.64f), strokeWidth = stroke)
            drawLine(color, Offset(w, h * 0.27f), Offset(w, h * 0.64f), strokeWidth = stroke)

            // Arrowheads
            val arrow = 5.dp.toPx()
            val left = Path().apply {
                moveTo(4f, midY); lineTo(4f + arrow, midY - arrow * 0.5f); lineTo(4f + arrow, midY + arrow * 0.5f); close()
            }
            val right = Path().apply {
                moveTo(w - 4f, midY); lineTo(w - 4f - arrow, midY - arrow * 0.5f); lineTo(w - 4f - arrow, midY + arrow * 0.5f); close()
            }
            drawPath(left,  color)
            drawPath(right, color)

            // Spine
            drawLine(color, Offset(4f + arrow, midY), Offset(w - 4f - arrow, midY), strokeWidth = stroke)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier
                .background(DraftingColors.Paper3)
                .padding(horizontal = 6.dp)
        )
    }
}

/**
 * Vertical dimension line — same construction, rotated. Use when a
 * region's height needs to be annotated on the right side of a bbox.
 */
@Composable
fun DimensionLineVertical(
    label: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = DraftingColors.Ink
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midX = w * 0.5f
            val stroke = 1f

            drawLine(color, Offset(w * 0.27f, 0f), Offset(w * 0.64f, 0f), strokeWidth = stroke)
            drawLine(color, Offset(w * 0.27f, h), Offset(w * 0.64f, h), strokeWidth = stroke)

            val arrow = 5.dp.toPx()
            val top = Path().apply {
                moveTo(midX, 4f); lineTo(midX - arrow * 0.5f, 4f + arrow); lineTo(midX + arrow * 0.5f, 4f + arrow); close()
            }
            val bot = Path().apply {
                moveTo(midX, h - 4f); lineTo(midX - arrow * 0.5f, h - 4f - arrow); lineTo(midX + arrow * 0.5f, h - 4f - arrow); close()
            }
            drawPath(top, color)
            drawPath(bot, color)

            drawLine(color, Offset(midX, 4f + arrow), Offset(midX, h - 4f - arrow), strokeWidth = stroke)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier
                .background(DraftingColors.Paper3)
                .padding(horizontal = 6.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426, widthDp = 320, heightDp = 120)
@Composable
private fun DimensionLinePreview() {
    DraftingTheme {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            DimensionLineHorizontal(label = "312 mm", modifier = Modifier
                .androidxFillMaxWidth()
                .heightDp(22))
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                DimensionLineVertical(label = "96 mm", modifier = Modifier.widthDp(26).heightDp(80))
            }
        }
    }
}

// small preview helpers that don't escape the file
private fun Modifier.androidxFillMaxWidth() = this.then(Modifier.fillMaxWidth())
private fun Modifier.heightDp(dp: Int) = this.then(Modifier.padding(top = 0.dp)).then(Modifier.androidx.compose.foundation.layout.height(dp.dp))
private fun Modifier.widthDp(dp: Int) = this.then(Modifier.androidx.compose.foundation.layout.width(dp.dp))
```

*Note: the `fillMaxWidth`, `height`, `width` helpers in the preview are there because preview-only code may not want to clutter top-level imports. If the executing agent finds the import machinery awkward, they can inline `Modifier.fillMaxWidth().height(22.dp)` in the preview directly.*

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/DimensionLine.kt
git commit -m "feat(drafting): add DimensionLineHorizontal + DimensionLineVertical"
```

---

## Task 12: NumberedCallout

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/NumberedCallout.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.DraftingTokens

/**
 * Number-in-circle label. Used on the dashboard dial to reference the four
 * cardinal categories (1 FOOD, 2 SHOPPING, 3 BILLS, 4 TRAVEL).
 * `reverse = true` flips the number to the right of the text, for corners
 * on the right edge of a drawing.
 */
@Composable
fun NumberedCallout(
    index: String,
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    reverse: Boolean = false
) {
    val circle = @Composable {
        Box(
            modifier = Modifier
                .size(DraftingTokens.CalloutCircleSize)
                .background(DraftingColors.Paper, CircleShape)
                .border(1.dp, DraftingColors.Ink, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                style = MaterialTheme.typography.labelSmall,
                color = DraftingColors.Ink
            )
        }
    }
    val body = @Composable {
        Column(
            horizontalAlignment = if (reverse) Alignment.End else Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = DraftingColors.Text
            )
            Text(
                text = amount,
                style = MaterialTheme.typography.labelMedium,
                color = DraftingColors.Text2
            )
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (reverse) { body(); Spacer(Modifier.width(2.dp)); circle() }
        else         { circle(); body() }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun NumberedCalloutPreview() {
    DraftingTheme {
        Column {
            NumberedCallout("1", "FOOD", "₹14,200")
            Spacer(Modifier.size(8.dp))
            NumberedCallout("2", "SHOPPING", "₹19,140", reverse = true)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/NumberedCallout.kt
git commit -m "feat(drafting): add NumberedCallout"
```

---

## Task 13: StatusCodeChip

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/StatusCodeChip.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Bordered status-code stamp — single-line, small caps, hairline border.
 * Tone selects between ink (normal drafting flow), amber (RFI / warning),
 * and muted (read-only annotation).
 */
enum class StatusTone { Ink, Amber, Muted }

@Composable
fun StatusCodeChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Ink
) {
    val fg: Color; val bg: Color; val bd: Color
    when (tone) {
        StatusTone.Ink   -> { fg = DraftingColors.Ink;   bg = DraftingColors.InkFaint.copy(alpha = 0.10f);   bd = DraftingColors.InkFaint }
        StatusTone.Amber -> { fg = DraftingColors.Amber; bg = DraftingColors.AmberFaint;                    bd = DraftingColors.AmberSoft }
        StatusTone.Muted -> { fg = DraftingColors.Text3; bg = Color.Transparent;                            bd = DraftingColors.Text4 }
    }
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg)
            .border(1.dp, bd)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun StatusCodeChipPreview() {
    DraftingTheme {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            StatusCodeChip("CHECKED")
            StatusCodeChip("RFI", tone = StatusTone.Amber)
            StatusCodeChip("MEAS")
            StatusCodeChip("LEGACY", tone = StatusTone.Muted)
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/StatusCodeChip.kt
git commit -m "feat(drafting): add StatusCodeChip"
```

---

## Task 14: BuildLogRow

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/BuildLogRow.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * One row of the Build Log (live wire). Sequence number + timestamp + body
 * (who / what / status chip) + amount. Hairline bottom separator.
 */
@Composable
fun BuildLogRow(
    sequence: String,
    timestamp: String,
    who: String,
    what: String,
    amount: String,
    statusCode: String? = null,
    statusTone: StatusTone = StatusTone.Ink,
    amountTone: Color = DraftingColors.Text,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(DraftingColors.InkTrace, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = sequence,
            style = MaterialTheme.typography.labelSmall,
            color = DraftingColors.Ink,
            modifier = Modifier.width(42.dp)
        )
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelMedium,
            color = DraftingColors.Text3,
            modifier = Modifier.width(54.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = who.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = DraftingColors.Text
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = what,
                style = MaterialTheme.typography.bodySmall,
                color = DraftingColors.Text2
            )
            if (statusCode != null) {
                Spacer(Modifier.height(6.dp))
                StatusCodeChip(statusCode, tone = statusTone)
            }
        }
        Text(
            text = amount,
            style = MaterialTheme.typography.headlineMedium,
            color = amountTone,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 64.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF07101E)
@Composable
private fun BuildLogRowPreview() {
    DraftingTheme {
        Column {
            BuildLogRow(
                sequence = "4801",
                timestamp = "21:38",
                who = "CRED · Bill Pay",
                what = "Cashback on Axis CC · auto-applied at checkout.",
                amount = "₹240",
                statusCode = "AUTO-REDEEM",
                statusTone = StatusTone.Amber,
                amountTone = DraftingColors.Amber
            )
            BuildLogRow(
                sequence = "4800",
                timestamp = "21:14",
                who = "PhonePe · Recharge",
                what = "Jio prepaid ₹239 · agent matched stacked offer.",
                amount = "₹95",
                statusCode = "STACK"
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/BuildLogRow.kt
git commit -m "feat(drafting): add BuildLogRow"
```

---

## Task 15: SheetTabBar

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/SheetTabBar.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.DraftingTokens

data class SheetTab(val number: String, val label: String)

/**
 * Bottom sheet-tab bar. N tabs separated by dashed dividers, with an
 * optional perch cell in the middle for a FAB. Active tab shows a mint
 * underline + bright label.
 */
@Composable
fun SheetTabBar(
    tabs: List<SheetTab>,
    activeIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    perchIndex: Int? = null
) {
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(DraftingTokens.SheetTabBarHeight)
                .background(DraftingColors.Paper3)
                .border(1.dp, DraftingColors.InkFaint)
                .drawBehind {
                    // bottom mint accent
                    val y = size.height - 1.dp.toPx()
                    drawLine(DraftingColors.Ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                }
        ) {
            tabs.forEachIndexed { idx, tab ->
                if (perchIndex != null && idx == perchIndex) {
                    // perch cell has dashed borders but no label
                    Box(
                        modifier = Modifier
                            .width(100.dp)
                            .fillMaxHeight()
                            .drawBehind {
                                val x1 = 0f
                                val x2 = size.width
                                drawLine(DraftingColors.InkFaint, Offset(x1, 0f), Offset(x1, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                                drawLine(DraftingColors.InkFaint, Offset(x2, 0f), Offset(x2, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                            }
                    )
                }
                val active = idx == activeIndex
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(idx) }
                        .drawBehind {
                            if (idx != tabs.lastIndex) {
                                val x = size.width - 1.dp.toPx()
                                drawLine(DraftingColors.InkFaint,
                                    Offset(x, 0f), Offset(x, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
                            }
                        }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                ) {
                    Text(
                        text = tab.number,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) DraftingColors.Ink else DraftingColors.Text3
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = tab.label.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) DraftingColors.Text else DraftingColors.Text3
                    )
                    if (active) {
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .width(18.dp)
                                .height(1.5.dp)
                                .background(DraftingColors.Ink)
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun SheetTabBarPreview() {
    DraftingTheme {
        SheetTabBar(
            tabs = listOf(
                SheetTab("A-101", "Elev"),
                SheetTab("A-102", "Wallet"),
                SheetTab("PERCH", ""),
                SheetTab("A-103", "Insight"),
                SheetTab("A-104", "Profile")
            ),
            activeIndex = 0,
            onSelect = {},
            perchIndex = 2
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/SheetTabBar.kt
git commit -m "feat(drafting): add SheetTabBar"
```

---

## Task 16: TheodoliteReticle

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/TheodoliteReticle.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Survey-instrument reticle — concentric rings with a crosshair through
 * the centre. Used inside the Scanner FAB and as the icon inside the
 * Smart Scanner button on the dashboard.
 */
@Composable
fun TheodoliteReticle(
    size: Dp = 38.dp,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = DraftingColors.Ink
) {
    Canvas(modifier = modifier.size(size)) {
        val r1 = this.size.minDimension * 0.45f
        val r2 = this.size.minDimension * 0.28f
        val r3 = this.size.minDimension * 0.08f
        val c = Offset(this.size.width * 0.5f, this.size.height * 0.5f)
        val stroke = Stroke(width = 1.2.dp.toPx())

        drawCircle(color, r1, c, style = stroke)
        drawCircle(color, r2, c, style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f))))
        drawCircle(color, r3, c, style = Stroke(width = 1.dp.toPx()))

        // Crosshair
        val reach = this.size.minDimension * 0.5f
        drawLine(color, Offset(c.x, 0f),                  Offset(c.x, c.y - r2),       strokeWidth = 1.dp.toPx())
        drawLine(color, Offset(c.x, c.y + r2),            Offset(c.x, this.size.height), strokeWidth = 1.dp.toPx())
        drawLine(color, Offset(0f, c.y),                  Offset(c.x - r2, c.y),       strokeWidth = 1.dp.toPx())
        drawLine(color, Offset(c.x + r2, c.y),            Offset(this.size.width, c.y), strokeWidth = 1.dp.toPx())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun TheodoliteReticlePreview() {
    DraftingTheme {
        TheodoliteReticle(size = 64.dp)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/TheodoliteReticle.kt
git commit -m "feat(drafting): add TheodoliteReticle"
```

---

## Task 17: ScaleBar

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/ScaleBar.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme

/**
 * Horizontal scale bar with a hairline-bordered track, a mint fill based
 * on `fraction` (0f..1f), and labeled endpoints. Fill animates in once
 * on first composition.
 */
@Composable
fun ScaleBar(
    label: String,
    fraction: Float,
    startLabel: String,
    midLabel: String,
    endLabel: String,
    modifier: Modifier = Modifier
) {
    val animFrac by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 1500, delayMillis = 400),
        label = "scaleFill"
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = DraftingColors.Text3
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .border(1.dp, DraftingColors.InkSoft)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = animFrac)
                    .fillMaxHeight()
                    .background(DraftingColors.Ink)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(startLabel, style = MaterialTheme.typography.labelSmall, color = DraftingColors.Text3)
            Text(midLabel, style = MaterialTheme.typography.labelSmall, color = DraftingColors.Ink)
            Text(endLabel, style = MaterialTheme.typography.labelSmall, color = DraftingColors.Text3)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426, widthDp = 320)
@Composable
private fun ScaleBarPreview() {
    DraftingTheme {
        ScaleBar(
            label = "Scale · 0 ↔ goal",
            fraction = 0.73f,
            startLabel = "0",
            midLabel = "₹47,820 · 73%",
            endLabel = "₹65,000"
        )
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/ScaleBar.kt
git commit -m "feat(drafting): add ScaleBar"
```

---

## Task 18: YieldDial

**Files:**
- Create: `app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/YieldDial.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.example.coupontracker.ui.drafting.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.drafting.DraftingColors
import com.example.coupontracker.ui.drafting.DraftingTheme
import com.example.coupontracker.ui.drafting.DraftingTokens
import kotlin.math.cos
import kotlin.math.sin

/**
 * Chronograph-style dial. 60 rim ticks (5 major + 55 minor), an outer
 * track ring, a progress arc animated from 0..fraction on first
 * composition, an inner instrument ring, and four cardinal hair-marks
 * at 12 / 3 / 6 / 9 o'clock. Centre content is provided by the caller.
 */
@Composable
fun YieldDial(
    fraction: Float,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(fraction) {
        progress.snapTo(0f)
        progress.animateTo(fraction, animationSpec = tween(durationMillis = 1500, delayMillis = 420))
    }

    Box(modifier = modifier.size(DraftingTokens.DialSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val rOuter = size.minDimension * 0.42f
            val rInner = size.minDimension * 0.32f

            // Rim ticks
            val rTickIn = size.minDimension * 0.46f
            val rTickOut = size.minDimension * 0.485f
            for (i in 0 until 60) {
                val a = (i / 60.0) * Math.PI * 2 - Math.PI / 2
                val major = i % 5 == 0
                val r1 = if (major) rTickIn - 3f else rTickIn
                val op = if (major) 0.55f else 0.18f
                drawLine(
                    DraftingColors.Ink.copy(alpha = op),
                    Offset(cx + (r1 * cos(a)).toFloat(),    cy + (r1 * sin(a)).toFloat()),
                    Offset(cx + (rTickOut * cos(a)).toFloat(), cy + (rTickOut * sin(a)).toFloat()),
                    strokeWidth = if (major) 1.dp.toPx() else 0.7.dp.toPx()
                )
            }

            // Outer track
            drawCircle(
                color = DraftingColors.Ink.copy(alpha = 0.18f),
                radius = rOuter,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2.dp.toPx())
            )

            // Inner instrument rings
            drawCircle(
                color = DraftingColors.Ink.copy(alpha = 0.25f),
                radius = rInner,
                center = Offset(cx, cy),
                style = Stroke(width = 0.8.dp.toPx())
            )
            drawCircle(
                color = Color(0x14FFFFFF),
                radius = rInner - 4.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 0.8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 4f)))
            )

            // Progress arc (top clockwise)
            val sweep = 360f * progress.value
            drawArc(
                color = DraftingColors.Ink,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - rOuter, cy - rOuter),
                size = androidx.compose.ui.geometry.Size(rOuter * 2, rOuter * 2),
                style = Stroke(width = 2.4.dp.toPx())
            )

            // Cardinal hair-marks
            val rHairIn = size.minDimension * 0.46f
            val rHairOut = size.minDimension * 0.43f
            listOf(0.0, Math.PI * 0.5, Math.PI, Math.PI * 1.5).forEach { a ->
                val adj = a - Math.PI / 2
                drawLine(
                    DraftingColors.Ink,
                    Offset(cx + (rHairIn * cos(adj)).toFloat(),  cy + (rHairIn * sin(adj)).toFloat()),
                    Offset(cx + (rHairOut * cos(adj)).toFloat(), cy + (rHairOut * sin(adj)).toFloat()),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        center()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A1426)
@Composable
private fun YieldDialPreview() {
    DraftingTheme {
        YieldDial(fraction = 0.73f, modifier = Modifier.size(240.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("₹47,820", style = MaterialTheme.typography.displayLarge, color = DraftingColors.Text)
                Spacer(Modifier.height(6.dp))
                Text("73% · ₹65,000 GOAL", style = MaterialTheme.typography.labelSmall, color = DraftingColors.Text3)
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/example/coupontracker/ui/drafting/components/YieldDial.kt
git commit -m "feat(drafting): add YieldDial chronograph"
```

---

## Task 19: Final verification

- [ ] **Step 1: Build**

```bash
./gradlew :app:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Render previews**

Open Android Studio's Compose preview pane on each component file. Confirm:
- `TitleBlockPreview` renders three columns with dashed separators + mint bottom edge
- `DimensionLinePreview` shows horizontal spine with arrowheads and "312 mm" label pill
- `NumberedCalloutPreview` shows `[1] FOOD ₹14,200` and the reversed version
- `BuildLogRowPreview` shows rows with sequence + timestamp + body + amount
- `SheetTabBarPreview` shows 4 tabs + perch with active underline
- `TheodoliteReticlePreview` shows concentric rings + crosshair
- `ScaleBarPreview` animates fill on recomposition
- `YieldDialPreview` shows the full dial with progress sweep

- [ ] **Step 3: No commit needed — verification only.**

---

## Self-Review

1. **Spec coverage:**
   - Google Fonts dep → Task 1
   - Font provider certs → Task 2
   - Tokens → Task 3
   - Colors + scheme → Task 4
   - Typography → Task 5
   - Shapes → Task 6
   - Theme composable → Task 7
   - Grid modifier → Task 8
   - SheetStamp / RevisionTriangle → Task 9
   - TitleBlock → Task 10
   - DimensionLine H/V → Task 11
   - NumberedCallout → Task 12
   - StatusCodeChip → Task 13
   - BuildLogRow → Task 14
   - SheetTabBar → Task 15
   - TheodoliteReticle → Task 16
   - ScaleBar → Task 17
   - YieldDial → Task 18
   - Verification gate → Task 19

2. **Placeholder scan:** None. Every step has a code block an engineer can paste.

3. **Type consistency:**
   - `DraftingColors`, `DraftingTokens`, `DraftingTheme`, `DraftingFonts`, `DraftingTypography`, `DraftingShapes` all consistent.
   - `StatusTone` enum used by both `StatusCodeChip` (Task 13) and `BuildLogRow` (Task 14) — signatures match.
   - `SheetTab` data class defined in Task 15 and used only there.
   - `TitleBlockField` defined in Task 10 and used only there.

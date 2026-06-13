# Vault Redesign — Design Spec

Internal codename: **Vault**.
A CRED-led, Airbnb-warm, Instagram-pop redesign of the CouponTracker token system and every screen surface.

Date: 2026-06-13.
Branch: `feature/qwen-multi-coupon-extraction` (this branch).
Status: brainstorm-approved by user; spec written; implementation plan to follow.

## 1. Brand foundation

**Pitch.** Every saved coupon is stored value. Treat the wallet like a credit-card holder, not a list. Dark and hushed, warm enough to feel hospitable in light mode, one mint accent for utility, one sunset gradient reserved for celebratory moments. Editorial New display gives Home + Detail a magazine voice; General Sans handles everything else. Composed motion — wallet stack with overlap, spring-press, shared-element transitions to Detail.

**Three visual pillars.**

1. **Stored value.** Each coupon is a landscape card; the value numeral is the hero. Editorial-style italic display, tabular figures everywhere else.
2. **Hush.** Near-black surfaces in dark; warm bone in light. Hairline 1.dp strokes on every card. Negative space carries weight; nothing competes with the value.
3. **One moment.** Each session has at most one "wow" surface: usually the hero coupon on Home with the sunset-gradient foil. Gradient appears nowhere else.

**Scope.** All visual surfaces, including the Compose token system, every Compose screen, every Compose component, and legacy XML theme/font resources for safety. No new screens, no new flows, no IA changes.

## 2. Color system

Replaces `BrandColors.Light` and `BrandColors.Dark` in `app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt`.

### Dark palette

| Token | Hex | Role |
|---|---|---|
| `Background` | `#0D0C10` | Warm-purple-tinted near-black canvas |
| `Surface` | `#16151B` | Coupon cards, list rows |
| `SurfaceVariant` | `#22212A` | Glass-tinted layer |
| `SurfaceElevated` | `#1E1D24` | Modals, dialogs |
| `OnBackground` | `#F7F4EE` | Warm bone (not white) |
| `OnSurface` | `#E6E2D9` | Body text |
| `OnSurfaceVariant` | `#9C988F` | Secondary text |
| `Muted` | `#6B6859` | Tertiary, placeholders |
| `Stroke` | `#2A2832` | Signature hairline — every card border |
| `Divider` | `#1F1E27` | Within-row separators |
| `Highlight` | `#1A1922` | Hover/press wash |

### Light palette

| Token | Hex | Role |
|---|---|---|
| `Background` | `#F2EDE4` | Warm bone canvas |
| `Surface` | `#FFFCF5` | Coupon cards |
| `SurfaceVariant` | `#EAE4D9` | Sheets, popovers |
| `SurfaceElevated` | `#FFFFFF` | Modals |
| `OnBackground` | `#0D0C10` | Deep ink — mirrors dark bg |
| `OnSurface` | `#1B1A1F` | Body text |
| `OnSurfaceVariant` | `#6E6A60` | Secondary text |
| `Muted` | `#A39E92` | Tertiary |
| `Stroke` | `#D9D2C5` | Hairline |
| `Divider` | `#E6E1D5` | Within-row |
| `Highlight` | `#FAF6EC` | Hover/press |

### Accents (shared semantic)

| Token | Hex | Where |
|---|---|---|
| `Accent` | `#00D69E` | All primary CTAs, active, "saved" |
| `AccentVariant` | `#00B488` | Pressed mint |
| `OnAccent` | `#0D0C10` | Text on mint |
| `AccentContainer` (dark) | `#0E2A22` | Tinted mint badge bg |
| `AccentContainer` (light) | `#D6F5EA` | Tinted mint badge bg |
| `Success` | `#00D69E` | Aliased to mint |
| `Warning` | `#F2C84B` | Warm amber |
| `Error` | `#FF4D6D` | Warm coral |
| `Info` | `#7BB9F2` | Muted, rarely used |

### Sunset gradient (celebratory single-use)

```
linear-gradient(135°, #F58529 0%, #DD2A7B 50%, #8134AF 100%)
```

Allowed surfaces — these and nothing else:

1. Hero coupon foil on Home (top card; high-value or favorited).
2. "You saved $X" lifetime-savings badge.
3. Redeemed-state celebration overlay (200ms sweep).
4. App icon (adaptive icon foreground).

Forbidden anywhere else: buttons, app bars, nav, backgrounds, dividers, text, FAB.

### Usage discipline

- At most 2 mint surfaces visible per screen.
- 1.dp Hairline Stroke on every card, input, section divider.
- Status bar matches `Background` exactly.
- No mid-tones; palette is bright accent on hushed surface.

## 3. Typography

### Licensing

Fonts under SIL OFL 1.1; safe to bundle commercially.

- **Display:** Instrument Serif (Regular + Italic). Editorial high-contrast serif.
- **Body:** Plus Jakarta Sans (variable, 200-800). Warm geometric sans.

Both available from Google Fonts as `.ttf` downloads.

### Bundled font resources

```
app/src/main/res/font/
  instrument_serif_regular.ttf
  instrument_serif_italic.ttf
  plus_jakarta_sans_variable.ttf
  instrument_serif.xml
  plus_jakarta_sans.xml
```

Compose font families exposed in `app/src/main/kotlin/com/example/coupontracker/ui/theme/BrandStyleGuide.kt`:

```kotlin
val DisplayFamily = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic,  FontWeight.Normal, FontStyle.Italic)
)
val BodyFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_variable,
         variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    // 500 / 600 / 700 bindings via FontVariation
)
```

### Scale (replaces `BrandTypography`)

| Style | Family | Weight | Size / Line / Tracking | Notes |
|---|---|---|---|---|
| `DisplayHero` (new) | Instrument Serif | Italic | 72 / 76 / -2.0 | Coupon value only |
| `DisplayLarge` | Instrument Serif | Regular | 56 / 60 / -1.5 | Page wordmarks |
| `DisplayMedium` | Instrument Serif | Regular | 44 / 48 / -1.0 | Empty-state hero |
| `DisplaySmall` | Instrument Serif | Regular | 36 / 40 / -0.75 | Section heroes |
| `HeadlineLarge` | Instrument Serif | Regular | 32 / 36 / -0.5 | Screen titles |
| `HeadlineMedium` | Instrument Serif | Regular | 26 / 32 / -0.25 | Sub-screen headings |
| `HeadlineSmall` | Instrument Serif | Regular | 22 / 28 / 0 | Dialog titles |
| `TitleLarge` | Plus Jakarta Sans | SemiBold (600) | 22 / 28 / -0.1 | Detail card brand |
| `TitleMedium` | Plus Jakarta Sans | SemiBold (600) | 17 / 24 / 0 | List card brand, sheet titles |
| `TitleSmall` | Plus Jakarta Sans | SemiBold (600) | 15 / 20 / 0.1 | Settings row titles |
| `BodyLarge` | Plus Jakarta Sans | Regular (400) | 16 / 24 / 0.15 | Detail body |
| `BodyMedium` | Plus Jakarta Sans | Regular (400) | 14 / 20 / 0.15 | List metadata |
| `BodySmall` | Plus Jakarta Sans | Regular (400) | 12 / 16 / 0.2 | Helper text |
| `LabelLarge` | Plus Jakarta Sans | SemiBold (600) | 14 / 18 / 0.4 | Buttons (sentence case) |
| `LabelMedium` | Plus Jakarta Sans | Medium (500) | 12 / 16 / 1.4 | Small-caps section headers ("EXPIRING SOON") |
| `LabelSmall` | Plus Jakarta Sans | Medium (500) | 10 / 14 / 1.6 | Card metadata badges ("CODE", "EXPIRES") |

### Numerals

```kotlin
fun TextStyle.tabularNumerals(): TextStyle = this.copy(fontFeatureSettings = "tnum")
```

Apply to all `Body*`, `Title*`, `Label*` styles when rendering numeric content.

### Voice rules

- Instrument Serif Italic DisplayHero — coupon value numeral only.
- Instrument Serif Regular — page wordmarks, screen titles, dialog titles.
- Plus Jakarta Sans SemiBold — brand names, button text, settings titles.
- Plus Jakarta Sans Medium small-caps with wide tracking — section headers, card meta labels.
- Plus Jakarta Sans Regular — body, descriptions.

### Migrations

- Delete `app/src/main/res/font/roboto_regular.ttf`, `roboto.xml`, `uber_move.xml`.
- Audit `values/themes.xml` + `values-night/themes.xml` for `@font/uber_move` and `?textAppearanceBody*` overrides; replace or remove.

## 4. Shapes, spacing, elevation, motion

### Shapes (replaces `BrandShapes`)

| Token | Radius |
|---|---|
| `Sharp` | 0.dp |
| `ExtraSmall` | 4.dp |
| `Small` | 8.dp |
| `Medium` | 12.dp |
| `Large` | 16.dp |
| `XLarge` | 20.dp |
| `CouponCard` | 14.dp |
| `Pill` | 50% |

### Spacing (extends `BrandSpacing`)

Keep existing 2/4/8/12/16/24/32/48/64. Add:

| Token | Value | Purpose |
|---|---|---|
| `Hairline` | 1.dp | Standard stroke width |
| `Hairline2` | 1.5.dp | Hero foil gradient stroke |
| `ContentEdge` | 24.dp | Screen-edge horizontal padding |
| `WalletCardOverlap` | -16.dp | Stack peek negative offset |
| `WalletCardSpacing` | 24.dp | Un-overlapped vertical gap |
| `HeroSpacing` | 48.dp | Above page wordmark |

Semantic token updates: `CardPadding` 16 → 20; `ButtonPadding` h20/v14; `SectionSpacing` 24 → 32.

### Elevation (replaces `BrandElevation`)

Depth via hairline stroke + tonal layering, not shadow.

| Token | Value | Used on |
|---|---|---|
| `None` | 0.dp | Cards, app bar, list rows, inputs |
| `Subtle` | 1.dp | Bottom sheets expanded |
| `Lifted` | 2.dp | Dialogs, dropdowns |
| `Hero` | 4.dp | FAB, redeem celebration |

Component tokens: `CardElevation = None`, `FabElevation = Hero`, `BottomSheetElevation = Subtle`, `DialogElevation = Lifted`, `AppBarElevation = None`.

### Motion (replaces `BrandAnimationDuration`)

| Token | ms | Purpose |
|---|---|---|
| `VeryFast` | 80 | Button press scale |
| `Fast` | 180 | Small UI transitions |
| `Medium` | 280 | Sheet enter, dialog enter |
| `Slow` | 420 | Shared-element coupon transition |
| `VerySlow` | 600 | Home wallet first reveal total |

```kotlin
object BrandEasing {
    val Standard   = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
    val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val Decelerate = CubicBezierEasing(0.0f, 0.0f, 0.2f, 1.0f)
}

object BrandSpring {
    val Press  = spring<Float>(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
    val Reveal = spring<IntOffset>(dampingRatio = 0.78f, stiffness = Spring.StiffnessLow)
}
```

Component motion: `PressScale = 0.97`; ripple uses `Accent.copy(alpha = 0.18f)`; list entry stagger 60ms offset capped at 8; shared-axis 420ms `Emphasized`; redeem celebration 200ms one-shot sunset sweep.

### Glassmorphism (API-aware)

`GlassSurface` composable branches on `Build.VERSION.SDK_INT`:

- API 31+: `Modifier.blur(24.dp)` + `SurfaceVariant.copy(alpha = 0.72f)` + Hairline Stroke.
- API 24-30: `SurfaceVariant.copy(alpha = 0.92f)` + dithered noise overlay (3 KB asset at `res/drawable-nodpi/glass_noise.webp`) + Hairline Stroke.

Used on bottom sheets, hero foil layer, Detail background, snackbars. Not on regular cards, app bars, inputs.

## 5. Coupon card spec

### Anatomy

- Aspect ratio 16:10.
- Width `fillMaxWidth` within `ContentEdge`.
- Shape `BrandShapes.CouponCard` (14.dp).
- Background `BrandColors.Surface`.
- Border `Hairline` × `Stroke`.
- Padding 20.dp.

### Content slots

| Slot | Position | Style |
|---|---|---|
| Brand chip | Top-left, 40 × 24, ExtraSmall | Extracted brand color bg, centered initial letter, DisplayFamily Regular 14sp |
| Brand name | Top-left, right of chip | TitleMedium |
| Value (hero) | Bottom-left | DisplayHero |
| EXPIRES label | Bottom-right | LabelSmall small-caps `OnSurfaceVariant` |
| Expiry date | Bottom-right, under label | BodyMedium tabular |
| CODE label + value | Above bottom | LabelSmall + masked BodyMedium tabular; tap to reveal; 4-second auto-mask |

### States

| State | Visual |
|---|---|
| Default | Surface + Hairline Stroke |
| Pressed | scale 0.97, Stroke briefly `Accent.copy(0.5)` |
| Selected | 2.dp `Accent` stroke |
| Redeemed | alpha 0.4 + diagonal `REDEEMED` watermark + sunset sweep on redeem tap |
| Expired | alpha 0.4 + diagonal `EXPIRED` watermark, tinted Warning |
| Loading | skeleton + shimmer sweep 1200ms |
| Hero foil | 1.5.dp gradient stroke + blurred gradient bleed ~12.dp outside |

### Variants

- `WalletStackCard` — Home wallet, vertical stack with -16.dp overlap, only topmost full.
- `CarouselCard` — Detail "related", batch capture preview, horizontal scroll.
- `PreviewCard` — capture/import preview pre-save, dashed Stroke 4-4.
- `ListCard` — analytics/history fallback.

### Signature

```kotlin
@Composable
fun CouponCard(
    state: CouponCardState,
    variant: CouponCardVariant,
    isHero: Boolean = false,
    onTap: () -> Unit,
    onRedeem: (() -> Unit)? = null,
    modifier: Modifier = Modifier
)
```

Lives at `app/src/main/kotlin/com/example/coupontracker/ui/components/CouponCard.kt`.

## 6. Screen-by-screen application

| Screen | Change |
|---|---|
| `HomeScreen` | "Vault" wordmark DisplayLarge top; WalletStackCard stack below; hero foil on top; editorial empty state; FAB removed in model-setup state |
| `ScannerScreen`, `UnifiedCameraScreen`, `SmartCameraScreen`, `SmartCaptureScreen` | Mint shutter 72dp + Hairline ring; GlassSurface bottom sheets |
| `UnifiedUploadScreen`, `BatchScannerScreen` | Editorial empty state; PreviewCard list; mint LabelLarge save |
| `MultiCouponPreviewScreen`, `MultiCouponReviewScreen` | Grid of PreviewCards; selection toggles to 2.dp Accent stroke; GlassSurface bulk action bar |
| `CouponDetailScreen` | Hero WalletStackCard at top; meta rows with LabelMedium small-caps left + body right; mint redeem button with celebration animation |
| `CouponFormScreen`, `ManualEntryScreen` | BrandTextField everywhere; LabelMedium small-caps above each input; GlassSurface save bar; URL absorbed into scrollable form |
| `SettingsScreen` | HeadlineLarge title; 32.dp SectionSpacing; LabelMedium group titles; collapsible Advanced section for dev/admin entries |
| `OnboardingScreen` | DisplayMedium hero per step; mint dots; tertiary secondary |
| `AnalyticsScreen` | LabelMedium labels + DisplaySmall tabular values; Accent bars, Stroke gridlines |
| `ExtractionDashboardScreen` | Hairline cards; LabelMedium category labels |
| `PrivacyPolicyScreen` | BodyLarge body, HeadlineSmall headings, 32.dp ContentEdge, 1.5× line height |
| `LicenseGateScreen` | DisplayMedium centered headline, BodyLarge description, mint primary |
| `ApiTestScreen` | "ADVANCED" LabelMedium badge; otherwise Settings treatment |
| `QRScannerScreen` | 1.5.dp mint reticle with 4dp corner segments |

### Components

| File | Change |
|---|---|
| `CouponCard.kt` (new) | Section 5 spec |
| `GlassSurface.kt` (new) | API-aware glass background |
| `BrandButton.kt` (new) | Three-tier wrapper (mint primary, ghost secondary, text tertiary) |
| `BrandTextField.kt` (new) | Canonical input — Hairline + Stroke, small-caps label above |
| `BrandTopBar.kt` (new) | Flush app bar, Instrument Serif title |
| `SimplifiedCaptureBottomSheet.kt` / `SimpleCaptureBottomSheet.kt` | Repaint to GlassSurface; consolidate to one |
| `ExtractionDashboard.kt` | Repaint per AnalyticsScreen |
| `TooltipOverlay.kt` | SurfaceVariant + Stroke + small-caps label |
| `ImagePreviewDialog.kt`, `ExtractionFeedbackDialog.kt`, `DataSafetyDialog.kt` | DialogElevation Lifted, Large shape, GlassSurface backdrop |
| `CouponAdapter.kt` (legacy XML) | Stays; Compose replacement in follow-up |

### Legacy XML

- `res/values/colors.xml` + `values-night/colors.xml` repointed to mint accent and warm neutrals.
- `res/values/themes.xml` + `values-night/themes.xml` updated to new color attrs; remove `?attr/fontFamily` references to deleted Roboto/UberMove.

## 7. Implementation considerations

### Bundle size

- New fonts: Instrument Serif Regular (~80 KB) + Italic (~80 KB) + Plus Jakarta Sans variable (~240 KB) + glass noise (~3 KB) = ~400 KB.
- Removed: `roboto_regular.ttf` (~165 KB).
- Net: **+235 KB APK**.

### API gating

`Modifier.blur` requires API 31. `GlassSurface` branches internally — no call site touches `Build.VERSION`. All other tokens work on minSdk 24.

### Font binary acquisition

Cannot fetch fonts during this implementation. Two options:

1. User downloads from `fonts.google.com` and drops `.ttf` files into `app/src/main/res/font/`. Implementation lands with the XML wiring and `Font(...)` declarations; the binaries are added separately.
2. Gradle task that fetches on first build (requires network during build).

First land uses option 1; option 2 deferred.

### Testing

- Existing unit tests reach `MaterialTheme` via composition; no semantic changes expected.
- Screenshot/golden tests will need regeneration — new screenshots become goldens.
- Small-caps labels need explicit `Modifier.semantics { contentDescription = ... }` so TalkBack doesn't spell them out letter by letter.

### Risks

1. Font licensing — SIL OFL 1.1; confirm compatibility with app distribution license.
2. Editorial display at 72sp on small densities — `CouponCard` auto-resizes the value when overflow imminent.
3. Legacy XML screens still in production routes will look stylistically inconsistent until ported in a follow-up; the colors.xml + themes.xml repointing reduces drift to a minimum.
4. Brand chip color extraction requires screenshot pixel access; fall back to `Stroke` if no screenshot.

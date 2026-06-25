package com.example.coupontracker.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Layout treatment used to render a [CouponCard]. See spec §5 (Variants).
 *
 * - [WalletStack] is the Home wallet treatment — full 16:10 when active, collapses to a
 *   60.dp peek (brand chip + brand name only) when the host clamps the height.
 * - [Carousel] renders identically to the default treatment; the carousel wrapper
 *   handles horizontal layout outside of the card itself.
 * - [Preview] replaces the solid Hairline border with a 4-4 dashed Stroke for
 *   pre-save capture/import surfaces.
 * - [List] is the analytics/history fallback — same anatomy as [WalletStack] active.
 */
enum class CouponCardVariant { WalletStack, Carousel, Preview, List }

/**
 * Visual state of a coupon card. See spec §5 (States).
 *
 * `Pressed` is intentionally not part of this hierarchy — it is a transient,
 * interaction-driven state that the card derives internally from its own
 * `MutableInteractionSource`.
 */
sealed interface CouponCardState {
    data object Default : CouponCardState
    data object Selected : CouponCardState
    data object Redeemed : CouponCardState
    data object Expired : CouponCardState
    data object Loading : CouponCardState
}

/**
 * Render model — the data a [CouponCard] needs to draw itself.
 *
 * @param brandName Brand display name, rendered with [com.example.coupontracker.ui.theme.BrandTypography.TitleMedium].
 * @param brandInitial Letter shown inside the 40×24 brand chip.
 * @param brandColor Extracted brand color for the chip background; falls back to
 *        [com.example.coupontracker.ui.theme.BrandColors.Stroke] when null
 *        (e.g. no screenshot available for color extraction).
 * @param valueLabel Pre-formatted value string ("$50", "20% OFF", "Free"). The card
 *        renders it verbatim as the hero numeral with [com.example.coupontracker.ui.theme.BrandTypography.DisplayHero].
 * @param code The redeem code; rendered masked until the user taps to reveal.
 * @param expiresAt Pre-formatted expiry date ("Aug 14, 2026").
 */
data class CouponCardModel(
    val brandName: String,
    val brandInitial: Char,
    val brandColor: Color?,
    val valueLabel: String,
    val code: String,
    val expiresAt: String,
    val statusLabel: String? = null,
    val statusInProgress: Boolean = false,
    val state: CouponCardState = CouponCardState.Default,
    val codeIsActionable: Boolean = true,
)

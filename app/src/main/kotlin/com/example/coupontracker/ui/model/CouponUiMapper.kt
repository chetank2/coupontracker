package com.example.coupontracker.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.coupontracker.R
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.ui.components.CouponCardModel
import com.example.coupontracker.ui.components.CouponCardState
import com.example.coupontracker.ui.components.DateFormatter
import java.util.Date

@Composable
fun Coupon.toCouponCardModel(
    displayDescription: String = DescriptionUtils.formatDisplayDescription(
        description = description,
        storeName = storeName,
        redeemCode = redeemCode,
    ),
): CouponCardModel {
    val initial = storeName.firstOrNull { it.isLetterOrDigit() } ?: DEFAULT_COUPON_INITIAL
    return CouponCardModel(
        brandName = storeName,
        brandInitial = initial,
        brandColor = null,
        valueLabel = cardOfferSummary(displayDescription),
        code = redeemCode.orEmpty(),
        expiresAt = DateFormatter.formatShort(expiryDate),
        statusLabel = cleanupStatusLabel(),
        statusInProgress = cleanupStatus == Coupon.CleanupStatus.PENDING ||
            cleanupStatus == Coupon.CleanupStatus.RUNNING,
        state = cardState(),
    )
}

fun Coupon.cardState(now: Date = Date()): CouponCardState {
    return when {
        status.equals(Coupon.Status.USED, ignoreCase = true) -> CouponCardState.Redeemed
        expiryDate?.before(now) == true -> CouponCardState.Expired
        else -> CouponCardState.Default
    }
}

@Composable
private fun Coupon.cleanupStatusLabel(): String? {
    return when (cleanupStatus) {
        Coupon.CleanupStatus.PENDING -> stringResource(R.string.coupon_status_queued)
        Coupon.CleanupStatus.RUNNING -> stringResource(R.string.coupon_status_cleaning)
        Coupon.CleanupStatus.FAILED -> stringResource(R.string.coupon_status_needs_clean)
        else -> null
    }
}

@Composable
private fun Coupon.cardOfferSummary(displayDescription: String): String {
    val cashback = getCashbackDisplayText()
        .replace(WHITESPACE_REGEX, " ")
        .trim()
        .takeIf { it.isNotBlank() && it.length <= MAX_CARD_OFFER_LENGTH && !it.equals("0.0", ignoreCase = true) }

    if (cashback != null) return cashback

    val normalizedDescription = displayDescription
        .replace(WHITESPACE_REGEX, " ")
        .trim()
    val matchedOffer = OFFER_SUMMARY_REGEX.find(normalizedDescription)?.value
        ?.replace(WHITESPACE_REGEX, " ")
        ?.trim()
        ?.take(MAX_CARD_OFFER_LENGTH)

    return matchedOffer?.ifBlank { null }
        ?: when {
            normalizedDescription.contains("voucher", ignoreCase = true) -> stringResource(R.string.coupon_saved_voucher)
            normalizedDescription.contains("coupon", ignoreCase = true) -> stringResource(R.string.coupon_saved_coupon)
            else -> stringResource(R.string.coupon_saved_offer)
        }
}

private const val DEFAULT_COUPON_INITIAL = 'C'
private const val MAX_CARD_OFFER_LENGTH = 44
private val WHITESPACE_REGEX = Regex("\\s+")
private val OFFER_SUMMARY_REGEX = Regex(
    pattern = """(?i)(₹\s*\d[\d,]*(?:\s*off)?|\d+\s*%\s*(?:off|cashback)?|flat\s+[^.]{1,32}|free\s+[^.]{1,32}|save\s+[^.]{1,32})""",
)

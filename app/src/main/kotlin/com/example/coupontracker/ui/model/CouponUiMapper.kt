package com.example.coupontracker.ui.model

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.coupontracker.R
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.ui.components.CouponCardModel
import com.example.coupontracker.ui.components.CouponCardState
import com.example.coupontracker.ui.components.DateFormatter
import com.example.coupontracker.util.CleanupStatusFreshness
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
        code = codeDisplayText(),
        expiresAt = expiryDisplayText(),
        statusLabel = cleanupStatusLabel(),
        statusInProgress = CleanupStatusFreshness.isFreshInProgress(this),
        state = cardState(),
        codeIsActionable = !redeemCode.isNullOrBlank(),
    )
}

@Composable
private fun Coupon.codeDisplayText(): String {
    return when {
        !redeemCode.isNullOrBlank() -> redeemCode
        codeState == Coupon.CodeState.NO_CODE_NEEDED -> stringResource(R.string.coupon_no_code_needed)
        codeState == Coupon.CodeState.NOT_VISIBLE -> stringResource(R.string.coupon_field_not_visible)
        else -> ""
    }
}

@Composable
private fun Coupon.expiryDisplayText(): String {
    return when {
        expiryDate != null -> DateFormatter.formatShort(expiryDate)
        expiryState == Coupon.ExpiryState.NOT_VISIBLE -> stringResource(R.string.coupon_field_not_visible)
        else -> ""
    }
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
    val freshInProgress = CleanupStatusFreshness.isFreshInProgress(this)
    return when (cleanupStatus) {
        Coupon.CleanupStatus.PENDING -> if (freshInProgress) {
            stringResource(R.string.coupon_status_queued)
        } else {
            stringResource(R.string.coupon_status_needs_clean)
        }
        Coupon.CleanupStatus.RUNNING -> if (freshInProgress) {
            stringResource(R.string.coupon_status_cleaning)
        } else {
            stringResource(R.string.coupon_status_needs_clean)
        }
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
        ?: normalizedDescription.takeIf { it.isSpecificOfferText() }
            ?.take(MAX_CARD_OFFER_LENGTH)
        ?: when {
            normalizedDescription.contains("voucher", ignoreCase = true) -> stringResource(R.string.coupon_saved_voucher)
            normalizedDescription.contains("coupon", ignoreCase = true) -> stringResource(R.string.coupon_saved_coupon)
            else -> stringResource(R.string.coupon_saved_offer)
        }
}

private fun String.isSpecificOfferText(): Boolean {
    if (length < MIN_CARD_DESCRIPTION_LENGTH) return false
    if (equals("coupon offer", ignoreCase = true)) return false
    if (equals("saved offer", ignoreCase = true)) return false
    return any(Char::isDigit) || OFFER_KEYWORD_REGEX.containsMatchIn(this)
}

private const val DEFAULT_COUPON_INITIAL = 'C'
private const val MAX_CARD_OFFER_LENGTH = 44
private const val MIN_CARD_DESCRIPTION_LENGTH = 10
private val WHITESPACE_REGEX = Regex("\\s+")
private val OFFER_SUMMARY_REGEX = Regex(
    pattern = """(?i)(₹\s*\d[\d,]*(?:\s*off)?|\d+\s*%\s*(?:off|cashback)?|flat\s+[^.]{1,32}|free\s+[^.]{1,32}|save\s+[^.]{1,32})""",
)
private val OFFER_KEYWORD_REGEX = Regex("""(?i)\b(discount|cashback|off|free|prime|premium|plus|voucher|coupon|membership)\b""")

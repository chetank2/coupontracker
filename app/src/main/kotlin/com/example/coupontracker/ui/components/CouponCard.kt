package com.example.coupontracker.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupontracker.R
import com.example.coupontracker.ui.theme.BrandAnimationDuration
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandSpring
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.ui.theme.CouponTrackerTheme
import com.example.coupontracker.ui.theme.tabularNumerals
import kotlinx.coroutines.delay

@Composable
fun CouponCard(
    model: CouponCardModel,
    state: CouponCardState,
    variant: CouponCardVariant,
    isHero: Boolean = false,
    showBorder: Boolean = true,
    onTap: () -> Unit,
    onRedeem: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = BrandSpring.Press,
        label = "coupon-card-press",
    )
    var revealed by remember(model.code) { mutableStateOf(false) }

    LaunchedEffect(revealed, model.code) {
        if (revealed) {
            delay(4_000)
            revealed = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp)
            .scale(scale),
    ) {
        if (isHero) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(BrandColors.SunsetGradient, BrandShapes.CouponCard)
                    .alpha(0.28f),
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(BrandShapes.CouponCard)
                .background(colors.surface)
                .then(
                    if (!showBorder) {
                        Modifier
                    } else if (variant == CouponCardVariant.Preview) {
                        Modifier.dashedCouponBorder(colors.outline)
                    } else {
                        Modifier.border(
                            BorderStroke(
                                if (state is CouponCardState.Selected) 2.dp else BrandSpacing.Hairline,
                                if (state is CouponCardState.Selected) colors.primary else colors.outline,
                            ),
                            BrandShapes.CouponCard,
                        )
                    },
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onTap,
                )
                .padding(BrandSpacing.CardPadding),
        ) {
            if (state is CouponCardState.Loading) {
                CouponCardSkeleton()
            } else {
                CouponCardContent(
                    model = model,
                    revealed = revealed,
                    onCodeTap = { revealed = true },
                )
            }

            when (state) {
                CouponCardState.Expired -> CouponWatermark(stringResource(R.string.coupon_status_expired), colors.error)
                CouponCardState.Redeemed -> CouponWatermark(stringResource(R.string.coupon_status_redeemed), colors.primary)
                else -> Unit
            }

            if (onRedeem != null && state !is CouponCardState.Loading) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(BrandShapes.ExtraSmall)
                        .clickable(onClick = onRedeem)
                        .background(colors.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "REDEEM",
                        style = BrandTypography.LabelSmall,
                        color = colors.primary,
                    )
                }
            }
        }

        if (isHero && showBorder) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val strokeWidth = BrandSpacing.Hairline2.toPx()
                drawRoundRect(
                    brush = BrandColors.SunsetGradient,
                    size = size,
                    cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                    style = Stroke(width = strokeWidth),
                )
            }
        }
    }
}

@Composable
private fun CouponCardContent(
    model: CouponCardModel,
    revealed: Boolean,
    onCodeTap: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 24.dp)
                    .clip(BrandShapes.ExtraSmall)
                    .background(model.brandColor ?: colors.outline),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = model.brandInitial.uppercaseChar().toString(),
                    style = BrandTypography.LabelLarge,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = model.brandName.ifBlank { "Coupon" },
                style = BrandTypography.TitleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            model.statusLabel?.let { status ->
                Spacer(Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (model.statusInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.primary,
                        )
                    }
                    Text(
                        text = status,
                        style = BrandTypography.LabelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = if (model.valueLabel.isBlank()) "Saved" else model.valueLabel,
                style = BrandTypography.TitleLarge.copy(
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    letterSpacing = 0.sp,
                ),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onCodeTap)
                        .background(colors.surfaceVariant.copy(alpha = 0.62f))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "CODE",
                        style = BrandTypography.LabelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = if (revealed || model.code.isBlank()) {
                            model.code.ifBlank { stringResource(R.string.coupon_no_code) }
                        } else {
                            "••••••"
                        },
                        style = BrandTypography.BodyMedium.tabularNumerals(),
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "EXPIRES",
                        style = BrandTypography.LabelSmall,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = model.expiresAt.ifBlank { stringResource(R.string.coupon_no_expiry) },
                        style = BrandTypography.BodyMedium.tabularNumerals(),
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun CouponCardSkeleton() {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "coupon-skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.32f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "coupon-skeleton-alpha",
    )
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 40.dp, height = 24.dp)
                    .clip(BrandShapes.ExtraSmall)
                    .background(colors.surfaceVariant.copy(alpha = alpha)),
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .height(18.dp)
                    .weight(1f)
                    .clip(BrandShapes.Small)
                    .background(colors.surfaceVariant.copy(alpha = alpha)),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier
                    .height(58.dp)
                    .fillMaxWidth(0.56f)
                    .clip(BrandShapes.Small)
                    .background(colors.surfaceVariant.copy(alpha = alpha)),
            )
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .height(34.dp)
                        .fillMaxWidth(0.38f)
                        .clip(BrandShapes.Small)
                        .background(colors.surfaceVariant.copy(alpha = alpha)),
                )
                Box(
                    Modifier
                        .height(34.dp)
                        .width(96.dp)
                        .clip(BrandShapes.Small)
                        .background(colors.surfaceVariant.copy(alpha = alpha)),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.CouponWatermark(text: String, color: Color) {
    Text(
        text = text,
        style = BrandTypography.HeadlineLarge,
        color = color.copy(alpha = 0.32f),
        modifier = Modifier
            .align(Alignment.Center)
            .rotate(-18f)
            .alpha(0.78f),
    )
}

private fun Modifier.dashedCouponBorder(color: Color): Modifier = this.then(
    Modifier.drawDashedBorder(color),
)

private fun Modifier.drawDashedBorder(color: Color): Modifier = this.then(
    Modifier
        .background(Color.Transparent)
        .then(
            Modifier,
        )
        .drawWithCacheCompat(color),
)

private fun Modifier.drawWithCacheCompat(color: Color): Modifier =
    drawWithCache {
        val stroke = Stroke(
            width = BrandSpacing.Hairline.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
        )
        onDrawWithContent {
            drawContent()
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
                style = stroke,
            )
        }
    }

@Preview(name = "CouponCard — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "CouponCard — Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun CouponCardPreview() {
    CouponTrackerTheme {
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(24.dp),
        ) {
            CouponCard(
                model = CouponCardModel(
                    brandName = "Domino's",
                    brandInitial = 'D',
                    brandColor = Color(0xFF00D69E),
                    valueLabel = "50% OFF",
                    code = "PIZZA50",
                    expiresAt = "Jun 30",
                ),
                state = CouponCardState.Default,
                variant = CouponCardVariant.WalletStack,
                isHero = true,
                onTap = {},
            )
        }
    }
}

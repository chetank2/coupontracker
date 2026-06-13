package com.example.coupontracker.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.ui.theme.CouponTrackerTheme

enum class BrandButtonTier { Primary, Secondary, Tertiary }

@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tier: BrandButtonTier = BrandButtonTier.Primary,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    val padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)
    val colors = MaterialTheme.colorScheme
    when (tier) {
        BrandButtonTier.Primary -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = BrandShapes.Medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
            contentColor   = colors.onPrimary,
            ),
            contentPadding = padding,
        ) { BrandButtonContent(text = text, leadingIcon = leadingIcon) }
        BrandButtonTier.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = BrandShapes.Medium,
            border = BorderStroke(BrandSpacing.Hairline, colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface),
            contentPadding = padding,
        ) { BrandButtonContent(text = text, leadingIcon = leadingIcon) }
        BrandButtonTier.Tertiary -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.primary),
            contentPadding = padding,
        ) { BrandButtonContent(text = text, leadingIcon = leadingIcon) }
    }
}

@Composable
private fun BrandButtonContent(
    text: String,
    leadingIcon: ImageVector?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(text, style = BrandTypography.LabelLarge)
    }
}

@Preview(name = "BrandButton — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "BrandButton — Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun BrandButtonPreview() {
    CouponTrackerTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandButton(
                text = "Primary action",
                onClick = {},
                tier = BrandButtonTier.Primary,
            )
            BrandButton(
                text = "Secondary action",
                onClick = {},
                tier = BrandButtonTier.Secondary,
            )
            BrandButton(
                text = "Tertiary action",
                onClick = {},
                tier = BrandButtonTier.Tertiary,
            )
        }
    }
}

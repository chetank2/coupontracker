package com.example.coupontracker.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.ui.theme.CouponTrackerTheme

/**
 * Canonical Vault text input.
 *
 * Renders a small-caps label above a hairline-bordered field. The visible label is
 * uppercased for the editorial small-caps treatment defined in spec §3, while the
 * accessibility content description preserves the original lowercase string so
 * TalkBack does not spell out the letters one by one.
 */
@Composable
fun BrandTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val sanitisedLabel = remember(label) { label.lowercase() }
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = BrandTypography.LabelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .padding(bottom = 6.dp)
                .semantics { contentDescription = sanitisedLabel },
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            textStyle = BrandTypography.BodyLarge.copy(color = colors.onSurface),
            modifier = Modifier
                .fillMaxWidth()
                .clip(BrandShapes.Medium)
                .background(colors.surface)
                .border(
                    BorderStroke(BrandSpacing.Hairline, colors.outline),
                    BrandShapes.Medium,
                )
                .padding(PaddingValues(horizontal = 16.dp, vertical = 14.dp)),
            decorationBox = { innerTextField ->
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = BrandTypography.BodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
                innerTextField()
            },
        )
    }
}

@Preview(name = "BrandTextField - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "BrandTextField - Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun BrandTextFieldPreview() {
    CouponTrackerTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(BrandSpacing.ContentEdge),
        ) {
            BrandTextField(
                value = "",
                onValueChange = {},
                label = "Email",
                placeholder = "you@example.com",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

package com.example.coupontracker.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.coupontracker.ui.theme.BrandColors
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.ui.theme.CouponTrackerTheme

/**
 * BrandTopBar — flush, no elevation, editorial title.
 *
 * Vault top bar that sits on the [BrandColors.Background] with no surface tint and no
 * elevation overlay. Title uses [BrandTypography.HeadlineSmall] in the Display family
 * for an editorial feel; action icons fall back to [BrandColors.OnSurfaceVariant] so
 * they read as secondary affordances next to the title.
 */
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
        title = {
            Text(
                text = title,
                style = BrandTypography.HeadlineSmall,
                color = BrandColors.OnBackground,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = BrandColors.Background,
            scrolledContainerColor = BrandColors.Background,
            navigationIconContentColor = BrandColors.OnBackground,
            titleContentColor = BrandColors.OnBackground,
            actionIconContentColor = BrandColors.OnSurfaceVariant,
        ),
    )
}

@Preview(name = "BrandTopBar — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "BrandTopBar — Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun BrandTopBarPreview() {
    CouponTrackerTheme {
        BrandTopBar(
            modifier = Modifier
                .background(BrandColors.Background)
                .fillMaxWidth(),
            title = "Vault",
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
        )
    }
}

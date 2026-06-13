package com.example.coupontracker.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.CouponTrackerTheme

@Composable
fun WalletStack(
    coupons: List<CouponCardModel>,
    modifier: Modifier = Modifier,
    activeIndex: Int = 0,
    onCouponTap: (Int) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium),
    ) {
        itemsIndexed(coupons) { index, model ->
            CouponCard(
                model = model,
                state = CouponCardState.Default,
                variant = CouponCardVariant.WalletStack,
                isHero = index == activeIndex,
                onTap = { onCouponTap(index) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "WalletStack — Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "WalletStack — Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun WalletStackPreview() {
    CouponTrackerTheme {
        WalletStack(
            coupons = listOf(
                CouponCardModel("Domino's", 'D', Color(0xFF00D69E), "50% OFF", "PIZZA50", "Jun 30"),
                CouponCardModel("CRED", 'C', Color(0xFF0D0C10), "₹250", "CRED250", "Jul 11"),
                CouponCardModel("TrueBasics", 'T', Color(0xFF00A86B), "20% OFF", "TRUE20", "Aug 4"),
            ),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(BrandSpacing.ContentEdge),
            onCouponTap = {},
        )
    }
}

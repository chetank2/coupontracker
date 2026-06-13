package com.example.coupontracker.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.ui.components.BrandButton
import com.example.coupontracker.ui.components.BrandTextField
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
import com.example.coupontracker.ui.viewmodel.MultiCouponReviewViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCouponReviewScreen(
    onConfirm: (List<org.json.JSONObject>) -> Unit,
    vm: MultiCouponReviewViewModel = hiltViewModel()
) {
    val state = vm.uiState.collectAsState().value
    Scaffold(
        topBar = {
            BrandTopBar(title = "Review coupons")
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(BrandSpacing.ContentEdge),
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium),
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium),
            ) {
                items(state.coupons, key = { it.id }) { item ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = BrandShapes.Large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(BrandSpacing.CardPadding),
                            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small),
                            ) {
                                Checkbox(
                                    checked = item.accepted,
                                    onCheckedChange = { vm.toggle(item.id) },
                                )
                                Text(
                                    text = "Include coupon",
                                    style = BrandTypography.TitleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            BrandTextField(
                                value = item.canonical.optString("storeName"),
                                onValueChange = { vm.edit(item.id, "storeName", it) },
                                label = "Store",
                            )
                            BrandTextField(
                                value = item.canonical.optString("redeemCode"),
                                onValueChange = { vm.edit(item.id, "redeemCode", it) },
                                label = "Code",
                            )
                            BrandTextField(
                                value = item.canonical.optString("expiryDate"),
                                onValueChange = { vm.edit(item.id, "expiryDate", it) },
                                label = "Expiry",
                            )
                        }
                    }
                }
            }
            BrandButton(
                text = "Save selected",
                onClick = { onConfirm(vm.accepted()) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

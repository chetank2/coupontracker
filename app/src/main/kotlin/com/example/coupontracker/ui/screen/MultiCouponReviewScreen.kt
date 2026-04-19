package com.example.coupontracker.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.ui.viewmodel.MultiCouponReviewViewModel

@Composable
fun MultiCouponReviewScreen(
    onConfirm: (List<org.json.JSONObject>) -> Unit,
    vm: MultiCouponReviewViewModel = hiltViewModel()
) {
    val state = vm.uiState.collectAsState().value
    Column(Modifier.fillMaxWidth()) {
        LazyColumn {
            items(state.coupons, key = { it.id }) { item ->
                Card(Modifier.padding(8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Checkbox(checked = item.accepted, onCheckedChange = { vm.toggle(item.id) })
                        OutlinedTextField(
                            value = item.canonical.optString("storeName"),
                            onValueChange = { vm.edit(item.id, "storeName", it) },
                            label = { Text("Store") }
                        )
                        OutlinedTextField(
                            value = item.canonical.optString("redeemCode"),
                            onValueChange = { vm.edit(item.id, "redeemCode", it) },
                            label = { Text("Code") }
                        )
                        OutlinedTextField(
                            value = item.canonical.optString("expiryDate"),
                            onValueChange = { vm.edit(item.id, "expiryDate", it) },
                            label = { Text("Expiry") }
                        )
                    }
                }
            }
        }
        Button(onClick = { onConfirm(vm.accepted()) }) { Text("Save selected") }
    }
}

package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.coupontracker.data.model.Coupon

@Composable
fun RepairNeededDialog(
    coupon: Coupon,
    onDismiss: () -> Unit,
    onAcknowledge: () -> Unit
) {
    if (!coupon.needsAttention) {
        return
    }

    val evidence = coupon.storeNameEvidence.take(3)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onAcknowledge()
                onDismiss()
            }) {
                Text("Acknowledge")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        },
        title = {
            Text("Review store name", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "We could not fully verify the store name from the current signals.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Detected value: ${coupon.storeName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )

                if (coupon.storeNameSource != null) {
                    Text(
                        text = "Primary source: ${coupon.storeNameSource}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                if (evidence.isEmpty()) {
                    Text(
                        text = "No strong signals were available.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    evidence.forEachIndexed { index, item ->
                        Text(
                            text = "${index + 1}. $item",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    )
}

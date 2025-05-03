package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.theme.BrandSpacing

/**
 * Bottom sheet for tracking coupon usage
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageTrackingBottomSheet(
    onDismiss: () -> Unit,
    onTrackUsage: (Double) -> Unit,
    coupon: Coupon
) {
    var amountSaved by remember { mutableStateOf(coupon.cashbackAmount.toString()) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BrandSpacing.Medium)
        ) {
            Text(
                text = "Track Coupon Usage",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
            
            Text(
                text = "Coupon: ${coupon.storeName} - ${coupon.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
            
            // Usage count
            if (coupon.usageLimit != null) {
                Text(
                    text = "Usage: ${coupon.usageCount} of ${coupon.usageLimit}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(BrandSpacing.Medium))
            }
            
            // Amount saved input
            OutlinedTextField(
                value = amountSaved,
                onValueChange = { amountSaved = it },
                label = { Text("Amount Saved") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(BrandSpacing.Large))
            
            // Track usage button
            Button(
                onClick = { 
                    val amount = amountSaved.toDoubleOrNull() ?: coupon.cashbackAmount
                    onTrackUsage(amount)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = coupon.usageLimit == null || coupon.usageCount < coupon.usageLimit
            ) {
                Text("Track Usage")
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }
    }
}

package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.CouponFilters

/**
 * Bottom sheet for filtering coupons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterBottomSheet(
    onDismiss: () -> Unit,
    onApplyFilters: (CouponFilters) -> Unit,
    currentFilters: CouponFilters
) {
    var filters by remember { mutableStateOf(currentFilters) }
    
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
                text = "Filter Coupons",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
            
            // Status filter
            Text(
                text = "Status",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BrandSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
            ) {
                FilterChip(
                    selected = filters.status == "All",
                    onClick = { filters = filters.copy(status = "All") },
                    label = { Text("All") }
                )
                
                FilterChip(
                    selected = filters.status == "Active",
                    onClick = { filters = filters.copy(status = "Active") },
                    label = { Text("Active") }
                )
                
                FilterChip(
                    selected = filters.status == "Used",
                    onClick = { filters = filters.copy(status = "Used") },
                    label = { Text("Used") }
                )
                
                FilterChip(
                    selected = filters.status == "Expired",
                    onClick = { filters = filters.copy(status = "Expired") },
                    label = { Text("Expired") }
                )
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Small))
            
            // Platform filter
            Text(
                text = "Platform",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BrandSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
            ) {
                FilterChip(
                    selected = filters.platform == "All",
                    onClick = { filters = filters.copy(platform = "All") },
                    label = { Text("All") }
                )
                
                FilterChip(
                    selected = filters.platform == "Food Delivery",
                    onClick = { filters = filters.copy(platform = "Food Delivery") },
                    label = { Text("Food") }
                )
                
                FilterChip(
                    selected = filters.platform == "E-commerce",
                    onClick = { filters = filters.copy(platform = "E-commerce") },
                    label = { Text("Shopping") }
                )
                
                FilterChip(
                    selected = filters.platform == "Payment",
                    onClick = { filters = filters.copy(platform = "Payment") },
                    label = { Text("Payment") }
                )
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Small))
            
            // Sort options
            Text(
                text = "Sort By",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BrandSpacing.Small),
                horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
            ) {
                FilterChip(
                    selected = filters.sortBy == "Expiry",
                    onClick = { filters = filters.copy(sortBy = "Expiry") },
                    label = { Text("Expiry Date") }
                )
                
                FilterChip(
                    selected = filters.sortBy == "Value",
                    onClick = { filters = filters.copy(sortBy = "Value") },
                    label = { Text("Value") }
                )
                
                FilterChip(
                    selected = filters.sortBy == "Store",
                    onClick = { filters = filters.copy(sortBy = "Store") },
                    label = { Text("Store") }
                )
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
            
            // Priority only switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = BrandSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Priority Coupons Only",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Switch(
                    checked = filters.priorityOnly,
                    onCheckedChange = { filters = filters.copy(priorityOnly = it) }
                )
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Large))
            
            // Apply button
            Button(
                onClick = { onApplyFilters(filters) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Filters")
            }
            
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }
    }
}

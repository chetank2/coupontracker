package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.coupontracker.data.SortOrder
import com.example.coupontracker.ui.model.CouponStatusFilter
import com.example.coupontracker.ui.model.ExpiryRange
import com.example.coupontracker.ui.model.FilterState
import com.example.coupontracker.ui.model.hasActiveFilters
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSortBottomSheet(
    onDismiss: () -> Unit,
    currentSortOrder: SortOrder,
    currentFilterState: FilterState,
    availableStores: List<String>,
    availableCategories: List<String>,
    onApply: (SortOrder, FilterState) -> Unit,
    onReset: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    fun numericToText(value: Double?): String = value?.let { valueToString(it) } ?: ""

    var selectedSortOrder by remember { mutableStateOf(currentSortOrder) }
    var selectedFilterState by remember { mutableStateOf(currentFilterState) }
    var minValueText by remember { mutableStateOf(numericToText(currentFilterState.minValue)) }
    var maxValueText by remember { mutableStateOf(numericToText(currentFilterState.maxValue)) }

    val canReset = selectedFilterState.hasActiveFilters(includeSearchQuery = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            FilterSheetHeader(
                canReset = canReset,
                onReset = {
                    selectedSortOrder = SortOrder.EXPIRY_DATE
                    selectedFilterState = FilterState()
                    minValueText = ""
                    maxValueText = ""
                    onReset()
                },
                onClose = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SortSection(
                selectedSortOrder = selectedSortOrder,
                onSortSelected = { selectedSortOrder = it }
            )

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            if (availableStores.isNotEmpty()) {
                StoreSection(
                    availableStores = availableStores,
                    selectedStores = selectedFilterState.selectedStores,
                    onToggle = { store ->
                    selectedFilterState = selectedFilterState.toggleStore(store)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            if (availableCategories.isNotEmpty()) {
                CategorySection(
                    availableCategories = availableCategories,
                    selectedCategories = selectedFilterState.selectedCategories,
                    onToggle = { category ->
                        selectedFilterState = selectedFilterState.toggleCategory(category)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))
            }

            StatusSection(
                selectedStatus = selectedFilterState.status,
                onStatusSelected = { status ->
                    selectedFilterState = selectedFilterState.copy(status = status)
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ValueRangeSection(
                minValueText = minValueText,
                maxValueText = maxValueText,
                onMinChange = { input ->
                    val normalized = input.filter { it.isDigit() || it == '.' }
                    minValueText = normalized
                    selectedFilterState = selectedFilterState.copy(
                        minValue = normalized.toDoubleOrNull()
                    )
                },
                onMaxChange = { input ->
                    val normalized = input.filter { it.isDigit() || it == '.' }
                    maxValueText = normalized
                    selectedFilterState = selectedFilterState.copy(
                        maxValue = normalized.toDoubleOrNull()
                    )
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            ExpiryRangeSection(
                selectedRange = selectedFilterState.expiryRange,
                onRangeSelected = { range ->
                    selectedFilterState = selectedFilterState.copy(expiryRange = range)
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            ApplyRow(
                onDismiss = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                },
                onApply = {
                    scope.launch {
                        onApply(selectedSortOrder, selectedFilterState.normalizeRange())
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) onDismiss()
                    }
                }
            )
        }
    }
}

@Composable
private fun FilterSheetHeader(
    canReset: Boolean,
    onReset: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.semantics { heading() }
        ) {
            Text(
                text = "Refine results",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Refine filters and sorting",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canReset) {
                TextButton(onClick = onReset) {
                    Text("Reset")
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .width(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close filters"
                )
            }
        }
    }
}

@Composable
private fun SortSection(
    selectedSortOrder: SortOrder,
    onSortSelected: (SortOrder) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        SectionTitle(title = "Sort")
        Spacer(modifier = Modifier.height(8.dp))
        SortOrder.values().forEach { sortOrder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.RadioButton(
                    selected = sortOrder == selectedSortOrder,
                    onClick = { onSortSelected(sortOrder) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (sortOrder) {
                        SortOrder.EXPIRY_DATE -> "Expiry date"
                        SortOrder.NAME -> "Store name"
                        SortOrder.CREATED_DATE -> "Date added"
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreSection(
    availableStores: List<String>,
    selectedStores: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "Stores")
            Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableStores.forEach { store ->
                val selected = selectedStores.contains(store)
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(store) },
                    label = { Text(store) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(
    availableCategories: List<String>,
    selectedCategories: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "Categories")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableCategories.forEach { category ->
                val selected = selectedCategories.contains(category)
                FilterChip(
                    selected = selected,
                    onClick = { onToggle(category) },
                    label = { Text(category) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSection(
    selectedStatus: CouponStatusFilter,
    onStatusSelected: (CouponStatusFilter) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "Status")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CouponStatusFilter.values().forEach { status ->
                val selected = status == selectedStatus
                FilterChip(
                    selected = selected,
                    onClick = { onStatusSelected(status) },
                    label = { Text(status.displayName) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryRangeSection(
    selectedRange: ExpiryRange,
    onRangeSelected: (ExpiryRange) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "Expiry window")
        Spacer(modifier = Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ExpiryRange.values().forEach { range ->
                val selected = range == selectedRange
                FilterChip(
                    selected = selected,
                    onClick = { onRangeSelected(range) },
                    label = { Text(range.displayName) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    )
                )
            }
        }
    }
}

@Composable
private fun ValueRangeSection(
    minValueText: String,
    maxValueText: String,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(title = "Cashback range")
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = minValueText,
                onValueChange = onMinChange,
                label = { Text("Min") },
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = maxValueText,
                onValueChange = onMaxChange,
                label = { Text("Max") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ApplyRow(
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text("Cancel")
        }
        Button(
            onClick = onApply,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Apply")
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

private fun FilterState.toggleStore(store: String): FilterState {
    val updated = selectedStores.toMutableSet()
    if (!updated.add(store)) {
        updated.remove(store)
    }
    return copy(selectedStores = updated)
}

private fun FilterState.toggleCategory(category: String): FilterState {
    val updated = selectedCategories.toMutableSet()
    if (!updated.add(category)) {
        updated.remove(category)
    }
    return copy(selectedCategories = updated)
}

private fun FilterState.normalizeRange(): FilterState {
    val min = minValue
    val max = maxValue
    if (min != null && max != null && min > max) {
        return copy(minValue = max, maxValue = min)
    }
    return this
}

private fun valueToString(value: Double): String {
    val intPart = value.toInt()
    return if (value == intPart.toDouble()) intPart.toString() else String.format("%.2f", value)
}

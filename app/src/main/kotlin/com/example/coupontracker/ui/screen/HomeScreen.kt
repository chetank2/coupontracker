package com.example.coupontracker.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.components.BrandCard
import com.example.coupontracker.ui.components.EmptyState
import com.example.coupontracker.ui.components.EnhancedCouponCard
import com.example.coupontracker.ui.components.FilterSortBottomSheet
import com.example.coupontracker.ui.components.OutlinedBrandButton
import com.example.coupontracker.ui.components.PrimaryButton
import com.example.coupontracker.ui.components.SecondaryButton
import com.example.coupontracker.ui.components.SectionHeader
import com.example.coupontracker.ui.components.SimplifiedCaptureBottomSheet
import com.example.coupontracker.ui.model.CouponStatusFilter
import com.example.coupontracker.ui.model.ExpiryRange
import com.example.coupontracker.ui.model.FilterState
import com.example.coupontracker.ui.model.hasActiveFilters
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.HomeViewModel
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Button

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val homeUiState by viewModel.uiState.collectAsState()
    val coupons = homeUiState.coupons
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val hasFiltersApplied: Boolean

    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            // Navigate to CouponForm with the selected image
            navController.navigate(
                Screen.CouponForm.createRoute(
                    imageUri = it.toString(),
                    isBatchMode = false
                )
            )
        }
    }

    val filters = homeUiState.filters

    // Search state mirrors ViewModel so filters survive recomposition
    var searchQuery by remember { mutableStateOf(filters.searchQuery) }
    var isSearchActive by remember { mutableStateOf(filters.searchQuery.isNotBlank()) }

    LaunchedEffect(filters.searchQuery) {
        if (filters.searchQuery != searchQuery) {
            searchQuery = filters.searchQuery
        }
    }

    // Bottom sheet states
    var showCaptureBottomSheet by remember { mutableStateOf(false) }
    var showFilterSortBottomSheet by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = true) {
        val isFirstLaunchPref = sharedPreferences.getBoolean("is_first_launch", true)
        if (isFirstLaunchPref) {
            // Mark first launch as completed
            sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (!isSearchActive) {
                        Text(
                            text = "Coupon Tracker",
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        TextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.updateSearchQuery(it)
                            },
                            placeholder = { Text("Search coupons") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) {
                                searchQuery = ""
                                viewModel.updateSearchQuery("")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchActive) "Close search" else "Search"
                        )
                    }

                    IconButton(
                        onClick = { navController.navigate(Screen.Settings.route) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            // Main FAB
            ExtendedFloatingActionButton(
                onClick = { showCaptureBottomSheet = true },
                icon = {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = {
                    Text(
                        text = "Add Coupon",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                expanded = true,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 6.dp,
                    pressedElevation = 8.dp
                ),
                modifier = Modifier.padding(vertical = BrandSpacing.Small)
            )

            // Show the simplified capture bottom sheet when the FAB is clicked
            if (showCaptureBottomSheet) {
                SimplifiedCaptureBottomSheet(
                    onDismiss = { showCaptureBottomSheet = false },
                    onCameraCapture = {
                        navController.navigate(Screen.SmartCamera.route)
                    },
                    onUpload = {
                        // Launch image picker directly instead of navigating to another screen
                        imagePickerLauncher.launch("image/*")
                    },
                    onManualEntry = {
                        navController.navigate(Screen.ManualEntry.route)
                    },
                    onScreenshotUpload = {
                        // Phase 3: Multi-coupon screenshot upload
                        // Navigate to batch scanner for multi-coupon extraction
                        navController.navigate(Screen.BatchScanner.route)
                    }
                )
            }

            // Show the filter and sort bottom sheet
            if (showFilterSortBottomSheet) {
                FilterSortBottomSheet(
                    onDismiss = { showFilterSortBottomSheet = false },
                    currentSortOrder = filters.sortOrder,
                    currentFilterState = filters.filterState,
                    availableStores = viewModel.availableStores.collectAsState().value,
                    availableCategories = viewModel.availableCategories.collectAsState().value,
                    onApply = { sortOrder, filterState ->
                        viewModel.updateSortOrder(sortOrder)
                        viewModel.setFilterState(filterState)
                        showFilterSortBottomSheet = false
                    },
                    onReset = {
                        viewModel.resetFilters()
                        showFilterSortBottomSheet = false
                    }
                )
            }
        }
    ) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        SearchAndFilterBar(
            onFiltersClick = { showFilterSortBottomSheet = true },
            sortOrderLabel = filters.sortOrder.displayName,
            hasActiveFilters = filters.filterState.hasActiveFilters(includeSearchQuery = true, searchQuery = searchQuery)
        )

        FilterHighlightsBanner(
                filterState = filters.filterState,
                searchQuery = searchQuery,
                onClearStore = { store ->
                    viewModel.updateFilterState { it.copy(selectedStores = it.selectedStores - store) }
                },
                onClearCategory = { category ->
                    viewModel.updateFilterState { it.copy(selectedCategories = it.selectedCategories - category) }
                },
                onClearStatus = {
                    viewModel.updateFilterState { it.copy(status = CouponStatusFilter.ALL) }
                },
                onClearValueRange = {
                    viewModel.updateFilterState { it.copy(minValue = null, maxValue = null) }
                },
                onClearExpiry = {
                    viewModel.updateFilterState { it.copy(expiryRange = ExpiryRange.ALL) }
                },
                onClearSearch = {
                    searchQuery = ""
                    viewModel.updateSearchQuery("")
                },
                onClearAll = {
                    viewModel.resetFilters()
                    searchQuery = ""
                    viewModel.updateSearchQuery("")
                }
            )

        if (coupons.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                if (filters.filterState.hasActiveFilters(includeSearchQuery = true, searchQuery = searchQuery)) {
                        FilteredEmptyState(
                            onClearFilters = {
                                viewModel.resetFilters()
                                searchQuery = ""
                                viewModel.updateSearchQuery("")
                            }
                        )
                    } else {
                        EmptyState(
                            title = "No Coupons Yet",
                            message = "Add your first coupon to start saving money. You can scan, import, or manually enter coupon details.",
                            icon = Icons.Outlined.Info,
                            modifier = Modifier.fillMaxWidth(0.9f),
                            action = {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth(0.7f)
                                ) {
                                    PrimaryButton(
                                        text = "Scan Coupon",
                                        onClick = { navController.navigate(Screen.SmartCamera.route) },
                                        leadingIcon = Icons.Default.CameraAlt,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = BrandSpacing.Medium)
                                    )

                                    Spacer(modifier = Modifier.height(BrandSpacing.Small))

                                    SecondaryButton(
                                        text = "Upload Image",
                                        onClick = { imagePickerLauncher.launch("image/*") },
                                        leadingIcon = Icons.Default.Upload,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        )
                    }
                }
            } else {
                SectionHeader(
                    title = "Your Coupons",
                    modifier = Modifier.padding(
                        start = BrandSpacing.Medium,
                        end = BrandSpacing.Medium,
                        top = BrandSpacing.Medium,
                        bottom = BrandSpacing.Small
                    ),
                    action = {
                        Text(
                            text = "${coupons.size} total",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = BrandSpacing.Medium,
                        end = BrandSpacing.Medium,
                        bottom = BrandSpacing.ExtraLarge
                    ),
                    verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
                ) {
                    items(coupons) { coupon ->
                        EnhancedCouponCard(
                            storeName = coupon.storeName,
                            description = coupon.description,
                            expiryDate = coupon.expiryDate,
                            amount = coupon.getCashbackNumericValue(),
                            code = coupon.redeemCode,
                            imageUri = coupon.imageUri,
                            onClick = {
                                navController.navigate(Screen.CouponDetail.createRoute(coupon.id))
                            },
                            onCopyCode = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                            },
                            cashbackDisplayText = coupon.getCashbackDisplayText()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAndFilterBar(
    onFiltersClick: () -> Unit,
    sortOrderLabel: String,
    hasActiveFilters: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BrandSpacing.Medium, vertical = BrandSpacing.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
        ) {
            AssistChip(
                onClick = onFiltersClick,
                label = { Text(if (hasActiveFilters) "Filters active" else "Filters") },
                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (hasActiveFilters) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                )
            )

            AssistChip(
                onClick = onFiltersClick,
                label = { Text(sortOrderLabel) },
                leadingIcon = { Icon(Icons.Default.Sort, contentDescription = null) }
            )
        }
    }
}

@Composable
private fun FilterHighlightsBanner(
    filterState: FilterState,
    searchQuery: String,
    onClearStore: (String) -> Unit,
    onClearCategory: (String) -> Unit,
    onClearStatus: () -> Unit,
    onClearValueRange: () -> Unit,
    onClearExpiry: () -> Unit,
    onClearSearch: () -> Unit,
    onClearAll: () -> Unit
) {
    if (!filterState.hasActiveFilters(includeSearchQuery = true, searchQuery = searchQuery)) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BrandSpacing.Medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active filters",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onClearAll) {
                Text("Clear all")
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Small))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searchQuery.isNotBlank()) {
                FilterChip(label = "Search: $searchQuery", onClear = onClearSearch)
            }

            filterState.selectedStores.forEach { store ->
                FilterChip(label = store, onClear = { onClearStore(store) })
            }

            filterState.selectedCategories.forEach { category ->
                FilterChip(label = category, onClear = { onClearCategory(category) })
            }

            if (filterState.status != CouponStatusFilter.ALL) {
                FilterChip(label = "Status: ${filterState.status.displayName}", onClear = onClearStatus)
            }

            if (filterState.minValue != null || filterState.maxValue != null) {
                val label = "Value: ${filterState.minValue?.toString() ?: "0"} – ${filterState.maxValue?.toString() ?: "∞"}"
                FilterChip(label = label, onClear = onClearValueRange)
            }

            if (filterState.expiryRange != ExpiryRange.ALL) {
                FilterChip(label = "Expiry: ${filterState.expiryRange.displayName}", onClear = onClearExpiry)
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Small))
    }
}

@Composable
private fun FilterChip(
    label: String,
    onClear: () -> Unit
) {
    AssistChip(
        onClick = {},
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = "Remove filter")
            }
        },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun AppliedFiltersBar(
    filterState: FilterState,
    searchQuery: String,
    onClearStore: (String) -> Unit,
    onClearCategory: (String) -> Unit,
    onClearStatus: () -> Unit,
    onClearValueRange: () -> Unit,
    onClearExpiry: () -> Unit,
    onClearSearch: () -> Unit,
    onClearAll: () -> Unit
) {
    if (!filterState.hasActiveFilters(includeSearchQuery = true, searchQuery = searchQuery)) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BrandSpacing.Medium)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Active filters",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onClearAll) {
                Text("Clear all")
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Small))

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (searchQuery.isNotBlank()) {
                AssistChip(
                    onClick = {},
                    label = { Text("Search: $searchQuery") },
                    leadingIcon = {
                        IconButton(onClick = onClearSearch) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                )
            }

            filterState.selectedStores.forEach { store ->
                AssistChip(
                    onClick = {},
                    label = { Text(store) },
                    leadingIcon = {
                        IconButton(onClick = { onClearStore(store) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove store filter")
                        }
                    }
                )
            }

            filterState.selectedCategories.forEach { category ->
                AssistChip(
                    onClick = {},
                    label = { Text(category) },
                    leadingIcon = {
                        IconButton(onClick = { onClearCategory(category) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove category filter")
                        }
                    }
                )
            }

            if (filterState.status != CouponStatusFilter.ALL) {
                AssistChip(
                    onClick = {},
                    label = { Text("Status: ${filterState.status.displayName}") },
                    leadingIcon = {
                        IconButton(onClick = onClearStatus) {
                            Icon(Icons.Default.Close, contentDescription = "Remove status filter")
                        }
                    }
                )
            }

            if (filterState.minValue != null || filterState.maxValue != null) {
                val text = buildString {
                    append("Value: ")
                    append(filterState.minValue?.toString() ?: "0")
                    append(" – ")
                    append(filterState.maxValue?.toString() ?: "∞")
                }
                AssistChip(
                    onClick = {},
                    label = { Text(text) },
                    leadingIcon = {
                        IconButton(onClick = onClearValueRange) {
                            Icon(Icons.Default.Close, contentDescription = "Remove value filter")
                        }
                    }
                )
            }

            if (filterState.expiryRange != ExpiryRange.ALL) {
                AssistChip(
                    onClick = {},
                    label = { Text("Expiry: ${filterState.expiryRange.displayName}") },
                    leadingIcon = {
                        IconButton(onClick = onClearExpiry) {
                            Icon(Icons.Default.Close, contentDescription = "Remove expiry filter")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FilteredEmptyState(
    onClearFilters: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = BrandSpacing.Medium),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "No coupons match",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "No coupons match the current search or filters. Try adjusting them or clear everything to view all coupons.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onClearFilters) {
                Text("Clear filters")
            }
        }
    }
}
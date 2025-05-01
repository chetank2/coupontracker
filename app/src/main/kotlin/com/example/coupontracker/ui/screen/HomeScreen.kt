package com.example.coupontracker.ui.screen

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.components.ApiSelectionDialog
import com.example.coupontracker.ui.components.BrandCard
import com.example.coupontracker.ui.components.EmptyState
import com.example.coupontracker.ui.components.EnhancedCouponCard
import com.example.coupontracker.ui.components.OutlinedBrandButton
import com.example.coupontracker.ui.components.PrimaryButton
import com.example.coupontracker.ui.components.SecondaryButton
import com.example.coupontracker.ui.components.SectionHeader
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val coupons by viewModel.coupons.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    // Check if this is the first launch
    var showApiDialog by remember { mutableStateOf(false) }
    var isFirstLaunch by remember { mutableStateOf(false) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = true) {
        val isFirstLaunchPref = sharedPreferences.getBoolean("is_first_launch", true)
        if (isFirstLaunchPref) {
            isFirstLaunch = true
            showApiDialog = true
            sharedPreferences.edit().putBoolean("is_first_launch", false).apply()
        }
    }

    // Show the API selection dialog if needed
    if (showApiDialog) {
        ApiSelectionDialog(
            sharedPreferences = sharedPreferences,
            isFirstLaunch = isFirstLaunch,
            onDismiss = { showApiDialog = false }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (!isSearchActive) {
                        Text(
                            text = "Coupon Tracker",
                            style = MaterialTheme.typography.titleLarge
                        )
                    } else {
                        // Search field would go here in a real implementation
                        Text(
                            text = "Search Coupons",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { isSearchActive = !isSearchActive }
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (isSearchActive) "Close Search" else "Search"
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            var showMenu by remember { mutableStateOf(false) }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Show the menu if expanded
                AnimatedVisibility(
                    visible = showMenu,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    BrandCard(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .width(220.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        shape = BrandShapes.LargeCornerShape
                    ) {
                        Column(
                            modifier = Modifier.padding(BrandSpacing.Medium)
                        ) {
                            Text(
                                text = "Add Coupon",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = BrandSpacing.Small)
                            )

                            // Camera option
                            PrimaryButton(
                                text = "Camera",
                                onClick = {
                                    navController.navigate(Screen.Scanner.route)
                                    showMenu = false
                                },
                                leadingIcon = Icons.Default.CameraAlt,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(BrandSpacing.Small))

                            // Batch scan option
                            SecondaryButton(
                                text = "Batch Scan",
                                onClick = {
                                    navController.navigate(Screen.BatchScanner.route)
                                    showMenu = false
                                },
                                leadingIcon = Icons.Default.Collections,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(BrandSpacing.Small))

                            // QR code option
                            SecondaryButton(
                                text = "QR Code",
                                onClick = {
                                    navController.navigate(Screen.QRScanner.route)
                                    showMenu = false
                                },
                                leadingIcon = Icons.Default.QrCode,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(BrandSpacing.Small))

                            // Manual entry option
                            OutlinedBrandButton(
                                text = "Manual Entry",
                                onClick = {
                                    navController.navigate(Screen.ManualEntry.route)
                                    showMenu = false
                                },
                                leadingIcon = Icons.Default.Edit,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Main FAB
                ExtendedFloatingActionButton(
                    onClick = { showMenu = !showMenu },
                    icon = {
                        Icon(
                            if (showMenu) Icons.Default.Close else Icons.Default.Add,
                            contentDescription = null
                        )
                    },
                    text = {
                        Text(
                            text = if (showMenu) "Close" else "Add Coupon",
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
            }
        }
    ) { paddingValues ->
        if (coupons.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                EmptyState(
                    title = "No Coupons Yet",
                    message = "Add your first coupon to start saving money. You can scan, import, or manually enter coupon details.",
                    icon = Icons.Outlined.Info,
                    modifier = Modifier.fillMaxWidth(0.9f),
                    action = {
                        PrimaryButton(
                            text = "Scan Coupon",
                            onClick = { navController.navigate(Screen.Scanner.route) },
                            leadingIcon = Icons.Default.CameraAlt,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .padding(top = BrandSpacing.Medium)
                        )
                    }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Section header
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

                // Coupon list
                LazyColumn(
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
                            amount = coupon.cashbackAmount,
                            code = coupon.redeemCode,
                            onClick = {
                                navController.navigate(Screen.CouponDetail.createRoute(coupon.id))
                            },
                            onCopyCode = { code ->
                                clipboardManager.setText(AnnotatedString(code))
                                // In a real app, you would show a toast or snackbar here
                            }
                        )
                    }
                }
            }
        }
    }
}
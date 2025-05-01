package com.example.coupontracker.ui.screen

import android.content.Context
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.ApiSelectionDialog
import com.example.coupontracker.ui.components.CouponItem
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val coupons by viewModel.coupons.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    // Check if this is the first launch
    var showApiDialog by remember { mutableStateOf(false) }
    var isFirstLaunch by remember { mutableStateOf(false) }

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
            TopAppBar(
                title = { Text("Coupon Tracker") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            var showMenu by remember { mutableStateOf(false) }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                // Show the menu if expanded
                if (showMenu) {
                    Card(
                        modifier = Modifier
                            .padding(bottom = 16.dp)
                            .width(200.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            // Camera option
                            Button(
                                onClick = {
                                    navController.navigate(Screen.Scanner.route)
                                    showMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Camera")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Batch scan option
                            Button(
                                onClick = {
                                    navController.navigate(Screen.BatchScanner.route)
                                    showMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Collections, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Batch Scan")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // QR code option
                            Button(
                                onClick = {
                                    navController.navigate(Screen.QRScanner.route)
                                    showMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("QR Code")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Manual entry option
                            Button(
                                onClick = {
                                    navController.navigate(Screen.ManualEntry.route)
                                    showMenu = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Manual Entry")
                            }
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
                    text = { Text(if (showMenu) "Close" else "Add Coupon") },
                    modifier = Modifier.padding(vertical = 8.dp)
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No Coupons Yet",
                                style = MaterialTheme.typography.headlineSmall
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Text(
                                text = "Scan a coupon to get started or test the API connection",
                                style = MaterialTheme.typography.bodyMedium
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Choose an input method:",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                // Camera option
                                Button(
                                    onClick = { navController.navigate(Screen.Scanner.route) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Camera")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Batch scan option
                                Button(
                                    onClick = { navController.navigate(Screen.BatchScanner.route) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Collections, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Batch Scan")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // QR code option
                                Button(
                                    onClick = { navController.navigate(Screen.QRScanner.route) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.QrCode, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("QR Code")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Manual entry option
                                Button(
                                    onClick = { navController.navigate(Screen.ManualEntry.route) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Manual Entry")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(coupons) { coupon ->
                    CouponItem(
                        coupon = coupon,
                        onClick = {
                            navController.navigate(Screen.CouponDetail.createRoute(coupon.id))
                        }
                    )
                }
            }
        }
    }
}
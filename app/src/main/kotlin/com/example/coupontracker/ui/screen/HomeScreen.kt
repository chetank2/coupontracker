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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.NetworkCheck
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
            ExtendedFloatingActionButton(
                onClick = { navController.navigate(Screen.Scanner.route) },
                icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                text = { Text("Scan") },
                modifier = Modifier.padding(vertical = 8.dp)
            )
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
                                text = "Welcome to Coupon Tracker!",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "Start by scanning your first coupon or configure your preferred OCR settings",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Button(
                                    onClick = { navController.navigate(Screen.Scanner.route) }
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Scan")
                                }
                                
                                Button(
                                    onClick = { navController.navigate(Screen.Settings.route) }
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null)
                                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                                    Text("Settings")
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
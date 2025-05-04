package com.example.coupontracker.ui.screen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.util.ModelMetadataReader
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.util.TesseractLanguageManager
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.text.style.TextAlign
import com.example.coupontracker.ui.components.PasswordDialog

// Using keys from SecurePreferencesManager

// We've removed the API Type enum as users don't need to select the OCR technology

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val securePreferencesManager = remember { SecurePreferencesManager(context) }
    val modelMetadataReader = remember { ModelMetadataReader(context) }

    // Initialize secure preferences manager
    LaunchedEffect(Unit) {
        securePreferencesManager.initialize()
    }

    // Get model version
    val (modelVersion, numPatterns) = remember { modelMetadataReader.getModelVersion() }

    // UI states
    val scrollState = rememberScrollState()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var protectedFeaturesUnlocked by remember {
        mutableStateOf(securePreferencesManager.areProtectedFeaturesUnlocked())
    }

    // Password dialog
    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onPasswordEntered = { password ->
                if (securePreferencesManager.checkAdminPassword(password)) {
                    // Password correct, unlock protected features
                    securePreferencesManager.setProtectedFeaturesUnlocked(true)
                    protectedFeaturesUnlocked = true
                    showPasswordDialog = false
                } else {
                    // Password incorrect, keep dialog open
                    // In a real app, you might want to show an error message
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Top
        ) {

            // Model Info
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Privacy-Focused Recognition",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = "100% On-Device",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "All coupon recognition happens directly on your device. " +
                              "Your coupon data never leaves your phone, ensuring complete privacy and security.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "The app has been trained on thousands of coupons to provide accurate results, including specialized recognition for Indian coupons.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Model Version Info
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "Model Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Model Version:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = modelVersion,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Number of Patterns:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = numPatterns.toString(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Protected features button (only shown if features are not unlocked)
            if (!protectedFeaturesUnlocked) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Protected features button
                        OutlinedButton(
                            onClick = { showPasswordDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Access Protected Features")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Enter admin password to access advanced features like usage analytics.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Protected features (only shown if unlocked)
            if (protectedFeaturesUnlocked) {
                // Analytics button
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Analytics button
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.Analytics.route) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View Usage Analytics")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "View detailed analytics about your coupon scanning patterns and app performance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Lock protected features button
                OutlinedButton(
                    onClick = {
                        securePreferencesManager.setProtectedFeaturesUnlocked(false)
                        protectedFeaturesUnlocked = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lock Protected Features")
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// Helper function to save preferences securely
private fun <T> saveApiPreference(securePreferencesManager: SecurePreferencesManager, key: String, value: T) {
    when (value) {
        is String -> securePreferencesManager.saveString(key, value)
        is Boolean -> securePreferencesManager.saveBoolean(key, value)
        else -> throw IllegalArgumentException("Unsupported type")
    }
}
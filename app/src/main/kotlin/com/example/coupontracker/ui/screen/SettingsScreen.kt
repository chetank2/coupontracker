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
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.util.TesseractLanguageManager
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.text.style.TextAlign

// Using keys from SecurePreferencesManager

// We've removed the API Type enum as users don't need to select the OCR technology

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val securePreferencesManager = remember { SecurePreferencesManager(context) }

    // Initialize secure preferences manager
    LaunchedEffect(Unit) {
        securePreferencesManager.initialize()
    }

    // We've removed the API selection as users don't need to select the OCR technology

    // UI states
    val scrollState = rememberScrollState()

    // We've removed the API selection as users don't need to select the OCR technology

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
                        text = "The app has been trained on thousands of coupons to provide accurate results.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Custom training button
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    // Custom training button
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.TesseractTraining.route) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Improve Recognition Accuracy")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Help the app learn from your coupons to improve recognition accuracy over time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
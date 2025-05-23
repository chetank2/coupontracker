package com.example.coupontracker.ui.components

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.coupontracker.ui.screen.ApiType
import com.example.coupontracker.ui.screen.KEY_GOOGLE_CLOUD_VISION_API_KEY
import com.example.coupontracker.ui.screen.KEY_MISTRAL_API_KEY
import com.example.coupontracker.ui.screen.KEY_SELECTED_API
import com.example.coupontracker.ui.screen.KEY_USE_MISTRAL_API

@Composable
fun ApiSelectionDialog(
    sharedPreferences: SharedPreferences,
    isFirstLaunch: Boolean,
    onDismiss: () -> Unit
) {
    var selectedApiType by remember {
        val savedApiType = sharedPreferences.getString(KEY_SELECTED_API, ApiType.GOOGLE_CLOUD_VISION.name)
        mutableStateOf(
            try {
                ApiType.valueOf(savedApiType ?: ApiType.GOOGLE_CLOUD_VISION.name)
            } catch (e: Exception) {
                ApiType.GOOGLE_CLOUD_VISION
            }
        )
    }
    
    // Check if API keys are available
    val googleApiKey = sharedPreferences.getString(KEY_GOOGLE_CLOUD_VISION_API_KEY, "") ?: ""
    val mistralApiKey = sharedPreferences.getString(KEY_MISTRAL_API_KEY, "") ?: ""
    
    // If first launch and no keys available, pre-select ML Kit
    if (isFirstLaunch && googleApiKey.isBlank() && mistralApiKey.isBlank()) {
        selectedApiType = ApiType.ML_KIT
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = !isFirstLaunch, dismissOnClickOutside = !isFirstLaunch)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isFirstLaunch) "Welcome to Coupon Tracker" else "OCR API Selection",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Please select which OCR API to use for text recognition:",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                ApiType.values().forEach { apiType ->
                    val isKeyAvailable = when (apiType) {
                        ApiType.GOOGLE_CLOUD_VISION -> googleApiKey.isNotBlank()
                        ApiType.MISTRAL -> mistralApiKey.isNotBlank()
                        ApiType.COMBINED -> googleApiKey.isNotBlank() && mistralApiKey.isNotBlank()
                        ApiType.ML_KIT -> true // Always available
                    }
                    
                    val isEnabled = isKeyAvailable || apiType == ApiType.ML_KIT
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selectedApiType == apiType,
                                onClick = { 
                                    if (isEnabled) {
                                        selectedApiType = apiType
                                    }
                                },
                                enabled = isEnabled
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedApiType == apiType,
                            onClick = { 
                                if (isEnabled) {
                                    selectedApiType = apiType
                                }
                            },
                            enabled = isEnabled
                        )
                        
                        Column(
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .weight(1f)
                        ) {
                            Text(
                                text = apiType.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isEnabled) 
                                    MaterialTheme.colorScheme.onSurface 
                                else 
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            )
                            
                            if (!isEnabled) {
                                Text(
                                    text = when (apiType) {
                                        ApiType.COMBINED -> "Both API keys required"
                                        else -> "API key required"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!isFirstLaunch) {
                        TextButton(
                            onClick = onDismiss
                        ) {
                            Text("Cancel")
                        }
                        
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    Button(
                        onClick = {
                            // Save the selection
                            with(sharedPreferences.edit()) {
                                putString(KEY_SELECTED_API, selectedApiType.name)
                                
                                // For backward compatibility
                                putBoolean(KEY_USE_MISTRAL_API, selectedApiType == ApiType.MISTRAL)
                                apply()
                            }
                            onDismiss()
                        }
                    ) {
                        Text("Confirm")
                    }
                }
                
                if (isFirstLaunch) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "You can change this later in Settings",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
} 
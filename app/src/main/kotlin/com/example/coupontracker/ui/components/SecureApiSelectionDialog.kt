package com.example.coupontracker.ui.components

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
import com.example.coupontracker.util.SecurePreferencesManager

/**
 * A dialog for selecting the OCR API to use
 * This version uses SecurePreferencesManager for secure storage of API keys
 */
@Composable
fun SecureApiSelectionDialog(
    securePreferencesManager: SecurePreferencesManager,
    isFirstLaunch: Boolean,
    onDismiss: () -> Unit
) {
    var selectedApiType by remember {
        val savedApiType = securePreferencesManager.getString(
            SecurePreferencesManager.KEY_SELECTED_API,
            ApiType.GOOGLE_CLOUD_VISION.name
        )
        mutableStateOf(
            try {
                ApiType.valueOf(savedApiType ?: ApiType.GOOGLE_CLOUD_VISION.name)
            } catch (e: Exception) {
                ApiType.GOOGLE_CLOUD_VISION
            }
        )
    }

    // Check if API keys are available
    val googleApiKey = securePreferencesManager.getString(
        SecurePreferencesManager.KEY_GOOGLE_CLOUD_VISION_API_KEY, ""
    ) ?: ""
    val mistralApiKey = securePreferencesManager.getString(
        SecurePreferencesManager.KEY_MISTRAL_API_KEY, ""
    ) ?: ""

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
                        ApiType.ML_KIT, ApiType.TESSERACT, ApiType.PATTERN_RECOGNIZER -> true // Always available
                        ApiType.SUPER -> googleApiKey.isNotBlank() || mistralApiKey.isNotBlank() || true // At least one API or on-device
                    }

                    val isEnabled = isKeyAvailable || apiType == ApiType.ML_KIT || apiType == ApiType.TESSERACT || apiType == ApiType.PATTERN_RECOGNIZER

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
                                        ApiType.SUPER -> "At least one API key required"
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
                            // Save the selection securely
                            securePreferencesManager.saveString(
                                SecurePreferencesManager.KEY_SELECTED_API,
                                selectedApiType.name
                            )

                            // For backward compatibility
                            securePreferencesManager.saveBoolean(
                                SecurePreferencesManager.KEY_USE_MISTRAL_API,
                                selectedApiType == ApiType.MISTRAL
                            )

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

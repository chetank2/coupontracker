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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.util.ApiTester
import com.example.coupontracker.util.DebugLoggerUtil
import com.example.coupontracker.util.TesseractLanguageManager
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.text.style.TextAlign

// Preference keys
const val KEY_GOOGLE_CLOUD_VISION_API_KEY = "google_cloud_vision_api_key"
const val KEY_MISTRAL_API_KEY = "mistral_api_key"
const val KEY_SELECTED_API = "selected_api"
const val KEY_USE_MISTRAL_API = "use_mistral_api" // Keeping for backward compatibility

// API Type enum
enum class ApiType(val displayName: String) {
    GOOGLE_CLOUD_VISION("Google Cloud Vision"),
    MISTRAL("Mistral AI"),
    COMBINED("Combined Vision+Mistral"),
    ML_KIT("ML Kit (On-device)"),
    TESSERACT("Tesseract OCR (On-device)"),
    SUPER("Super OCR (All Technologies)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    // API keys
    var googleCloudVisionApiKey by remember {
        mutableStateOf(sharedPreferences.getString(KEY_GOOGLE_CLOUD_VISION_API_KEY, "") ?: "")
    }
    var mistralApiKey by remember {
        mutableStateOf(sharedPreferences.getString(KEY_MISTRAL_API_KEY, "") ?: "")
    }

    // Selected API
    val savedApiType = sharedPreferences.getString(KEY_SELECTED_API, ApiType.GOOGLE_CLOUD_VISION.name)
    var selectedApiType by remember {
        mutableStateOf(
            try {
                ApiType.valueOf(savedApiType ?: ApiType.GOOGLE_CLOUD_VISION.name)
            } catch (e: Exception) {
                ApiType.GOOGLE_CLOUD_VISION
            }
        )
    }

    // UI states
    var isGoogleKeyVisible by remember { mutableStateOf(false) }
    var isMistralKeyVisible by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Coroutine scope
    val scope = rememberCoroutineScope()

    // API test states
    var isTestingGoogleApi by remember { mutableStateOf(false) }
    var googleApiTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var isTestingMistralApi by remember { mutableStateOf(false) }
    var mistralApiTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

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
            // API Keys Help Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "About API Keys",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "API keys enhance the OCR capabilities of the app. You can use the app without API keys (ML Kit mode), but adding them will improve text recognition accuracy.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• Google Cloud Vision: Provides high-quality cloud OCR (free tier available)\n• Mistral AI: Enhances results with AI validation (free tier available)\n• Tesseract OCR: Open-source OCR engine (no API key needed)\n• Super OCR: Uses all available technologies for best results",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // OCR API Selection
            Text(
                text = "OCR API Selection",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // API Selection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Select OCR API Priority",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    ApiType.values().forEach { apiType ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedApiType == apiType,
                                onClick = {
                                    selectedApiType = apiType
                                    saveApiPreference(sharedPreferences, KEY_SELECTED_API, apiType.name)

                                    // For backward compatibility
                                    if (apiType == ApiType.MISTRAL) {
                                        saveApiPreference(sharedPreferences, KEY_USE_MISTRAL_API, true)
                                    } else {
                                        saveApiPreference(sharedPreferences, KEY_USE_MISTRAL_API, false)
                                    }

                                    Toast.makeText(context, "${apiType.displayName} selected as primary OCR API", Toast.LENGTH_SHORT).show()
                                }
                            )

                            Text(
                                text = apiType.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )

                            // Show status icon for APIs that need keys
                            if (apiType != ApiType.ML_KIT && apiType != ApiType.TESSERACT) {
                                Spacer(modifier = Modifier.weight(1f))

                                val isKeyValid = when (apiType) {
                                    ApiType.GOOGLE_CLOUD_VISION -> googleCloudVisionApiKey.isNotBlank()
                                    ApiType.MISTRAL -> mistralApiKey.isNotBlank()
                                    ApiType.COMBINED -> googleCloudVisionApiKey.isNotBlank() && mistralApiKey.isNotBlank()
                                    ApiType.SUPER -> googleCloudVisionApiKey.isNotBlank() || mistralApiKey.isNotBlank()
                                    ApiType.ML_KIT, ApiType.TESSERACT -> true // On-device options don't need API keys
                                    else -> false
                                }

                                Icon(
                                    imageVector = if (isKeyValid) Icons.Default.Check else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isKeyValid) Color.Green else Color.Red
                                )
                            }
                        }
                    }

                    // API Selection Help Text
                    Text(
                        text = "Note: If the selected API fails, the app will try the next one in order. " +
                              "The 'Combined' option uses Google Cloud Vision results validated by Mistral AI. " +
                              "The 'Tesseract' option uses an open-source OCR engine that works on-device. " +
                              "The 'Super OCR' option uses all available technologies in parallel and selects the best results.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Google Cloud Vision API Key
            Text(
                text = "API Keys",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Google Cloud Vision API Key Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Google Cloud Vision API Key",
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedButton(
                            onClick = {
                                val uri = Uri.parse("https://cloud.google.com/vision/docs/setup")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Get API Key")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = googleCloudVisionApiKey,
                        onValueChange = { googleCloudVisionApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isGoogleKeyVisible) VisualTransformation.None
                                             else PasswordVisualTransformation(),
                        trailingIcon = {
                            Button(
                                onClick = { isGoogleKeyVisible = !isGoogleKeyVisible },
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(if (isGoogleKeyVisible) "Hide" else "Show")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // Test API Button
                        if (googleApiTestResult != null) {
                            Icon(
                                imageVector = if (googleApiTestResult!!.first) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (googleApiTestResult!!.first) Color.Green else Color.Red,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        OutlinedButton(
                            onClick = {
                                isTestingGoogleApi = true
                                googleApiTestResult = null

                                scope.launch {
                                    val apiTester = ApiTester(context)
                                    googleApiTestResult = apiTester.testGoogleVisionApi(googleCloudVisionApiKey)
                                    isTestingGoogleApi = false
                                }
                            },
                            enabled = !isTestingGoogleApi && googleCloudVisionApiKey.isNotBlank()
                        ) {
                            if (isTestingGoogleApi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Test API")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                saveApiPreference(sharedPreferences, KEY_GOOGLE_CLOUD_VISION_API_KEY, googleCloudVisionApiKey)
                                Toast.makeText(context, "Google Cloud Vision API key saved", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Save Key")
                        }
                    }

                    // Advanced Test Section
                    var isAdvancedTestOpen by remember { mutableStateOf(false) }
                    var isRunningAdvancedTest by remember { mutableStateOf(false) }
                    var advancedTestResult by remember { mutableStateOf<String?>(null) }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { isAdvancedTestOpen = !isAdvancedTestOpen },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(if (isAdvancedTestOpen) "Hide Advanced Test" else "Advanced Test")
                    }

                    if (isAdvancedTestOpen) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Run a direct raw API test to diagnose connection issues",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                isRunningAdvancedTest = true
                                advancedTestResult = null

                                scope.launch {
                                    val debugLogger = DebugLoggerUtil(context)
                                    advancedTestResult = debugLogger.testVisionApiDirectly(googleCloudVisionApiKey)
                                    isRunningAdvancedTest = false
                                }
                            },
                            enabled = !isRunningAdvancedTest && googleCloudVisionApiKey.isNotBlank(),
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            if (isRunningAdvancedTest) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Run Raw API Test")
                        }

                        // Show advanced test result
                        advancedTestResult?.let { result ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = result,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.startsWith("Success")) Color.Green else Color.Red
                            )
                        }
                    }

                    // Show test result message if available
                    googleApiTestResult?.let { (success, message) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = if (success) Color.Green else Color.Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // Mistral API Key Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Mistral AI API Key",
                            style = MaterialTheme.typography.titleMedium
                        )

                        OutlinedButton(
                            onClick = {
                                val uri = Uri.parse("https://console.mistral.ai/")
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Get API Key")
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = mistralApiKey,
                        onValueChange = { mistralApiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (isMistralKeyVisible) VisualTransformation.None
                                             else PasswordVisualTransformation(),
                        trailingIcon = {
                            Button(
                                onClick = { isMistralKeyVisible = !isMistralKeyVisible },
                                modifier = Modifier.padding(4.dp)
                            ) {
                                Text(if (isMistralKeyVisible) "Hide" else "Show")
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        // Test API Button
                        if (mistralApiTestResult != null) {
                            Icon(
                                imageVector = if (mistralApiTestResult!!.first) Icons.Default.Check else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (mistralApiTestResult!!.first) Color.Green else Color.Red,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        OutlinedButton(
                            onClick = {
                                isTestingMistralApi = true
                                mistralApiTestResult = null

                                scope.launch {
                                    val apiTester = ApiTester(context)
                                    mistralApiTestResult = apiTester.testMistralApi(mistralApiKey)
                                    isTestingMistralApi = false
                                }
                            },
                            enabled = !isTestingMistralApi && mistralApiKey.isNotBlank()
                        ) {
                            if (isTestingMistralApi) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Test API")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                saveApiPreference(sharedPreferences, KEY_MISTRAL_API_KEY, mistralApiKey)
                                Toast.makeText(context, "Mistral API key saved", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Save Key")
                        }
                    }

                    // Show test result message if available
                    mistralApiTestResult?.let { (success, message) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            color = if (success) Color.Green else Color.Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // ML Kit Info
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
                            text = "ML Kit (On-device OCR)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = "No API Key Required",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "ML Kit runs directly on your device and doesn't require an API key. " +
                              "It's used as a fallback when other API methods fail and is always available even if you don't have any API keys.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "To get started without API keys, simply select ML Kit as your OCR option above.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Tesseract Info
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                            text = "Tesseract OCR (On-device)",
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(4.dp)
                        ) {
                            Text(
                                text = "No API Key Required",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tesseract is an open-source OCR engine that runs on your device. " +
                              "It's particularly good at recognizing printed text and works without an internet connection or API keys.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Tesseract complements ML Kit by using a different algorithm that may catch text ML Kit misses.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tesseract Language Selection
                    val languageManager = remember { TesseractLanguageManager(context) }
                    val availableLanguages = remember { languageManager.getAvailableLanguagesWithNames() }
                    var selectedLanguage by remember { mutableStateOf(languageManager.getSelectedLanguage()) }
                    var isLanguageDropdownExpanded by remember { mutableStateOf(false) }

                    Text(
                        text = "Tesseract Language",
                        style = MaterialTheme.typography.titleSmall
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Box {
                        OutlinedButton(
                            onClick = { isLanguageDropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = languageManager.getLanguageDisplayName(selectedLanguage),
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Language"
                            )
                        }

                        DropdownMenu(
                            expanded = isLanguageDropdownExpanded,
                            onDismissRequest = { isLanguageDropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth(0.7f)
                        ) {
                            availableLanguages.forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedLanguage = code
                                        languageManager.setSelectedLanguage(code)
                                        isLanguageDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Select the language that matches the text in your coupons for best results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// Helper function to save preferences
private fun <T> saveApiPreference(sharedPreferences: SharedPreferences, key: String, value: T) {
    with(sharedPreferences.edit()) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            else -> throw IllegalArgumentException("Unsupported type")
        }
        apply()
    }
}
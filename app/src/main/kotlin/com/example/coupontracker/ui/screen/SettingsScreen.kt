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
import com.example.coupontracker.util.ApiType
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.ui.components.PasswordDialog
import com.example.coupontracker.ui.components.ThemeSelector
import com.example.coupontracker.ui.viewmodel.SettingsViewModel
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.ModelDownloadManager
import com.example.coupontracker.util.LlmServiceStatus
import com.example.coupontracker.util.LocalLlmOcrService
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import com.example.coupontracker.ocr.OcrEngine
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// Using keys from SecurePreferencesManager

// We've removed the API Type enum as users don't need to select the OCR technology

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsScreenEntryPoint {
    fun getOcrEngine(): OcrEngine
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val ocrEngine = remember {
        val hiltEntryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SettingsScreenEntryPoint::class.java
        )
        hiltEntryPoint.getOcrEngine()
    }
    
    // Use a lazy initialization to avoid ANR
    val securePreferencesManager = remember { 
        SecurePreferencesManager(context)
    }
    
    // Get model version in background to avoid ANR
    var modelVersion by remember { mutableStateOf("Loading...") }
    var numPatterns by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        // Move expensive operations to background thread
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Initialize SecurePreferencesManager in background
                securePreferencesManager.initialize()
                
                val modelMetadataReader = ModelMetadataReader(context)
                val (version, patterns) = modelMetadataReader.getModelVersion()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    modelVersion = version
                    numPatterns = patterns
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    modelVersion = "1.0.1"
                    numPatterns = 25
                }
            }
        }
    }

    // Get theme mode
    val themeMode by viewModel.themeMode.collectAsState()

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

            // Theme Selector
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                ThemeSelector(
                    selectedThemeMode = themeMode,
                    onThemeModeSelected = { newThemeMode ->
                        viewModel.setThemeMode(newThemeMode)
                    }
                )
            }

            // OCR Engine Selector
            ApiTypeSelector(securePreferencesManager = securePreferencesManager)

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
                        text = "The app uses advanced recognition technology to accurately identify coupon details, including specialized recognition for Indian coupons.",
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
            
            // LLM Status Info
            LlmStatusCard(securePreferencesManager = securePreferencesManager, ocrEngine = ocrEngine)

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
                // Extraction Performance Dashboard
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        // Dashboard button
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.ExtractionDashboard.route) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Extraction Performance")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Monitor universal extraction performance, learning progress, and optimize the AI system.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // V2: Extraction Strategy Selector
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "Extraction Strategy (Advanced)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text(
                            text = "Choose how the AI extracts coupon information. LEGACY is the current stable mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        data class StrategyOption(
                            val strategy: com.example.coupontracker.util.ExtractionStrategy,
                            val label: String,
                            val description: String
                        )
                        
                        // Get available strategies based on model availability
                        val availableStrategies = com.example.coupontracker.util.ExtractionConfig.getAvailableStrategies()
                        
                        val allStrategyOptions = listOf(
                            StrategyOption(com.example.coupontracker.util.ExtractionStrategy.OCR_FIRST, "OCR First (Recommended)", "✅ Real OCR → Pattern matching"),
                            StrategyOption(com.example.coupontracker.util.ExtractionStrategy.LEGACY, "Legacy Fallback", "✅ Multi-step with fallbacks"),
                            StrategyOption(com.example.coupontracker.util.ExtractionStrategy.LLM_FIRST, "LLM First", "⚠️ Requires model download (2.4 GB)"),
                            StrategyOption(com.example.coupontracker.util.ExtractionStrategy.HYBRID, "Hybrid", "⚠️ Requires model download (2.4 GB)")
                        )
                        
                        val strategies = allStrategyOptions.filter { 
                            availableStrategies.contains(it.strategy)
                        }
                        
                        // V2 Fix: Initialize ExtractionConfig if not already initialized
                        LaunchedEffect(Unit) {
                            com.example.coupontracker.util.ExtractionConfig.init(context)
                        }
                        
                        var currentStrategy by remember { 
                            mutableStateOf(com.example.coupontracker.util.ExtractionConfig.getStrategy()) 
                        }
                        
                        strategies.forEach { option ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (currentStrategy == option.strategy) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surface
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = currentStrategy == option.strategy,
                                        onClick = {
                                            com.example.coupontracker.util.ExtractionConfig.setStrategy(option.strategy)
                                            currentStrategy = option.strategy
                                            Toast.makeText(
                                                context,
                                                "Strategy changed to ${option.strategy.name}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (currentStrategy == option.strategy) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Text(
                                            text = option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

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
                                imageVector = Icons.Default.School,
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

@Composable
private fun LlmStatusCard(
    securePreferencesManager: SecurePreferencesManager,
    ocrEngine: OcrEngine
) {
    val context = LocalContext.current
    var llmStatus by remember { mutableStateOf<LlmServiceStatus?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var modelDownloadStatus by remember { mutableStateOf("Unknown") }
    var modelSize by remember { mutableStateOf(0f) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var downloadStatusMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    
    // Load LLM status and download info in background
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val llmRuntimeManager = LlmRuntimeManager.getInstance(context)
                val localLlmOcrService = LocalLlmOcrService(context, ocrEngine, llmRuntimeManager)
                
                val status = localLlmOcrService.getServiceStatus()
                val downloadStatus = if (securePreferencesManager.getLlmModelDownloaded()) {
                    "Downloaded"
                } else {
                    "Not Downloaded"
                }
                val sizeMB = securePreferencesManager.getLlmModelSizeMB()
                
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    llmStatus = status
                    modelDownloadStatus = downloadStatus
                    modelSize = sizeMB
                    isLoading = false
                }
            } catch (e: Exception) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "Local LLM Status",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Loading status...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                llmStatus?.let { status ->
                    // Model availability status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Model Available:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (status.isAvailable) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (status.isAvailable) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (status.isAvailable) "Yes" else "No",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.isAvailable) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Model loaded status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Model Loaded:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (status.isModelLoaded) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (status.isModelLoaded) androidx.compose.ui.graphics.Color.Green else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (status.isModelLoaded) "Loaded" else "Not Loaded",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Model version
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
                            text = status.modelVersion,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Memory usage
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Memory Usage:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "${status.memoryUsageMB}MB",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (status.referenceCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Active References:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = status.referenceCount.toString(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    
                    // Model Download Management Section
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    HorizontalDivider()
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Model Management",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Download Status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Download Status:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (modelDownloadStatus == "Downloaded") Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (modelDownloadStatus == "Downloaded") androidx.compose.ui.graphics.Color.Green else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = modelDownloadStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (modelDownloadStatus == "Downloaded") androidx.compose.ui.graphics.Color.Green else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    
                    if (modelSize > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Model Size:",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${modelSize.toInt()}MB",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    
                    // Download Button
                    if (modelDownloadStatus != "Downloaded") {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = {
                                if (!isDownloading) {
                                    isDownloading = true
                                    downloadProgress = 0
                                    downloadStatusMessage = "Starting download..."
                                    
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val modelDownloadManager = ModelDownloadManager(context)
                                            
                                            // Start download with progress callback
                                            val result = modelDownloadManager.downloadModel { progress ->
                                                // Update UI on main thread
                                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                    downloadProgress = progress.progressPercent
                                                    downloadStatusMessage = progress.statusMessage
                                                }
                                            }
                                            
                                            // Handle result on main thread
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                when (result) {
                                                    is com.example.coupontracker.llm.DownloadResult.Success -> {
                                                        Toast.makeText(context, "Model downloaded successfully!", Toast.LENGTH_LONG).show()
                                                        modelDownloadStatus = "Downloaded"
                                                        modelSize = result.modelSizeMB
                                                        // Update preferences
                                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                            securePreferencesManager.setLlmModelDownloaded(true)
                                                            securePreferencesManager.setLlmModelSizeMB(result.modelSizeMB)
                                                        }
                                                    }
                                                    is com.example.coupontracker.llm.DownloadResult.Error -> {
                                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                                        downloadStatusMessage = "Download failed"
                                                    }
                                                }
                                                isDownloading = false
                                                downloadProgress = 0
                                            }
                                        } catch (e: Exception) {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                                                downloadStatusMessage = "Download error"
                                                isDownloading = false
                                                downloadProgress = 0
                                            }
                                        }
                                    }
                                }
                            },
                            enabled = !isDownloading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isDownloading) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { if (downloadProgress > 0) downloadProgress / 100f else 0f },
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (downloadProgress > 0) "Downloading... ${downloadProgress}%" else "Downloading...",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                    
                                    if (downloadStatusMessage.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = downloadStatusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Download MiniCPM Model (~4.5MB)")
                                }
                            }
                        }
                        
                        // Progress bar for download
                        if (isDownloading && downloadProgress > 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Spacer(modifier = Modifier.height(4.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${downloadProgress}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                if (downloadStatusMessage.isNotEmpty()) {
                                    Text(
                                        text = downloadStatusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    
                } ?: run {
                    Text(
                        text = "Unable to load LLM status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.ui.graphics.Color.Red
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiTypeSelector(securePreferencesManager: SecurePreferencesManager) {
    var selectedApiType by remember { mutableStateOf(ApiType.MODEL_BASED) }
    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    // Load current selection
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val currentType = securePreferencesManager.getSelectedApiType()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                selectedApiType = currentType
            }
        }
    }
    
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
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "OCR Engine",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Dropdown for API type selection
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedApiType.displayName,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Select OCR Engine") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    ApiType.getAvailableTypes().forEach { apiType ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = apiType.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = apiType.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedApiType = apiType
                                expanded = false
                                
                                // Save selection in background
                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    securePreferencesManager.setSelectedApiType(apiType)
                                }
                            },
                            leadingIcon = {
                                if (selectedApiType == apiType) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        )
                    }
                }
            }
            
            // Show additional info for LOCAL_LLM
            if (selectedApiType == ApiType.LOCAL_LLM) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Local AI Model",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "Uses MiniCPM-Llama3-V2.5 for advanced structured extraction. Requires model download (~3GB). Fully offline and privacy-focused.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
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
package com.example.coupontracker.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.util.ModelMetadataReader
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.components.ThemeSelector
import com.example.coupontracker.ui.components.DataSafetyDialog
import com.example.coupontracker.ui.viewmodel.SettingsViewModel
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Storage
import com.example.coupontracker.R
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography

// Using keys from SecurePreferencesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showDataSafety by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    val settings by viewModel.settings.collectAsState()
    val cleanupState by viewModel.cleanupState.collectAsState()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.updateNotificationsEnabled(granted)
        Toast.makeText(
            context,
            if (granted) "Notifications enabled" else "Notifications permission denied",
            Toast.LENGTH_SHORT
        ).show()
    }

    if (showDataSafety) {
        DataSafetyDialog(
            onDismiss = { showDataSafety = false },
            onLearnMore = {
                showDataSafety = false
                navController.navigate(Screen.PrivacyPolicy.route)
            }
        )
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

    LaunchedEffect(cleanupState) {
        when (val state = cleanupState) {
            is SettingsViewModel.CleanupState.Success -> {
                Toast.makeText(
                    context,
                    if (state.removedCount == 0) "No duplicates found" else "Removed ${state.removedCount} duplicate coupon${if (state.removedCount == 1) "" else "s"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is SettingsViewModel.CleanupState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
            }
            else -> Unit
        }
    }

    // UI states
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        scrollState.scrollTo(0)
    }

    Scaffold(
        topBar = {
            BrandTopBar(
                title = "Settings",
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
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .padding(horizontal = BrandSpacing.ContentEdge, vertical = BrandSpacing.Medium)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.SectionSpacing)
        ) {
            SettingsSection(title = "PRIVACY") {
                SettingsRow(
                    title = "Private by design",
                    subtitle = "Coupon recognition runs on this device. Your saved coupons stay on your phone.",
                    trailing = { CompactValue("On-device") },
                )
                SettingsDivider()
                SettingsRow(
                    title = stringResource(id = R.string.settings_data_safety_title),
                    subtitle = stringResource(id = R.string.settings_data_safety_summary),
                    onClick = { showDataSafety = true },
                )
                SettingsDivider()
                SettingsRow(
                    title = "Privacy policy",
                    subtitle = "Read how Coupon Tracker handles your data.",
                    onClick = { navController.navigate(Screen.PrivacyPolicy.route) },
                )
            }

            SettingsSection(title = "MODEL") {
                ModelManagementCard()
            }

            SettingsSection(title = "BACKUP") {
                BackupRestoreCard(
                    viewModel = viewModel,
                    onPickerReturn = {
                        coroutineScope.launch { scrollState.animateScrollTo(0) }
                    },
                )
            }

            SettingsSection(title = "NOTIFICATIONS") {
                SettingsRow(
                    title = "Expiry reminders",
                    subtitle = if (settings.notificationsEnabled) {
                        "Reminder notifications are enabled."
                    } else {
                        "Turn on notifications for saved coupon reminders."
                    },
                    trailing = {
                        Switch(
                            checked = settings.notificationsEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    viewModel.updateNotificationsEnabled(enabled)
                                }
                            }
                        )
                    },
                )
            }

            SettingsSection(title = "APPEARANCE") {
                ThemeSelector(
                    selectedThemeMode = themeMode,
                    onThemeModeSelected = { newThemeMode ->
                        viewModel.setThemeMode(newThemeMode)
                    },
                    modifier = Modifier.padding(vertical = BrandSpacing.ExtraSmall),
                )
            }

            SettingsSection(title = "ADVANCED") {
                SettingsRow(
                    title = "Advanced",
                    subtitle = "Diagnostics and internal tools.",
                    onClick = { showAdvanced = !showAdvanced },
                    trailing = {
                        Icon(
                            imageVector = if (showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                if (showAdvanced) {
                    SettingsDivider()
                    SettingsRow(
                        title = "Diagnostics",
                        subtitle = "Analytics dashboard for troubleshooting.",
                        onClick = { navController.navigate(Screen.Analytics.route) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Reader learning",
                        subtitle = "Review extraction learning signals.",
                        onClick = { navController.navigate(Screen.ExtractionDashboard.route) },
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = "Clean duplicate coupons",
                        subtitle = when (cleanupState) {
                            SettingsViewModel.CleanupState.Running -> "Checking saved coupons..."
                            is SettingsViewModel.CleanupState.Success -> "Last cleanup removed ${(cleanupState as SettingsViewModel.CleanupState.Success).removedCount} duplicate coupons."
                            else -> "Merge repeated scans of the same coupon."
                        },
                        onClick = { viewModel.cleanupDuplicateCoupons() },
                    )

                    val commitHash = remember(BuildConfig.APP_VERSION) {
                        BuildConfig.APP_VERSION.split("-").firstOrNull { it.startsWith("g") }?.removePrefix("g")
                    }
                    SettingsDivider()
                    SettingsRow(
                        title = "App version",
                        subtitle = buildString {
                            append("A private wallet for coupon codes and expiry dates.")
                            if (commitHash != null) {
                                append(" ")
                                append(stringResource(id = R.string.settings_build_commit, commitHash))
                            }
                        },
                        trailing = { CompactValue(BuildConfig.APP_VERSION) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BrandSpacing.ExtraSmall)) {
        Text(
            text = title,
            style = BrandTypography.LabelMedium.copy(
                letterSpacing = 1.8.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = BrandSpacing.Hairline,
                    color = MaterialTheme.colorScheme.outline,
                    shape = BrandShapes.Large,
                ),
            shape = BrandShapes.Large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = BrandSpacing.Medium, vertical = BrandSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BrandSpacing.Micro),
        ) {
            Text(
                text = title,
                style = BrandTypography.TitleSmall.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = BrandTypography.BodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = BrandSpacing.Medium),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
        thickness = BrandSpacing.Hairline,
    )
}

@Composable
private fun CompactValue(text: String) {
    Text(
        text = text,
        style = BrandTypography.BodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// Removed LlmStatusCard - too technical for users (Memory, Reference Count, etc.)
// Removed ApiTypeSelector - only one OCR engine (Tesseract), no choice needed


@Composable
private fun ModelManagementCard() {
    val context = LocalContext.current
    val modelImportViewModel: com.example.coupontracker.ui.viewmodel.ModelImportViewModel = hiltViewModel()
    val uiState by modelImportViewModel.uiState.collectAsState()
    
    val securePrefsManager = remember { SecurePreferencesManager(context) }
    var showLicenseGate by remember { mutableStateOf(false) }
    var licenseAccepted by remember { mutableStateOf(securePrefsManager.isMiniCpmLicenseAccepted()) }
    
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { modelImportViewModel.importModel(it) }
    }
    
    // License Gate Dialog
    if (showLicenseGate) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showLicenseGate = false }
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                LicenseGateScreen(
                    onLicenseAccepted = {
                        licenseAccepted = true
                        showLicenseGate = false
                        // Proceed with download
                        modelImportViewModel.downloadModel()
                    },
                    securePreferencesManager = securePrefsManager
                )
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BrandSpacing.Medium),
    ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Qwen offline coupon reader",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Model Status
            if (uiState.isModelInstalled) {
                // Installed status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                        val modelName = uiState.modelInfo?.name ?: "Qwen2.5 offline reader"
                        val sizeText = uiState.modelSizeMB.takeIf { it > 0 }?.let { " • $it MB" }.orEmpty()
                        Text(
                            text = "$modelName installed$sizeText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                uiState.selfTestResult?.let { result ->
                    when (result) {
                        is com.example.coupontracker.model.SelfTestResult.Success -> {
                            Text(
                                text = "Reader check passed",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4CAF50)
                            )
                        }
                        is com.example.coupontracker.model.SelfTestResult.Failed -> {
                            Text(
                                text = "Reader check failed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Set up the Qwen model to read coupon screenshots privately on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Import Progress
            if (uiState.isImporting) {
                LinearProgressIndicator(
                    progress = uiState.importProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = uiState.importMessage,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            // Error Message
            uiState.importError?.let { error ->
                Text(
                    text = "Error: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action Buttons
            if (!uiState.isModelInstalled) {
                // Import/Download options when model not installed
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (licenseAccepted) {
                                modelImportViewModel.downloadModel()
                            } else {
                                showLicenseGate = true
                            }
                        },
                        enabled = !uiState.isImporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Set up Qwen model")
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedButton(
                        onClick = {
                            filePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                        },
                        enabled = !uiState.isImporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Memory, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import setup file")
                    }
                }
            } else {
                // Test/Delete buttons when model is installed
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Test Button
                    OutlinedButton(
                        onClick = { modelImportViewModel.runSelfTest() },
                        enabled = !uiState.selfTestRunning && !uiState.isImporting,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.selfTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Check")
                    }
                    
                    // Delete Button
                    OutlinedButton(
                        onClick = { modelImportViewModel.deleteModel() },
                        enabled = !uiState.isImporting && !uiState.selfTestRunning,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Default.Error, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Remove")
                    }
                }
            }
    }
}

@Composable
private fun BackupRestoreCard(
    viewModel: SettingsViewModel,
    onPickerReturn: () -> Unit,
) {
    val context = LocalContext.current
    val backupState by viewModel.backupState.collectAsState()
    
    // File pickers
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        onPickerReturn()
        uri?.let { viewModel.exportBackup(it) }
    }
    
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        onPickerReturn()
        uri?.let { viewModel.importBackup(it) }
    }
    
    // Show result dialog
    when (val state = backupState) {
        is SettingsViewModel.BackupState.Success -> {
            LaunchedEffect(state) {
                Toast.makeText(
                    context,
                    "${state.message} (${state.count} coupons)",
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetBackupState()
            }
        }
        is SettingsViewModel.BackupState.Error -> {
            LaunchedEffect(state) {
                Toast.makeText(
                    context,
                    state.message,
                    Toast.LENGTH_LONG
                ).show()
                viewModel.resetBackupState()
            }
        }
        else -> {}
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(BrandSpacing.Medium),
    ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Backup & Restore",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = "Export your coupons to an encrypted backup file or restore from a previous backup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Export/Import Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Export Button
                OutlinedButton(
                    onClick = {
                        val timestamp = System.currentTimeMillis()
                        exportLauncher.launch("coupons_backup_$timestamp.encrypted")
                    },
                    enabled = backupState !is SettingsViewModel.BackupState.Exporting &&
                             backupState !is SettingsViewModel.BackupState.Importing,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    if (backupState is SettingsViewModel.BackupState.Exporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export")
                }
                
                // Import Button
                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    enabled = backupState !is SettingsViewModel.BackupState.Exporting &&
                             backupState !is SettingsViewModel.BackupState.Importing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (backupState is SettingsViewModel.BackupState.Importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import")
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Security note
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Backups are encrypted using AES-256",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
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

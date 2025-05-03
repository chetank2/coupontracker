package com.example.coupontracker.ui.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.util.TesseractOCRHelper
import com.example.coupontracker.util.TesseractTrainer
import com.example.coupontracker.util.TesseractTrainingDataCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Screen for managing Tesseract OCR training data and custom models
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesseractTrainingScreen(
    navController: NavController
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Tesseract components
    val tesseractOCRHelper = remember { TesseractOCRHelper(context) }
    val tesseractTrainer = remember { TesseractTrainer(context) }
    val trainingDataCollector = remember { TesseractTrainingDataCollector(context) }
    
    // State
    var selectedTab by remember { mutableStateOf(0) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var recognizedText by remember { mutableStateOf("") }
    var groundTruthText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var trainingStats by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedModelFile by remember { mutableStateOf<File?>(null) }
    var useCustomModel by remember { mutableStateOf(false) }
    
    // Load initial data
    LaunchedEffect(Unit) {
        trainingStats = trainingDataCollector.getTrainingDataStats()
        availableModels = tesseractTrainer.getAvailableCustomModels()
        useCustomModel = tesseractOCRHelper.isCustomModelAvailable()
    }
    
    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bitmap = loadBitmapFromUri(context, it)
                    selectedImage = bitmap
                    groundTruthText = ""
                    recognizedText = ""
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load image", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    // Model file picker launcher
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val file = copyUriToFile(context, it, "custom_model.traineddata")
                    selectedModelFile = file
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to load model file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tesseract Training") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tabs
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Collect Data") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Manage Models") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Test Recognition") }
                )
            }
            
            // Tab content
            when (selectedTab) {
                0 -> CollectDataTab(
                    selectedImage = selectedImage,
                    groundTruthText = groundTruthText,
                    onGroundTruthChange = { groundTruthText = it },
                    onPickImage = { imagePickerLauncher.launch("image/*") },
                    onSaveTrainingData = {
                        scope.launch {
                            isProcessing = true
                            selectedImage?.let {
                                val path = trainingDataCollector.saveTrainingImage(
                                    it,
                                    groundTruthText,
                                    "general"
                                )
                                if (path != null) {
                                    Toast.makeText(context, "Training data saved", Toast.LENGTH_SHORT).show()
                                    trainingStats = trainingDataCollector.getTrainingDataStats()
                                    selectedImage = null
                                    groundTruthText = ""
                                } else {
                                    Toast.makeText(context, "Failed to save training data", Toast.LENGTH_SHORT).show()
                                }
                            }
                            isProcessing = false
                        }
                    },
                    onExportTrainingData = {
                        scope.launch {
                            isProcessing = true
                            val uri = trainingDataCollector.exportTrainingData()
                            if (uri != null) {
                                Toast.makeText(context, "Training data exported to: ${uri.path}", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Failed to export training data", Toast.LENGTH_SHORT).show()
                            }
                            isProcessing = false
                        }
                    },
                    onClearTrainingData = {
                        scope.launch {
                            trainingDataCollector.clearTrainingData()
                            trainingStats = trainingDataCollector.getTrainingDataStats()
                            Toast.makeText(context, "Training data cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    trainingStats = trainingStats,
                    isProcessing = isProcessing
                )
                
                1 -> ManageModelsTab(
                    availableModels = availableModels,
                    selectedModelFile = selectedModelFile,
                    useCustomModel = useCustomModel,
                    onUseCustomModelChange = { useCustomModel = it },
                    onPickModelFile = { modelPickerLauncher.launch("*/*") },
                    onInstallModel = {
                        scope.launch {
                            isProcessing = true
                            selectedModelFile?.let {
                                val success = tesseractTrainer.installCustomModel(it)
                                if (success) {
                                    Toast.makeText(context, "Custom model installed", Toast.LENGTH_SHORT).show()
                                    availableModels = tesseractTrainer.getAvailableCustomModels()
                                    selectedModelFile = null
                                } else {
                                    Toast.makeText(context, "Failed to install custom model", Toast.LENGTH_SHORT).show()
                                }
                            }
                            isProcessing = false
                        }
                    },
                    isProcessing = isProcessing
                )
                
                2 -> TestRecognitionTab(
                    selectedImage = selectedImage,
                    recognizedText = recognizedText,
                    useCustomModel = useCustomModel,
                    onUseCustomModelChange = { useCustomModel = it },
                    onPickImage = { imagePickerLauncher.launch("image/*") },
                    onRecognizeText = {
                        scope.launch {
                            isProcessing = true
                            selectedImage?.let {
                                recognizedText = tesseractOCRHelper.processImageFromBitmap(
                                    it,
                                    "eng",
                                    useCustomModel
                                )
                            }
                            isProcessing = false
                        }
                    },
                    isProcessing = isProcessing
                )
            }
        }
    }
}

@Composable
fun CollectDataTab(
    selectedImage: Bitmap?,
    groundTruthText: String,
    onGroundTruthChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onSaveTrainingData: () -> Unit,
    onExportTrainingData: () -> Unit,
    onClearTrainingData: () -> Unit,
    trainingStats: Map<String, Int>,
    isProcessing: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Training Data Collection",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Image preview or placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImage != null) {
                            Image(
                                bitmap = selectedImage.asImageBitmap(),
                                contentDescription = "Selected Image",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "No image selected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Image selection button
                    Button(
                        onClick = onPickImage,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Image")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Ground truth text input
                    OutlinedTextField(
                        value = groundTruthText,
                        onValueChange = onGroundTruthChange,
                        label = { Text("Ground Truth Text") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Save button
                    Button(
                        onClick = onSaveTrainingData,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedImage != null && groundTruthText.isNotBlank() && !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Save Training Data")
                        }
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Training Data Statistics",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (trainingStats.isEmpty()) {
                        Text(
                            text = "No training data collected yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // Display statistics
                        trainingStats.forEach { (category, count) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = category,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$count images",
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Export and clear buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = onExportTrainingData,
                            enabled = trainingStats.isNotEmpty() && !isProcessing
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Export Data")
                        }
                        
                        OutlinedButton(
                            onClick = onClearTrainingData,
                            enabled = trainingStats.isNotEmpty() && !isProcessing,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Clear Data")
                        }
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Training Instructions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "1. Collect at least 50-100 images of coupons with ground truth text",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "2. Export the training data to your computer",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "3. Follow the Tesseract training documentation to train a custom model",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "4. Import the trained model back into the app",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun ManageModelsTab(
    availableModels: List<String>,
    selectedModelFile: File?,
    useCustomModel: Boolean,
    onUseCustomModelChange: (Boolean) -> Unit,
    onPickModelFile: () -> Unit,
    onInstallModel: () -> Unit,
    isProcessing: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Available Custom Models",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (availableModels.isEmpty()) {
                        Text(
                            text = "No custom models installed",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        // List available models
                        availableModels.forEach { model ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                
                                Spacer(modifier = Modifier.width(16.dp))
                                
                                Text(
                                    text = model,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Use custom model switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Use Custom Model",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Switch(
                            checked = useCustomModel,
                            onCheckedChange = onUseCustomModelChange,
                            enabled = availableModels.isNotEmpty()
                        )
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Install New Model",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Selected model file
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Text(
                            text = selectedModelFile?.name ?: "No model file selected",
                            color = if (selectedModelFile != null)
                                MaterialTheme.colorScheme.onSurface
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = onPickModelFile,
                            enabled = !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select Model")
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Button(
                            onClick = onInstallModel,
                            enabled = selectedModelFile != null && !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isProcessing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.Upload, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install")
                            }
                        }
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Model Installation Instructions",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "1. Train a custom Tesseract model on your computer",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "2. Copy the .traineddata file to your device",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "3. Select the file using the button above",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "4. Install the model and enable 'Use Custom Model'",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun TestRecognitionTab(
    selectedImage: Bitmap?,
    recognizedText: String,
    useCustomModel: Boolean,
    onUseCustomModelChange: (Boolean) -> Unit,
    onPickImage: () -> Unit,
    onRecognizeText: () -> Unit,
    isProcessing: Boolean
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Test OCR Recognition",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Image preview or placeholder
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedImage != null) {
                            Image(
                                bitmap = selectedImage.asImageBitmap(),
                                contentDescription = "Selected Image",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "No image selected",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Image selection button
                    Button(
                        onClick = onPickImage,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Image")
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Use custom model switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Use Custom Model",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        
                        Switch(
                            checked = useCustomModel,
                            onCheckedChange = onUseCustomModelChange
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Recognize button
                    Button(
                        onClick = onRecognizeText,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = selectedImage != null && !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Recognize Text")
                        }
                    }
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Recognition Results",
                        style = MaterialTheme.typography.titleMedium
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    if (recognizedText.isBlank()) {
                        Text(
                            text = "No text recognized yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        // Display recognized text
                        Text(
                            text = recognizedText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Load a bitmap from a URI
 */
private suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val inputStream: InputStream = context.contentResolver.openInputStream(uri)
        ?: throw Exception("Could not open input stream")
    
    return@withContext BitmapFactory.decodeStream(inputStream)
}

/**
 * Copy a file from a URI to a local file
 */
private suspend fun copyUriToFile(context: Context, uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
    val inputStream: InputStream = context.contentResolver.openInputStream(uri)
        ?: throw Exception("Could not open input stream")
    
    val outputFile = File(context.cacheDir, fileName)
    val outputStream = FileOutputStream(outputFile)
    
    inputStream.use { input ->
        outputStream.use { output ->
            input.copyTo(output)
        }
    }
    
    return@withContext outputFile
}

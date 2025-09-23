package com.example.coupontracker.ui.screen

import android.Manifest
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.viewmodel.ScannerUiState
import com.example.coupontracker.ui.viewmodel.ScannerViewModel
import com.example.coupontracker.util.CouponInfo
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.text.selection.SelectionContainer

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun ScannerScreen(
    navController: NavController,
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    // Lifecycle owner is used in camera view

    val uiState by viewModel.uiState.collectAsState()

    var showCamera by remember { mutableStateOf(false) }
    var capturedImageUri by remember { mutableStateOf<Uri?>(null) }

    // Form fields
    var code by remember { mutableStateOf("") }
    var storeName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var expiryDateString by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var fieldsInitialized by remember { mutableStateOf(false) }

    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            capturedImageUri = it
            fieldsInitialized = false
            viewModel.scanImage(it)
        }
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is ScannerUiState.Success -> {
                val successState = uiState
                if (!fieldsInitialized) {
                    storeName = successState.coupon.storeName
                    description = successState.coupon.description
                    amount = successState.coupon.benefitValue?.toString()
                        ?: successState.coupon.cashbackAmount.toString()
                    code = successState.coupon.displayCode.orEmpty()
                    expiryDateString = successState.coupon.expiryDate.let { date ->
                        SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(date)
                    }
                    category = successState.coupon.category.orEmpty()
                    fieldsInitialized = true
                }
                successState.imageUri?.let { uriStr ->
                    capturedImageUri = Uri.parse(uriStr)
                }
            }
            is ScannerUiState.Initial -> {
                fieldsInitialized = false
                capturedImageUri = null
                category = ""
            }
            is ScannerUiState.Scanning -> {
                fieldsInitialized = false
                category = ""
            }
            else -> {
                // no-op
            }
        }
    }

    // Reset state when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetState()
            capturedImageUri = null
            fieldsInitialized = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Coupon") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                showCamera -> {
                    CameraView(
                        onImageCaptured = { uri ->
                            capturedImageUri = uri
                            fieldsInitialized = false
                            showCamera = false
                            viewModel.scanImage(uri)
                        },
                        onError = { error ->
                            Log.e("ScannerScreen", "Camera error: $error")
                            Toast.makeText(context, "Camera error: $error", Toast.LENGTH_SHORT).show()
                            showCamera = false
                        },
                        viewModel = viewModel
                    )

                    // Camera controls
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FloatingActionButton(
                                onClick = { showCamera = false },
                                modifier = Modifier.size(56.dp),
                                containerColor = MaterialTheme.colorScheme.error
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                uiState is ScannerUiState.Scanning -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Processing image...")
                        }
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Image capture options
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Capture Coupon Image",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = {
                                            if (cameraPermissionState.status.isGranted) {
                                                fieldsInitialized = false
                                                showCamera = true
                                            } else {
                                                cameraPermissionState.launchPermissionRequest()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.CameraAlt, contentDescription = "Camera")
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text("Camera")
                                    }

                                    Button(
                                        onClick = { imagePickerLauncher.launch("image/*") }
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = "Gallery")
                                        Spacer(modifier = Modifier.size(8.dp))
                                        Text("Gallery")
                                    }
                                }

                                // Show captured image
                                capturedImageUri?.let { uri ->
                            Spacer(modifier = Modifier.height(16.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "Captured image",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        }
                    }

                    if (uiState is ScannerUiState.Success) {
                        val successState = uiState
                        DetectionInsightsCard(
                            couponInfo = successState.couponInfo,
                            fieldConfidences = successState.fieldConfidences,
                            rawText = successState.rawText,
                            onCopyRawText = {
                                if (successState.rawText.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(successState.rawText))
                                    Toast.makeText(
                                        context,
                                        "Raw text copied to clipboard",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Form for coupon details
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Coupon Details",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )

                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                label = { Text("Coupon Code") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = storeName,
                                onValueChange = { storeName = it },
                                label = { Text("Store Name") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Description") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = category,
                                onValueChange = { category = it },
                                label = { Text("Category (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                }
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = amount,
                                onValueChange = { amount = it },
                                label = { Text("Amount") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = expiryDateString,
                                onValueChange = { expiryDateString = it },
                                label = { Text("Expiry Date (MM/DD/YYYY)") },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = {
                                    Icon(Icons.Default.DateRange, contentDescription = null)
                                }
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    viewModel.saveEditedCoupon(
                                        storeName = storeName,
                                        description = description,
                                        amountInput = amount,
                                        codeInput = code,
                                        expiryInput = expiryDateString,
                                        category = category,
                                        imageUri = capturedImageUri?.toString()
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = code.isNotBlank() && storeName.isNotBlank()
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null)
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("Save Coupon")
                            }

                            if (uiState is ScannerUiState.Saved) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = "Coupon saved successfully!",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                LaunchedEffect(uiState) {
                                    kotlinx.coroutines.delay(1500)
                                    navController.popBackStack()
                                }
                            }

                            if (uiState is ScannerUiState.Error) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = (uiState as ScannerUiState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectionInsightsCard(
    couponInfo: CouponInfo,
    fieldConfidences: Map<String, Float>,
    rawText: String,
    onCopyRawText: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Scan Insights",
                style = MaterialTheme.typography.titleMedium
            )

            val confidencePercent = (couponInfo.confidence.coerceIn(0f, 1f) * 100).roundToInt()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Overall confidence: $confidencePercent%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            if (fieldConfidences.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                fieldConfidences.entries
                    .sortedByDescending { it.value }
                    .forEach { (field, confidence) ->
                        ConfidenceRow(label = field.toDisplayLabel(), confidence = confidence)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "No ROI field confidences available for this scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (rawText.isNotBlank()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Raw OCR text",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        text = rawText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCopyRawText) {
                        Text("Copy raw text")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceRow(label: String, confidence: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${(confidence.coerceIn(0f, 1f) * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { confidence.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )
    }
}

private fun String.toDisplayLabel(): String {
    val normalized = lowercase(Locale.getDefault())
    return when (normalized) {
        "storename" -> "Store"
        "description" -> "Description"
        "amount" -> "Amount"
        "code", "redeemcode" -> "Coupon Code"
        "expirydate", "expiry" -> "Expiry"
        else -> replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
    }
}

@Composable
fun CameraView(
    onImageCaptured: (Uri) -> Unit,
    onError: (String) -> Unit,
    viewModel: ScannerViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageCapture = remember { ImageCapture.Builder().build() }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                // Unbind use cases when leaving the screen
                cameraProviderFuture.get()?.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraView", "Error unbinding camera", e)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            cameraSelector,
                            preview,
                            imageCapture
                        )
                    } catch (e: Exception) {
                        onError("Failed to bind camera: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Add a capture button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            FloatingActionButton(
                onClick = {
                    val outputDirectory = context.getOutputDirectory()
                    val photoFile = File(
                        outputDirectory,
                        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                            .format(System.currentTimeMillis()) + ".jpg"
                    )

                    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val savedUri = Uri.fromFile(photoFile)
                                onImageCaptured(savedUri)
                                viewModel.scanImage(savedUri)
                            }

                            override fun onError(exception: ImageCaptureException) {
                                onError(exception.message ?: "Unknown error")
                            }
                        }
                    )
                },
                modifier = Modifier.size(72.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Take Photo",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }
}

private fun Context.getOutputDirectory(): File {
    // Get the app-specific media directory using modern approach
    val mediaDir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        File(getExternalFilesDir(null), "CouponTracker").apply { mkdirs() }
    } else {
        @Suppress("DEPRECATION")
        externalMediaDirs.firstOrNull()?.let {
            File(it, "CouponTracker").apply { mkdirs() }
        }
    }
    return if (mediaDir != null && mediaDir.exists()) mediaDir else filesDir
}

private suspend fun Context.getCameraProvider(): ProcessCameraProvider = suspendCoroutine { continuation ->
    ProcessCameraProvider.getInstance(this).also { future ->
        future.addListener(
            {
                continuation.resume(future.get())
            },
            ContextCompat.getMainExecutor(this)
        )
    }
}

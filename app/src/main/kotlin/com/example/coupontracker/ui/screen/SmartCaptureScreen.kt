package com.example.coupontracker.ui.screen

import android.Manifest
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import android.content.Context
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.PulsatingHighlight
import com.example.coupontracker.ui.components.TooltipOverlay
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.CaptureMode
import com.example.coupontracker.ui.viewmodel.SmartCaptureViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Smart capture screen that provides a unified capture experience
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SmartCaptureScreen(
    navController: NavController,
    viewModel: SmartCaptureViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Camera executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // Image capture use case
    val imageCapture = remember { ImageCapture.Builder().build() }

    // User guidance state
    val sharedPreferences = remember {
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }
    var showCaptureTooltip by remember {
        mutableStateOf(sharedPreferences.getBoolean("show_capture_tooltip", true))
    }
    var showModeTooltip by remember {
        mutableStateOf(false)
    }

    // Show mode tooltip after capture tooltip is dismissed
    LaunchedEffect(showCaptureTooltip) {
        if (!showCaptureTooltip) {
            delay(1000)
            showModeTooltip = sharedPreferences.getBoolean("show_mode_tooltip", true)
        }
    }

    // Handle permission result
    LaunchedEffect(cameraPermissionState.status) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }

    // Handle detected mode changes
    LaunchedEffect(uiState.detectedMode) {
        uiState.detectedMode?.let { mode ->
            when (mode) {
                CaptureMode.QR_CODE -> {
                    // Navigate to QR scanner
                    Log.d("SmartCaptureScreen", "Navigating to QR scanner")
                    navController.navigate(Screen.QRScanner.route)
                }
                CaptureMode.MULTIPLE_COUPONS -> {
                    // Navigate to unified camera in batch mode
                    Log.d("SmartCaptureScreen", "Navigating to unified camera in batch mode")
                    navController.navigate(Screen.UnifiedCamera.route)
                }
                CaptureMode.MANUAL -> {
                    // Navigate to manual entry
                    Log.d("SmartCaptureScreen", "Navigating to manual entry")
                    navController.navigate(Screen.ManualEntry.route)
                }
                CaptureMode.SINGLE_COUPON -> {
                    // Process as single coupon
                    uiState.capturedImageUri?.let { uri ->
                        Log.d("SmartCaptureScreen", "Processing single coupon with URI: $uri")
                        // Continue with normal flow - the viewModel will process the image
                    }
                }
            }
        }
    }

    // Handle coupon info
    LaunchedEffect(uiState.couponInfo) {
        uiState.couponInfo?.let { couponInfo ->
            if (couponInfo.storeName.isNotBlank() || couponInfo.description.isNotBlank()) {
                // Navigate to CouponForm with the image URI
                Log.d("SmartCaptureScreen", "Navigating to CouponForm with image URI")
                uiState.capturedImageUri?.let { uri ->
                    navController.navigate(
                        Screen.CouponForm.createRoute(
                            imageUri = uri.toString(),
                            isBatchMode = false
                        )
                    )
                }
            }
        }
    }

    // Handle errors
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { error ->
            scope.launch {
                snackbarHostState.showSnackbar(error)
            }
            viewModel.clearError()
        }
    }

    // Clean up resources when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capture Coupon") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (cameraPermissionState.status.isGranted) {
                // Camera preview
                CameraPreview(
                    imageCapture = imageCapture,
                    cameraExecutor = cameraExecutor,
                    onImageCaptured = { uri ->
                        // Image captured, let the ViewModel handle it
                        // The ViewModel will determine if it's a QR code, multiple coupons, or a single coupon
                        viewModel.processImageUri(uri)
                    },
                    onError = { error ->
                        Log.e("SmartCaptureScreen", "Camera error: $error")
                        scope.launch {
                            snackbarHostState.showSnackbar("Camera error: $error")
                        }
                    },
                    viewModel = viewModel
                )

                // Show tooltips for first-time users
                if (showCaptureTooltip) {
                    TooltipOverlay(
                        visible = true,
                        message = "Point your camera at a coupon and tap the capture button to scan it. Our smart detection will automatically identify what type of coupon it is.",
                        targetAlignment = Alignment.Center,
                        onDismiss = {
                            showCaptureTooltip = false
                            sharedPreferences.edit().putBoolean("show_capture_tooltip", false).apply()
                        }
                    )
                }

                if (showModeTooltip) {
                    TooltipOverlay(
                        visible = true,
                        message = "You can also switch between different capture modes using these quick access buttons.",
                        targetAlignment = Alignment.BottomCenter,
                        offsetY = -100,
                        onDismiss = {
                            showModeTooltip = false
                            sharedPreferences.edit().putBoolean("show_mode_tooltip", false).apply()
                        }
                    )
                }

                // Quick mode selection buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Batch scan button
                    QuickModeButton(
                        icon = Icons.Default.Collections,
                        label = "Batch",
                        onClick = {
                            viewModel.setDetectedMode(CaptureMode.MULTIPLE_COUPONS)
                        }
                    )

                    // Camera capture button with pulsating highlight for first-time users
                    Box(contentAlignment = Alignment.Center) {
                        if (showCaptureTooltip) {
                            PulsatingHighlight(
                                modifier = Modifier.size(90.dp),
                                visible = true
                            )
                        }

                        CaptureButton(
                            onClick = {
                                if (!uiState.isProcessing) {
                                    // Capture image
                                    viewModel.captureImage(
                                        imageCapture = imageCapture,
                                        executor = ContextCompat.getMainExecutor(context),
                                        onImageSaved = { uri ->
                                            Log.d("SmartCaptureScreen", "Image captured: $uri")
                                            // The ViewModel will handle processing

                                            // Dismiss tooltips when user captures an image
                                            if (showCaptureTooltip) {
                                                showCaptureTooltip = false
                                                sharedPreferences.edit().putBoolean("show_capture_tooltip", false).apply()
                                            }
                                            if (showModeTooltip) {
                                                showModeTooltip = false
                                                sharedPreferences.edit().putBoolean("show_mode_tooltip", false).apply()
                                            }
                                        },
                                        onError = { exception ->
                                            Log.e("SmartCaptureScreen", "Image capture failed", exception)
                                            Toast.makeText(
                                                context,
                                                "Image capture failed: ${exception.message}",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                }
                            },
                            isProcessing = uiState.isProcessing
                        )
                    }

                    // QR code button
                    QuickModeButton(
                        icon = Icons.Default.QrCode,
                        label = "QR",
                        onClick = {
                            viewModel.setDetectedMode(CaptureMode.QR_CODE)
                        }
                    )
                }

                // Manual entry button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    QuickModeButton(
                        icon = Icons.Default.Edit,
                        label = "Manual",
                        onClick = {
                            viewModel.setDetectedMode(CaptureMode.MANUAL)
                        }
                    )
                }

                // Detection overlay
                if (uiState.detectedMode != null && uiState.detectedMode != CaptureMode.MANUAL) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (uiState.detectedMode == CaptureMode.MULTIPLE_COUPONS) {
                            // Multiple coupon detection overlay
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Multiple boxes to indicate multiple coupons
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(120.dp)
                                            .border(
                                                width = 2.dp,
                                                color = Color.Yellow,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(120.dp)
                                            .border(
                                                width = 2.dp,
                                                color = Color.Yellow,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                    )
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Multiple Coupons Detected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White,
                                    modifier = Modifier
                                        .background(
                                            color = Color.Black.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .padding(8.dp)
                                )
                            }
                        } else {
                            // Single coupon or QR code detection overlay
                            Box(
                                modifier = Modifier
                                    .size(250.dp)
                                    .border(
                                        width = 2.dp,
                                        color = when (uiState.detectedMode) {
                                            CaptureMode.QR_CODE -> Color.Green
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            )
                        }
                    }
                }
            } else {
                // Camera permission not granted
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Camera permission is required to capture coupons",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            cameraPermissionState.launchPermissionRequest()
                        }
                    ) {
                        Text("Grant Permission")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.setDetectedMode(CaptureMode.MANUAL)
                        }
                    ) {
                        Text("Enter Manually Instead")
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    imageCapture: ImageCapture,
    cameraExecutor: ExecutorService,
    onImageCaptured: (Uri) -> Unit,
    onError: (String) -> Unit,
    viewModel: SmartCaptureViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Image analysis for real-time detection
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor) { imageProxy ->
                            // Process the image for smart detection
                            viewModel.processImage(imageProxy, ContextCompat.getMainExecutor(ctx))
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    onError("Failed to bind camera: ${e.message}")
                    Log.e("CameraPreview", "Failed to bind camera", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun QuickModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CaptureButton(
    onClick: () -> Unit,
    isProcessing: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = onClick,
            enabled = !isProcessing,
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .padding(16.dp)
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Capture",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (isProcessing) "Processing..." else "Capture",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

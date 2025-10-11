package com.example.coupontracker.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.coupontracker.camera.CaptureReadiness
import com.example.coupontracker.ui.viewmodel.CameraUiState
import com.example.coupontracker.ui.viewmodel.SmartCameraViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Smart camera screen with live text detection
 * 
 * Features:
 * - CameraX preview
 * - Real-time text detection overlays
 * - Capture readiness indicators
 * - Smart crop toggle
 * - Permission handling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCameraScreen(
    onPhotoTaken: (android.net.Uri) -> Unit,
    onBackPressed: () -> Unit,
    viewModel: SmartCameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    val uiState by viewModel.uiState.collectAsState()
    val captureReadiness by viewModel.captureReadiness.collectAsState()
    val detectedText by viewModel.detectedText.collectAsState()
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    var smartCropEnabled by remember { mutableStateOf(true) }
    var imageCapture: ImageCapture? by remember { mutableStateOf(null) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }
    
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    // Handle successful capture
    LaunchedEffect(uiState) {
        if (uiState is CameraUiState.Success) {
            onPhotoTaken((uiState as CameraUiState.Success).uri)
            viewModel.resetState()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Capture") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Smart crop toggle
                    FilterChip(
                        selected = smartCropEnabled,
                        onClick = { smartCropEnabled = !smartCropEnabled },
                        label = { Text("Smart Crop") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasCameraPermission) {
                // Permission denied UI
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Camera,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera permission required",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enable camera access to capture coupon images",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission")
                    }
                }
            } else {
                // Camera preview
                CameraPreview(
                    onImageCaptureReady = { capture ->
                        imageCapture = capture
                    },
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Text detection overlays
                TextDetectionOverlay(
                    detectedText = detectedText,
                    modifier = Modifier.fillMaxSize()
                )
                
                // Bottom controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Readiness indicator
                    ReadinessIndicator(captureReadiness)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Capture button
                    Button(
                        onClick = {
                            imageCapture?.let { capture ->
                                viewModel.capturePhoto(
                                    imageCapture = capture,
                                    shouldSmartCrop = smartCropEnabled,
                                    onSuccess = { /* Handled by LaunchedEffect */ },
                                    onError = { /* Show error snackbar */ }
                                )
                            }
                        },
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = when (captureReadiness) {
                                CaptureReadiness.READY -> Color(0xFF4CAF50)
                                else -> MaterialTheme.colorScheme.primary
                            }
                        ),
                        enabled = uiState !is CameraUiState.Capturing && 
                                 uiState !is CameraUiState.Processing
                    ) {
                        if (uiState is CameraUiState.Capturing || 
                            uiState is CameraUiState.Processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Camera,
                                contentDescription = "Capture",
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreview(
    onImageCaptureReady: (ImageCapture) -> Unit,
    viewModel: SmartCameraViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                // Preview use case
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                
                // Image capture use case
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                
                onImageCaptureReady(imageCapture)
                
                // Image analysis use case (live text detection)
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            Executors.newSingleThreadExecutor(),
                            viewModel.initializeAnalyzer()
                        )
                    }
                
                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture,
                        imageAnalyzer
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CameraPreview", "Camera binding failed", e)
                }
            }, ContextCompat.getMainExecutor(ctx))
            
            previewView
        },
        modifier = modifier
    )
}

@Composable
fun TextDetectionOverlay(
    detectedText: List<com.example.coupontracker.camera.DetectedTextBlock>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        detectedText.forEach { block ->
            val rect = block.boundingBox
            
            // Draw bounding box
            drawRect(
                color = Color.Green.copy(alpha = 0.5f),
                topLeft = androidx.compose.ui.geometry.Offset(rect.left.toFloat(), rect.top.toFloat()),
                size = androidx.compose.ui.geometry.Size(rect.width().toFloat(), rect.height().toFloat()),
                style = Stroke(width = 4f)
            )
        }
    }
}

@Composable
fun ReadinessIndicator(readiness: CaptureReadiness) {
    val (text, color) = when (readiness) {
        CaptureReadiness.READY -> "Ready to capture" to Color(0xFF4CAF50)
        CaptureReadiness.LOW_CONFIDENCE -> "Move closer for better focus" to Color(0xFFFFC107)
        CaptureReadiness.INSUFFICIENT_TEXT -> "Looking for text..." to Color(0xFFFF9800)
        CaptureReadiness.NO_TEXT_DETECTED -> "No text detected" to Color(0xFFF44336)
        CaptureReadiness.NOT_READY -> "Preparing..." to Color.Gray
    }
    
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}


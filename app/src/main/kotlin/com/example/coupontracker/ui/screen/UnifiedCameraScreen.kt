package com.example.coupontracker.ui.screen

import android.Manifest
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.viewmodel.UnifiedCameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Screen for unified camera functionality (single photo and batch scanning)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun UnifiedCameraScreen(
    navController: NavController,
    viewModel: UnifiedCameraViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    
    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    
    // Camera executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    
    // Image capture use case
    val imageCapture = remember { ImageCapture.Builder().build() }
    
    // Handle permission result
    LaunchedEffect(cameraPermissionState.status) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
    
    // Handle captured image
    LaunchedEffect(uiState.capturedImageUri) {
        uiState.capturedImageUri?.let { uri ->
            // Navigate to coupon details with the captured image
            navController.navigate(
                Screen.CouponForm.createRoute(
                    imageUri = uri.toString(),
                    isBatchMode = uiState.isBatchMode
                )
            )
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
            BrandTopBar(
                title = if (uiState.isBatchMode) "Batch capture" else "Camera capture",
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Toggle between single and batch mode
                    IconButton(
                        onClick = { viewModel.toggleBatchMode() }
                    ) {
                        Icon(
                            imageVector = if (uiState.isBatchMode) 
                                Icons.Default.PhotoCamera 
                            else 
                                Icons.Default.PhotoLibrary,
                            contentDescription = if (uiState.isBatchMode) 
                                "Switch to Single Mode" 
                            else 
                                "Switch to Batch Mode"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.isBatchMode && uiState.capturedBatchImages.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { 
                        // Process all captured images in batch mode
                        viewModel.processBatchImages()
                        navController.navigate(Screen.BatchScanner.route)
                    },
                    icon = { Icon(Icons.Default.Done, contentDescription = null) },
                    text = { Text("Process ${uiState.capturedBatchImages.size} Images") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
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
                        if (uiState.isBatchMode) {
                            // Add to batch images
                            viewModel.addToBatchImages(uri)
                        } else {
                            // Set as single captured image
                            viewModel.setCapturedImage(uri)
                        }
                    },
                    onError = { error ->
                        Log.e("UnifiedCameraScreen", "Camera error: $error")
                        Toast.makeText(context, "Camera error: $error", Toast.LENGTH_SHORT).show()
                    }
                )
                
                // Capture button
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                ) {
                    IconButton(
                        onClick = {
                            viewModel.captureImage(
                                imageCapture = imageCapture,
                                executor = ContextCompat.getMainExecutor(context)
                            )
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            )
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Camera,
                            contentDescription = "Capture",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                
                // Batch image counter
                if (uiState.isBatchMode && uiState.capturedBatchImages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                            .size(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${uiState.capturedBatchImages.size}",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
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
                        text = "Camera permission is required",
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
    onError: (String) -> Unit
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
}

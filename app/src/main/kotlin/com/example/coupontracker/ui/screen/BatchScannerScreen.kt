package com.example.coupontracker.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.viewmodel.BatchScannerViewModel
import com.example.coupontracker.ui.viewmodel.ImageProcessingStatus
import com.example.coupontracker.ui.viewmodel.SelectedImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Screen for batch scanning of multiple coupons
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun BatchScannerScreen(
    navController: NavController,
    viewModel: BatchScannerViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsState()

    // Camera permission state
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Image picker launcher for multiple images
    val multipleImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Log.w("BatchScannerScreen", "Unable to persist permission for uri=$uri", e)
                }
            }
            viewModel.addImages(uris)
        }
    }

    // PDF picker launcher
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            viewModel.addPdf(it)
        }
    }

    // Check for shared images on screen load
    LaunchedEffect(Unit) {
        val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
        val sharedImageUris = sharedPrefs.getString("shared_image_uris", null)
        
        if (sharedImageUris != null) {
            // Clear the shared URIs to prevent reuse
            sharedPrefs.edit().remove("shared_image_uris").apply()
            
            try {
                // Parse the JSON array of URI strings
                val gson = com.google.gson.Gson()
                val uriStrings: List<String> = gson.fromJson(sharedImageUris, Array<String>::class.java).toList()
                val uris = uriStrings.map { Uri.parse(it) }
                
                // Add the shared images to the batch scanner
                viewModel.addImages(uris)
            } catch (e: Exception) {
                Log.e("BatchScannerScreen", "Error parsing shared image URIs", e)
            }
        }
    }

    // Reset state when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Batch Scanner") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.selectedImages.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.clearImages() }
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear All")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState.selectedImages.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.processImages() },
                    icon = { Icon(Icons.Default.Scanner, contentDescription = null) },
                    text = { Text("Scan All") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val usingFallback = viewModel.isOcrFallbackActive()

            if (usingFallback) {
                BatchScanningFallbackBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f, fill = true)
            ) {
                val batchScanningSupported = viewModel.isBatchScanningSupported()

                if (!batchScanningSupported && uiState.selectedImages.isNotEmpty() && !uiState.isProcessing) {
                    // Show warning about batch scanning unavailability
                    BatchScanningUnavailableWarning(
                        onUseSingleScan = {
                            Toast.makeText(context, "Please use single scan mode", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        },
                        onClearImages = {
                            viewModel.clearImages()
                        }
                    )
                } else when {
                uiState.isProcessing -> {
                    // Show loading indicator
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            val currentLabel = uiState.currentlyProcessingImage?.displayName
                            Text(
                                text = buildString {
                                    append("Processing ${uiState.processedCount}/${uiState.selectedImages.size} images")
                                    if (!currentLabel.isNullOrBlank()) {
                                        append("\n")
                                        append(currentLabel)
                                    }
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                uiState.processedCoupons.isNotEmpty() -> {
                    // Show processed coupons
                    ProcessedCouponsView(
                        coupons = uiState.processedCoupons,
                        imageStatuses = uiState.imageProcessingStatuses,
                        onSaveAll = {
                            viewModel.saveAllCoupons()
                            Toast.makeText(context, "All coupons saved", Toast.LENGTH_SHORT).show()
                            navController.popBackStack()
                        },
                        onEditCoupon = { index ->
                            // Navigate to CouponFormScreen with the coupon image
                            val coupon = uiState.processedCoupons[index]
                            coupon.imageUri?.let { imageUri ->
                                navController.navigate(
                                    Screen.CouponForm.createRoute(
                                        imageUri = imageUri,
                                        isBatchMode = true
                                    )
                                )
                            }
                        },
                        onRemoveCoupon = { index ->
                            viewModel.removeCoupon(index)
                        },
                        onScanMore = {
                            viewModel.resetProcessedCoupons()
                        }
                    )
                }
                uiState.selectedImages.isEmpty() -> {
                    // Show empty state with options to add images
                    EmptyBatchScannerView(
                        onAddFromGallery = {
                            multipleImagePickerLauncher.launch(arrayOf("image/*"))
                        },
                        onAddFromCamera = {
                            if (cameraPermissionState.status.isGranted) {
                                navController.navigate(Screen.UnifiedCamera.route)
                            } else {
                                cameraPermissionState.launchPermissionRequest()
                            }
                        },
                        onAddFromPdf = {
                            pdfPickerLauncher.launch("application/pdf")
                        }
                    )
                }
                else -> {
                    // Show selected images
                    SelectedImagesView(
                        images = uiState.selectedImages,
                        onRemoveImage = { index ->
                            viewModel.removeImage(index)
                        },
                        onAddMore = {
                            multipleImagePickerLauncher.launch(arrayOf("image/*"))
                        }
                    )
                }
            }
        }
    }
}
}




@Composable
fun BatchScanningFallbackBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OCR Fallback Active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "YOLO multi-coupon models are unavailable in this build. We're using OCR anchor segmentation instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Results may need a quick review before saving.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun BatchScanningUnavailableWarning(
    onUseSingleScan: () -> Unit,
    onClearImages: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Batch Scanning Unavailable",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "⚠️ Multi-coupon detection models not available",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Batch scanning requires trained YOLO models (20-100 MB) that are not yet integrated. The current placeholder models cannot detect multiple coupons.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Please use:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Text(
                    text = "• Single scan mode for individual coupons\n• OCR_FIRST strategy (recommended)\n• Manual entry for bulk addition",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onUseSingleScan,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Use Single Scan Mode")
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        OutlinedButton(
            onClick = onClearImages,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear Images")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "See CRITICAL_PRODUCTION_BLOCKERS.md for details",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun EmptyBatchScannerView(
    onAddFromGallery: () -> Unit,
    onAddFromCamera: () -> Unit,
    onAddFromPdf: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Collections,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Images Selected",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Add images to scan multiple coupons at once",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Option buttons
        Button(
            onClick = onAddFromGallery,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Select from Gallery")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onAddFromCamera,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Camera, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Take Photo")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onAddFromPdf,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Import from PDF")
        }
    }
}

@Composable
fun SelectedImagesView(
    images: List<SelectedImage>,
    onRemoveImage: (Int) -> Unit,
    onAddMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${images.size} Files Selected",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = onAddMore,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add More")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = images,
                key = { _, image -> image.uri.toString() }
            ) { index, image ->
                ImageItem(
                    image = image,
                    onRemove = { onRemoveImage(index) }
                )
            }
        }
    }
}

@Composable
fun ImageItem(
    image: SelectedImage,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (image.isImage()) {
                Image(
                    painter = rememberAsyncImagePainter(image.uri),
                    contentDescription = "Selected Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = image.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Remove button
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        shape = RoundedCornerShape(topEnd = 8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = image.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
fun ProcessedCouponsView(
    coupons: List<Coupon>,
    imageStatuses: List<ImageProcessingStatus>,
    onSaveAll: () -> Unit,
    onEditCoupon: (Int) -> Unit,
    onRemoveCoupon: (Int) -> Unit,
    onScanMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${coupons.size} Coupons Found",
                style = MaterialTheme.typography.titleMedium
            )

            Button(
                onClick = onScanMore,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Scan More")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (imageStatuses.isNotEmpty()) {
            ProcessingSummary(statuses = imageStatuses)
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(coupons.size) { index ->
                CouponItem(
                    coupon = coupons[index],
                    onEdit = { onEditCoupon(index) },
                    onRemove = { onRemoveCoupon(index) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSaveAll,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save All Coupons")
        }
    }
}

@Composable
fun CouponItem(
    coupon: Coupon,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                coupon.imageUri?.let { imageUri ->
                    Image(
                        painter = rememberAsyncImagePainter(imageUri),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: run {
                    Icon(
                        imageVector = Icons.Default.LocalOffer,
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = coupon.storeName,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row {
                        IconButton(
                            onClick = onEdit,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = coupon.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                val expiryText = remember(coupon.expiryDate) {
                    coupon.expiryDate?.let {
                        java.text.DateFormat.getDateInstance().format(it)
                    }
                }

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Amount: ₹${coupon.cashbackAmount}",
                            style = MaterialTheme.typography.bodySmall
                        )

                        coupon.redeemCode?.let {
                            Text(
                                text = "Code: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    expiryText?.let { formattedDate ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Expiry: $formattedDate",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ProcessingSummary(statuses: List<ImageProcessingStatus>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Processing Summary",
                style = MaterialTheme.typography.titleSmall
            )

            statuses.forEach { status ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val icon = if (status.success) Icons.Default.Check else Icons.Default.Error
                    val iconTint = if (status.success) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = status.image.displayName,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Text(
                            text = if (status.success) {
                                "${status.couponsFound} coupon(s) extracted"
                            } else {
                                status.message ?: "Extraction failed"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

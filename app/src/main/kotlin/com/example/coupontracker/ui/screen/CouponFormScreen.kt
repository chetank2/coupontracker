package com.example.coupontracker.ui.screen

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.UnifiedCouponForm
import com.example.coupontracker.ui.navigation.Screen
import com.example.coupontracker.ui.viewmodel.CouponFormViewModel
import com.example.coupontracker.ui.viewmodel.CouponSaveResult
import com.example.coupontracker.ui.viewmodel.ScannerViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Screen for editing coupon details with a consistent form
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponFormScreen(
    navController: NavController,
    imageUri: String?,
    isBatchMode: Boolean = false,
    viewModel: CouponFormViewModel = hiltViewModel(),
    scannerViewModel: ScannerViewModel? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    val persistedImageUri = uiState.persistedImageUri
    val previewImageUriString = persistedImageUri ?: imageUri
    val previewImageUri = previewImageUriString
        ?.takeIf { it.isNotBlank() }
        ?.let { Uri.parse(it) }

    // Form fields
    var storeName by remember { mutableStateOf(uiState.couponInfo?.storeName ?: "") }

    var description by remember { mutableStateOf(uiState.couponInfo?.description ?: "") }
    var amount by remember { mutableStateOf(uiState.couponInfo?.cashbackAmount?.toString() ?: "") }
    var code by remember { mutableStateOf(uiState.couponInfo?.redeemCode ?: "") }
    var expiryDateString by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(uiState.couponInfo?.category ?: "") }

    // Initialize with image URI
    LaunchedEffect(imageUri) {
        imageUri?.let { uri ->
            if (uri.isNotEmpty()) {
                viewModel.processImageUri(Uri.parse(uri))
            }
        }
    }

    // Update form fields when coupon info changes
    LaunchedEffect(uiState.couponInfo) {
        uiState.couponInfo?.let { info ->
            storeName = info.storeName
            description = info.description
            amount = info.cashbackAmount?.toString() ?: ""
            code = info.redeemCode ?: ""
            category = info.category ?: ""

            // Format expiry date if available
            val expiryDate = info.expiryDate
            if (expiryDate != null) {
                val format = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                expiryDateString = format.format(expiryDate)
            } else {
                expiryDateString = ""
            }
        }
    }

    // Handle save result
    LaunchedEffect(uiState.saveResult) {
        val result = uiState.saveResult ?: return@LaunchedEffect
        val message = when (result) {
            CouponSaveResult.SAVED -> "Coupon saved successfully"
            CouponSaveResult.ALREADY_SAVED -> "Coupon already saved"
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        scannerViewModel?.clearPendingPreview()

        if (isBatchMode) {
            navController.navigate(Screen.BatchScanner.route) {
                popUpTo(Screen.BatchScanner.route) { inclusive = true }
            }
        } else {
            navController.navigate(Screen.Home.route) {
                popUpTo(Screen.Home.route) { inclusive = true }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    // This screen is used for adding new coupons from images
                    Text("Add Coupon") 
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        // Show loading indicator while processing
        if (uiState.isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Extracting coupon data...",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "This may take up to 1 minute",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            UnifiedCouponForm(
                storeName = storeName,
            onStoreNameChange = { storeName = it },
            description = description,
            onDescriptionChange = { description = it },
            amount = amount,
            onAmountChange = { amount = it },
            code = code,
            onCodeChange = { code = it },
            expiryDateString = expiryDateString,
            onExpiryDateStringChange = { expiryDateString = it },
            category = category,
            onCategoryChange = { category = it },
            imageUri = previewImageUri,
            onSave = {
                val amountValue = amount.toDoubleOrNull() ?: 0.0
                val expiryDate = try {
                    if (expiryDateString.isNotBlank()) {
                        val format = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                        format.parse(expiryDateString)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }

                viewModel.saveCoupon(
                    storeName = storeName,
                    description = description,
                    amount = amountValue,
                    code = code,
                    expiryDate = expiryDate,
                    category = category,
                    imageUri = previewImageUriString?.takeIf { it.isNotBlank() }
                )
            },
            isSaving = uiState.isSaving,
            error = uiState.error,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
            )
        }
    }
}

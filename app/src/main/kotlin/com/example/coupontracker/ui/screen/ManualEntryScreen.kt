package com.example.coupontracker.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.UnifiedCouponForm
import com.example.coupontracker.util.ExtractionLogBuffer
import com.example.coupontracker.ui.viewmodel.ManualEntryViewModel
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Screen for manual entry of coupon details
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryScreen(
    navController: NavController,
    viewModel: ManualEntryViewModel = hiltViewModel(),
    initialCode: String? = null
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val clipboard = remember {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    // Form fields
    var storeName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(initialCode ?: "") }
    var expiryDateString by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }

    // Date picker state
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    // Initialize with URL data if available
    LaunchedEffect(uiState.urlData) {
        uiState.urlData?.let { data ->
            storeName = data.storeName
            description = data.description
            amount = data.amount?.toString() ?: ""
            code = data.code ?: ""
        }
    }

    // Handle save result
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            Toast.makeText(context, "Coupon saved successfully", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manual Entry") },
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
            // Main content with UnifiedCouponForm
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
                imageUri = null,
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
                        category = category
                    )
                },
                onCopyLogs = {
                    val logText = ExtractionLogBuffer.getLogText().ifBlank { "No log data recorded yet." }
                    val clip = ClipData.newPlainText("Coupon Extraction Log", logText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Logcat data copied", Toast.LENGTH_SHORT).show()
                    ExtractionLogBuffer.appendInfo("ManualEntryScreen", "Log data copied to clipboard (${logText.length} chars)")
                },
                isSaving = uiState.isSaving,
                error = uiState.error,
                modifier = Modifier
                    .padding(16.dp)
            )

            // URL processing field at the bottom
            if (!uiState.isProcessingUrl) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.url ?: "",
                        onValueChange = { viewModel.setUrl(it) },
                        label = { Text("Enter URL (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null)
                        },
                        trailingIcon = {
                            if (!uiState.url.isNullOrBlank()) {
                                IconButton(onClick = { viewModel.processUrl() }) {
                                    Icon(Icons.Default.Search, contentDescription = "Process URL")
                                }
                            }
                        }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Processing URL...",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

package com.example.coupontracker.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.viewmodel.ManualEntryViewModel
import java.text.SimpleDateFormat
import java.util.*

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
    
    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Date(millis)
                            val format = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                            expiryDateString = format.format(date)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false }
                ) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Store name field
            OutlinedTextField(
                value = storeName,
                onValueChange = { storeName = it },
                label = { Text("Store Name") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Store, contentDescription = null)
                }
            )
            
            // Description field
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
            )
            
            // Amount field
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount (₹)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.CurrencyRupee, contentDescription = null)
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            // Code field
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("Redeem Code") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Code, contentDescription = null)
                }
            )
            
            // Expiry date field
            OutlinedTextField(
                value = expiryDateString,
                onValueChange = { expiryDateString = it },
                label = { Text("Expiry Date (MM/DD/YYYY)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Select Date")
                    }
                },
                readOnly = true
            )
            
            // Category field
            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Category, contentDescription = null)
                }
            )
            
            // URL field for processing
            if (!uiState.isProcessingUrl) {
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
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Processing URL...",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Save button
            Button(
                onClick = {
                    val amountValue = amount.toDoubleOrNull() ?: 0.0
                    val expiryDate = try {
                        if (expiryDateString.isNotBlank()) {
                            val format = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                            format.parse(expiryDateString)
                        } else {
                            // Default to 30 days from now
                            val calendar = Calendar.getInstance()
                            calendar.add(Calendar.DAY_OF_YEAR, 30)
                            calendar.time
                        }
                    } catch (e: Exception) {
                        // Default to 30 days from now
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.DAY_OF_YEAR, 30)
                        calendar.time
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
                modifier = Modifier.fillMaxWidth(),
                enabled = storeName.isNotBlank() && !uiState.isProcessingUrl && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Coupon")
                }
            }
            
            // Error message
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

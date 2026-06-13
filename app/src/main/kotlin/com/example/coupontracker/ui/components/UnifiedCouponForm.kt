package com.example.coupontracker.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A unified coupon form component that ensures consistent input fields
 * across all entry methods (camera, upload, manual entry)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedCouponForm(
    storeName: String,
    onStoreNameChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    expiryDateString: String,
    onExpiryDateStringChange: (String) -> Unit,
    category: String,
    onCategoryChange: (String) -> Unit,
    imageUri: Uri?,
    onSave: () -> Unit,
    onCopyLogs: () -> Unit,
    isSaving: Boolean = false,
    error: String? = null,
    modifier: Modifier = Modifier,
    extraContentBeforeActions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    
    // Date picker state
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Image preview if available
        imageUri?.let { uri ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 8.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(uri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Coupon Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        BrandTextField(
            value = storeName,
            onValueChange = onStoreNameChange,
            label = "Store name",
            modifier = Modifier.fillMaxWidth()
        )
        
        BrandTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = "Description",
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
        )
        
        BrandTextField(
            value = code,
            onValueChange = onCodeChange,
            label = "Redeem code",
            modifier = Modifier.fillMaxWidth()
        )
        
        BrandTextField(
            value = expiryDateString,
            onValueChange = onExpiryDateStringChange,
            label = "Expiry date (MM/DD/YYYY)",
            modifier = Modifier.fillMaxWidth(),
            readOnly = true
        )
        BrandButton(
            text = "Select expiry date",
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth(),
            tier = BrandButtonTier.Secondary,
        )
        
        BrandTextField(
            value = category,
            onValueChange = onCategoryChange,
            label = "Category (optional)",
            modifier = Modifier.fillMaxWidth()
        )

        extraContentBeforeActions?.invoke(this)
        
        BrandButton(
            text = if (isSaving) "Saving..." else "Save coupon",
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
            enabled = storeName.isNotBlank() && !isSaving
        )

        BrandButton(
            text = "Copy diagnostic data",
            onClick = onCopyLogs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            tier = BrandButtonTier.Secondary,
        )
        
        // Error message
        error?.let { errorMsg ->
            Text(
                text = errorMsg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        
        // Date picker dialog
        if (showDatePicker) {
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    BrandButton(
                        text = "OK",
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val date = Date(millis)
                                val format = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault())
                                onExpiryDateStringChange(format.format(date))
                            }
                            showDatePicker = false
                        }
                    )
                },
                dismissButton = {
                    BrandButton(
                        text = "Cancel",
                        onClick = { showDatePicker = false },
                        tier = BrandButtonTier.Tertiary,
                    )
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }
}

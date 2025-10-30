package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.theme.BrandSpacing
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog for collecting user feedback on coupon extractions
 * Used to improve the universal extraction system through learning
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionFeedbackDialog(
    coupon: Coupon,
    onConfirmCorrect: () -> Unit,
    onSubmitCorrection: (CorrectedCoupon) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCorrectionForm by remember { mutableStateOf(false) }
    var correctedStoreName by remember { mutableStateOf(coupon.storeName) }
    var correctedCode by remember { mutableStateOf(coupon.redeemCode ?: "") }
    var correctedSavingsDetail by remember { mutableStateOf(coupon.getCashbackDisplayText()) }
    var correctedExpiry by remember { mutableStateOf(
        coupon.expiryDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it) } ?: ""
    ) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(BrandSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
            ) {
                Text(
                    text = "How did we do?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Help us improve by confirming if this extraction is correct:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show extracted information
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(BrandSpacing.Medium),
                        verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
                    ) {
                        ExtractionField("Store", coupon.storeName)
                        ExtractionField("Code", coupon.redeemCode ?: "Not found")
                        ExtractionField("Savings", coupon.getCashbackDisplayText())
                        ExtractionField(
                            "Expiry", 
                            coupon.expiryDate?.let { 
                                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(it) 
                            } ?: "Not found"
                        )
                    }
                }

                if (!showCorrectionForm) {
                    // Feedback buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
                    ) {
                        OutlinedButton(
                            onClick = { showCorrectionForm = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null)
                            Spacer(modifier = Modifier.width(BrandSpacing.Small))
                            Text("Needs Correction")
                        }
                        
                        Button(
                            onClick = onConfirmCorrect,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null)
                            Spacer(modifier = Modifier.width(BrandSpacing.Small))
                            Text("Looks Good!")
                        }
                    }
                } else {
                    // Correction form
                    Text(
                        text = "Please correct the fields below:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    OutlinedTextField(
                        value = correctedStoreName,
                        onValueChange = { correctedStoreName = it },
                        label = { Text("Store Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = correctedCode,
                        onValueChange = { correctedCode = it },
                        label = { Text("Coupon Code (leave empty if none)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = correctedSavingsDetail,
                        onValueChange = { correctedSavingsDetail = it },
                        label = { Text("Savings Detail (e.g., Cashback: ₹500 off)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = correctedExpiry,
                        onValueChange = { correctedExpiry = it },
                        label = { Text("Expiry Date (DD/MM/YYYY)") },
                        placeholder = { Text("31/12/2024") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
                    ) {
                        OutlinedButton(
                            onClick = { showCorrectionForm = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        
                        Button(
                            onClick = {
                                val correction = CorrectedCoupon(
                                    storeName = correctedStoreName.takeIf { it.isNotBlank() },
                                    redeemCode = correctedCode.takeIf { it.isNotBlank() },
                                    cashbackDetail = correctedSavingsDetail.takeIf { it.isNotBlank() },
                                    expiryDate = correctedExpiry.takeIf { it.isNotBlank() }
                                )
                                onSubmitCorrection(correction)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Submit")
                        }
                    }
                }

                // Skip button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip Feedback")
                }
            }
        }
    }
}

@Composable
private fun ExtractionField(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(2f)
        )
    }
}

/**
 * Data class for user corrections
 */
data class CorrectedCoupon(
    val storeName: String?,
    val redeemCode: String?,
    val cashbackDetail: String?,
    val expiryDate: String?
)

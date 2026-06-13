package com.example.coupontracker.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.theme.BrandShapes
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.theme.BrandTypography
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
            shape = BrandShapes.Large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(BrandSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
            ) {
                Text(
                    text = "How did we do?",
                    style = BrandTypography.TitleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = "Help improve future scans by confirming these coupon details.",
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
                        BrandButton(
                            text = "Needs correction",
                            onClick = { showCorrectionForm = true },
                            modifier = Modifier.weight(1f),
                            tier = BrandButtonTier.Secondary,
                        )
                        
                        BrandButton(
                            text = "Looks good",
                            onClick = onConfirmCorrect,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    // Correction form
                    Text(
                        text = "Please correct the fields below:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )

                    BrandTextField(
                        value = correctedStoreName,
                        onValueChange = { correctedStoreName = it },
                        label = "Store name",
                        modifier = Modifier.fillMaxWidth()
                    )

                    BrandTextField(
                        value = correctedCode,
                        onValueChange = { correctedCode = it },
                        label = "Coupon code (optional)",
                        modifier = Modifier.fillMaxWidth()
                    )

                    BrandTextField(
                        value = correctedSavingsDetail,
                        onValueChange = { correctedSavingsDetail = it },
                        label = "Savings detail",
                        modifier = Modifier.fillMaxWidth()
                    )

                    BrandTextField(
                        value = correctedExpiry,
                        onValueChange = { correctedExpiry = it },
                        label = "Expiry date",
                        placeholder = "31/12/2026",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
                    ) {
                        BrandButton(
                            text = "Cancel",
                            onClick = { showCorrectionForm = false },
                            modifier = Modifier.weight(1f),
                            tier = BrandButtonTier.Secondary,
                        )
                        
                        BrandButton(
                            text = "Submit",
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
                        )
                    }
                }

                // Skip button
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip")
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

package com.example.coupontracker.ui.review

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.extraction.ExtractionValidator
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.data.util.DescriptionUtils

/**
 * Multi-Coupon Preview Screen
 * Shows detected coupons from a screenshot before batch saving
 * Allows user to review, edit, or remove individual coupons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCouponPreviewScreen(
    navController: NavController,
    extractionResult: MultiCouponExtractionService.MultiCouponResult,
    onSaveAll: (List<Coupon>) -> Unit,
    onCancel: () -> Unit
) {
    var coupons by remember { mutableStateOf(extractionResult.coupons.toMutableList()) }
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BrandTopBar(
                title = "Review ${coupons.size} coupons",
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !isSaving
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            isSaving = true
                            onSaveAll(coupons.map { it.coupon })
                        },
                        enabled = coupons.isNotEmpty() && !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Save ${coupons.size} Coupons")
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Summary Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Screenshot Extraction Summary",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    SummaryRow("Screenshot Type:", extractionResult.screenshotType.name)
                    SummaryRow("Detected Regions:", "${extractionResult.totalDetected}")
                    SummaryRow("Successfully Extracted:", "${extractionResult.totalExtracted}")
                    if (extractionResult.totalFiltered > 0) {
                        SummaryRow(
                            "Filtered (Low Quality):",
                            "${extractionResult.totalFiltered}",
                            isWarning = true
                        )
                    }
                }
            }

            // Coupon List
            if (coupons.isEmpty()) {
                EmptyStateContent()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(coupons, key = { it.coupon.hashCode() }) { couponWithConf ->
                        CouponPreviewCard(
                            couponWithConfidence = couponWithConf,
                            onRemove = {
                                coupons = coupons.filter { it != couponWithConf }.toMutableList()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    isWarning: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isWarning) MaterialTheme.colorScheme.error
                   else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun CouponPreviewCard(
    couponWithConfidence: MultiCouponExtractionService.CouponWithConfidence,
    onRemove: () -> Unit
) {
    val coupon = couponWithConfidence.coupon
    val quality = couponWithConfidence.extractionQuality

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (quality) {
                ExtractionValidator.ExtractionQuality.EXCELLENT,
                ExtractionValidator.ExtractionQuality.GOOD ->
                    MaterialTheme.colorScheme.surface
                ExtractionValidator.ExtractionQuality.ACCEPTABLE ->
                    MaterialTheme.colorScheme.surfaceVariant
                else ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with quality badge and remove button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QualityBadge(
                    quality = quality,
                    confidence = couponWithConfidence.confidence
                )

                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove coupon",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Store Name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Store,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = coupon.storeName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            if (coupon.description.isNotBlank()) {
                Text(
                    text = coupon.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Coupon Code
            if (!coupon.redeemCode.isNullOrBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocalOffer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Code: ${coupon.redeemCode}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            DescriptionUtils.extractCashbackLine(coupon.description)?.let { detail ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.LocalOffer,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Warnings (if any)
            if (couponWithConfidence.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                couponWithConfidence.warnings.take(2).forEach { warning ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun QualityBadge(
    quality: ExtractionValidator.ExtractionQuality,
    confidence: Float
) {
    val (color, icon, label) = when (quality) {
        ExtractionValidator.ExtractionQuality.EXCELLENT ->
            Triple(MaterialTheme.colorScheme.primary, Icons.Default.CheckCircle, "Excellent")
        ExtractionValidator.ExtractionQuality.GOOD ->
            Triple(MaterialTheme.colorScheme.tertiary, Icons.Default.ThumbUp, "Good")
        ExtractionValidator.ExtractionQuality.ACCEPTABLE ->
            Triple(MaterialTheme.colorScheme.secondary, Icons.Default.Check, "OK")
        ExtractionValidator.ExtractionQuality.POOR ->
            Triple(MaterialTheme.colorScheme.error, Icons.Default.Warning, "Low Quality")
        ExtractionValidator.ExtractionQuality.FAILED ->
            Triple(MaterialTheme.colorScheme.error, Icons.Default.Error, "Failed")
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
            Text(
                text = "$label (${(confidence * 100).toInt()}%)",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun EmptyStateContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "No coupons to preview",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "All detected coupons were filtered due to low quality",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

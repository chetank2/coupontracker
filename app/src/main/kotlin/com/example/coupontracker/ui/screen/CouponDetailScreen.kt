package com.example.coupontracker.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.coupontracker.ui.components.ImagePreviewDialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.R
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.ui.components.DateFormatter
import com.example.coupontracker.ui.components.ExtractionDebugPanel
import com.example.coupontracker.data.model.CashbackType
import com.example.coupontracker.ui.components.StatusType
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.DetailViewModel
import com.example.coupontracker.util.GenericFieldHeuristics
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponDetailScreen(
    navController: NavController,
    couponId: Long,
    viewModel: DetailViewModel = hiltViewModel()
) {
    // State for image preview
    var showImagePreview by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isDebugBuild = remember {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    val coupon by viewModel.coupon.collectAsState()
    val debugSnapshot by viewModel.debugSnapshot.collectAsState()

    // Load the coupon when the screen is first displayed
    LaunchedEffect(couponId) {
        viewModel.loadCoupon(couponId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Coupon Details") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val shareEnabled = coupon != null
                    IconButton(onClick = {
                        coupon?.let { shareCoupon(context, it) }
                    }, enabled = shareEnabled) {
                        Icon(Icons.Default.Share, contentDescription = "Share coupon")
                    }
                    IconButton(onClick = {
                        viewModel.deleteCoupon()
                        navController.popBackStack()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
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
            coupon?.let { coupon ->
                CouponDetailContent(
                    coupon = coupon,
                    showImagePreview = showImagePreview,
                    onToggleImagePreview = { showImagePreview = it },
                    onTrackUsage = { viewModel.trackUsage(coupon.cashbackAmount) },
                    context = context,
                    debugSnapshot = debugSnapshot,
                    isDebugBuild = isDebugBuild
                )
            } ?: run {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun CouponDetailContent(
    coupon: com.example.coupontracker.data.model.Coupon,
    showImagePreview: Boolean,
    onToggleImagePreview: (Boolean) -> Unit,
    onTrackUsage: () -> Unit,
    context: Context,
    debugSnapshot: ExtractionDebugSnapshot?,
    isDebugBuild: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BrandSpacing.Medium)
    ) {
        // Coupon image (if available)
        coupon.imageUri?.let { imageUri ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = BrandSpacing.Medium)
                    .clickable { onToggleImagePreview(true) },
                shape = RoundedCornerShape(12.dp)
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Coupon Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Image preview dialog
            if (showImagePreview) {
                ImagePreviewDialog(
                    imageUri = imageUri,
                    onDismiss = { onToggleImagePreview(false) },
                    onShare = {
                        // Share the image
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(imageUri))
                            type = "image/*"
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Coupon Image"))
                    }
                )
            }
        }

        // Store name and icon
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Store icon placeholder
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = coupon.storeName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(BrandSpacing.Medium))

            Text(
                text = coupon.storeName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        // Description
        Text(
            text = coupon.description,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        ExtractionQualityCard(coupon = coupon)

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        // Coupon code
        if (!coupon.redeemCode.isNullOrEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BrandSpacing.Medium),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = coupon.redeemCode,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Coupon code", coupon.redeemCode)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                    }
                }
            }

            Spacer(modifier = Modifier.height(BrandSpacing.Large))
        }

        if (isDebugBuild && debugSnapshot != null) {
            ExtractionDebugPanel(snapshot = debugSnapshot)
            Spacer(modifier = Modifier.height(BrandSpacing.Large))
        }

        // Expiry date
        val expiryStatus = DateFormatter.getExpiryStatus(coupon.expiryDate)
        val expiryText = DateFormatter.getExpiryText(coupon.expiryDate)
        val expiryColor = when (expiryStatus) {
            StatusType.ERROR -> Color(0xFFE53935)
            StatusType.WARNING -> Color(0xFFFFA000)
            StatusType.SUCCESS -> Color(0xFF43A047)
            StatusType.INFO, StatusType.NEUTRAL -> Color(0xFF2196F3)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Event,
                contentDescription = null,
                tint = expiryColor
            )

            Spacer(modifier = Modifier.width(BrandSpacing.Small))

            Text(
                text = coupon.expiryDate?.let { DateFormatter.formatShort(it) }
                    ?: stringResource(id = R.string.no_expiry_provided),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.width(BrandSpacing.Medium))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = expiryColor.copy(alpha = 0.1f)
            ) {
                Text(
                    text = expiryText,
                    style = MaterialTheme.typography.labelMedium,
                    color = expiryColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

        // Cashback amount - HIDDEN per user request
        // User: "Don't show the amount" - description field has full offer text
        /*
        val cashbackDisplayText = coupon.getCashbackDisplayText()
        if (cashbackDisplayText.isNotBlank() && coupon.getCashbackNumericValue() > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (coupon.getCashbackInfo().type == CashbackType.PERCENT) 
                        Icons.Default.Percent else Icons.Default.CurrencyRupee,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.width(BrandSpacing.Small))

                Text(
                    text = cashbackDisplayText, // Shows "75%" or "₹500" correctly
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }
        */

        // Category
        if (!coupon.category.isNullOrEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Category,
                    contentDescription = null
                )

                Spacer(modifier = Modifier.width(BrandSpacing.Small))

                Text(
                    text = coupon.category,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = {
                    onTrackUsage()
                    Toast.makeText(context, "Usage tracked", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(BrandSpacing.Small))
                Text("Track Usage")
            }

            Spacer(modifier = Modifier.width(BrandSpacing.Medium))

            Button(
                onClick = {
                    // Set reminder
                    Toast.makeText(context, "Reminder functionality would be implemented here", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Notifications, contentDescription = null)
                Spacer(modifier = Modifier.width(BrandSpacing.Small))
                Text("Set Reminder")
            }
        }
    }
}

@Composable
private fun ExtractionQualityCard(coupon: com.example.coupontracker.data.model.Coupon) {
    val insights = remember(coupon) { deriveQualityInsights(coupon) }
    val statusColor = when (insights.status) {
        QualityStatus.EXCELLENT -> MaterialTheme.colorScheme.primary
        QualityStatus.GOOD -> MaterialTheme.colorScheme.tertiary
        QualityStatus.REVIEW -> Color(0xFFFFA000)
        QualityStatus.POOR -> MaterialTheme.colorScheme.error
    }
    val statusIcon = insights.status.icon
    val score = insights.score.coerceIn(0, 100)
    val progress = (score / 100f).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(BrandSpacing.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Quality score",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(BrandSpacing.Tiny))
                    Text(
                        text = "Based on the fields captured from your screenshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = statusColor.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(18.dp)
                        )

                        Spacer(modifier = Modifier.width(BrandSpacing.Tiny))

                        Text(
                            text = insights.status.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
            ) {
                Text(
                    text = score.toString(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )

                Column(modifier = Modifier.weight(1f)) {
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = statusColor,
                        trackColor = statusColor.copy(alpha = 0.16f)
                    )

                    Spacer(modifier = Modifier.height(BrandSpacing.Tiny))

                    Text(
                        text = "$score / 100 confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(BrandSpacing.Small))

            Text(
                text = insights.status.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))

            insights.metrics.forEach { metric ->
                val metricColor = if (metric.isAchieved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (metric.isAchieved) metricColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = metric.icon,
                            contentDescription = null,
                            tint = metricColor,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(BrandSpacing.Small))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (metric.isAchieved) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = metric.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "${metric.earnedPoints}/${metric.maxPoints}",
                        style = MaterialTheme.typography.labelMedium,
                        color = metricColor,
                        fontWeight = if (metric.isAchieved) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

private data class QualityInsights(
    val score: Int,
    val metrics: List<QualityMetric>,
    val status: QualityStatus
)

private data class QualityMetric(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val earnedPoints: Int,
    val maxPoints: Int
) {
    val isAchieved: Boolean get() = earnedPoints > 0
}

private enum class QualityStatus(val label: String, val message: String, val icon: ImageVector) {
    EXCELLENT("Excellent", "All critical fields look reliable.", Icons.Default.Verified),
    GOOD("Good", "Most key details look solid.", Icons.Default.ThumbUp),
    REVIEW("Needs review", "Some important fields are missing or generic.", Icons.Default.Warning),
    POOR("Incomplete", "Critical details are missing. Consider rescanning or editing.", Icons.Default.Error)
}

private fun deriveQualityInsights(coupon: com.example.coupontracker.data.model.Coupon): QualityInsights {
    val storeValid = !GenericFieldHeuristics.isGenericOrMissing(coupon.storeName)
    val codeValid = !GenericFieldHeuristics.isGenericOrMissingCode(coupon.redeemCode)
    val numericValue = coupon.getCashbackNumericValue()
    val amountValid = !GenericFieldHeuristics.isZeroOrMeaningless(numericValue)
    val expiryDate = coupon.expiryDate
    val descriptionValid = GenericFieldHeuristics.isMeaningfulDescription(coupon.description)

    val expiryDescription = when {
        expiryDate == null -> "Add an expiry date to get reminders"
        expiryDate.before(Date()) -> "Expiry date captured (already past)"
        else -> "Expiry date captured"
    }

    val metrics = listOf(
        QualityMetric(
            label = "Store recognition",
            description = if (storeValid) "Recognized a specific store name" else "Store name looks generic",
            icon = Icons.Default.Store,
            earnedPoints = if (storeValid) 25 else 0,
            maxPoints = 25
        ),
        QualityMetric(
            label = "Code readiness",
            description = if (codeValid) "Coupon code looks redeemable" else "Code missing or placeholder",
            icon = Icons.Default.Key,
            earnedPoints = if (codeValid) 30 else 0,
            maxPoints = 30
        ),
        QualityMetric(
            label = "Savings detected",
            description = if (amountValid) "Cashback or discount captured" else "Savings not detected",
            icon = Icons.Default.CurrencyRupee,
            earnedPoints = if (amountValid) 20 else 0,
            maxPoints = 20
        ),
        QualityMetric(
            label = "Expiry tracking",
            description = expiryDescription,
            icon = Icons.Default.Event,
            earnedPoints = if (expiryDate != null) 15 else 0,
            maxPoints = 15
        ),
        QualityMetric(
            label = "Offer clarity",
            description = if (descriptionValid) "Offer description looks specific" else "Description looks incomplete",
            icon = Icons.Default.Article,
            earnedPoints = if (descriptionValid) 10 else 0,
            maxPoints = 10
        )
    )

    val score = metrics.sumOf { it.earnedPoints }.coerceIn(0, 100)
    val status = when {
        score >= 85 -> QualityStatus.EXCELLENT
        score >= 70 -> QualityStatus.GOOD
        score >= 50 -> QualityStatus.REVIEW
        else -> QualityStatus.POOR
    }

    return QualityInsights(score = score, metrics = metrics, status = status)
}

private fun shareCoupon(context: Context, coupon: com.example.coupontracker.data.model.Coupon) {
    val shareBody = buildString {
        append("Store: ${coupon.storeName}\n")
        if (coupon.description.isNotBlank()) append("Offer: ${coupon.description}\n")
        coupon.redeemCode?.takeIf { it.isNotBlank() }?.let { append("Code: $it\n") }
        coupon.expiryDate?.let { append("Expires: ${com.example.coupontracker.ui.components.DateFormatter.formatShort(it)}\n") }
    }

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        putExtra(Intent.EXTRA_TEXT, shareBody)
        type = "text/plain"
    }

    coupon.imageUri?.let { uriString ->
        val uri = Uri.parse(uriString)
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        shareIntent.type = "image/*"
    }

    context.startActivity(Intent.createChooser(shareIntent, "Share coupon"))
}

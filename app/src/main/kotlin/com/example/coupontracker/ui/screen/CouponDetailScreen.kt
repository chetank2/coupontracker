package com.example.coupontracker.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.components.CouponCard
import com.example.coupontracker.ui.components.CouponCardModel
import com.example.coupontracker.ui.components.CouponCardState
import com.example.coupontracker.ui.components.CouponCardVariant
import com.example.coupontracker.ui.components.DateFormatter
import com.example.coupontracker.ui.components.ExtractionDebugPanel
import com.example.coupontracker.ui.components.StatusType
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.DetailViewModel
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.data.util.DescriptionUtils
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
            BrandTopBar(
                title = "Coupon details",
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
                    onTrackUsage = { viewModel.trackUsage() },
                    onSetReminderLeadTime = { minutes -> viewModel.setReminderLeadTime(minutes) },
                    onCancelReminder = { viewModel.cancelReminder() },
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
    onSetReminderLeadTime: (Int) -> Unit,
    onCancelReminder: () -> Unit,
    context: Context,
    debugSnapshot: ExtractionDebugSnapshot?,
    isDebugBuild: Boolean
) {
    val displayDescription = remember(coupon.description, coupon.storeName, coupon.redeemCode) {
        DescriptionUtils.formatDisplayDescription(
            description = coupon.description,
            storeName = coupon.storeName,
            redeemCode = coupon.redeemCode
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BrandSpacing.Medium)
    ) {
        CouponCard(
            model = coupon.toDetailCouponCardModel(displayDescription),
            state = CouponCardState.Default,
            variant = CouponCardVariant.WalletStack,
            isHero = true,
            onTap = {},
            onRedeem = {
                onTrackUsage()
                Toast.makeText(context, "Usage tracked", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.padding(bottom = BrandSpacing.Medium),
        )

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

        Text(
            text = "Offer",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(BrandSpacing.Tiny))
        Text(
            text = displayDescription,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(BrandSpacing.Large))

        // Coupon code
        if (!coupon.redeemCode.isNullOrEmpty()) {
            Text(
                text = "Code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(BrandSpacing.Tiny))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
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

            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
        }

        if (isDebugBuild && debugSnapshot != null) {
            ExtractionQualityCard(coupon = coupon)
            Spacer(modifier = Modifier.height(BrandSpacing.Medium))
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

        CouponActionButtons(
            coupon = coupon,
            onTrackUsageClick = {
                onTrackUsage()
                Toast.makeText(context, "Usage tracked", Toast.LENGTH_SHORT).show()
            },
            onSetReminderLeadTime = { minutes ->
                onSetReminderLeadTime(minutes)
                Toast.makeText(context, "Reminder saved", Toast.LENGTH_SHORT).show()
            },
            onCancelReminderClick = {
                onCancelReminder()
                Toast.makeText(context, "Reminder removed", Toast.LENGTH_SHORT).show()
            },
        )

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

        Spacer(modifier = Modifier.height(BrandSpacing.Large))
    }
}

private fun com.example.coupontracker.data.model.Coupon.toDetailCouponCardModel(
    displayDescription: String,
): CouponCardModel {
    val initial = storeName.firstOrNull { it.isLetterOrDigit() } ?: 'C'
    return CouponCardModel(
        brandName = storeName,
        brandInitial = initial,
        brandColor = null,
        valueLabel = detailCardOfferSummary(displayDescription),
        code = redeemCode.orEmpty(),
        expiresAt = DateFormatter.formatShort(expiryDate),
    )
}

private fun com.example.coupontracker.data.model.Coupon.detailCardOfferSummary(
    displayDescription: String,
): String {
    val cashback = getCashbackDisplayText()
        .replace(Regex("\\s+"), " ")
        .trim()
        .takeIf { it.isNotBlank() && it.length <= 36 && !it.equals("0.0", ignoreCase = true) }

    if (cashback != null) return cashback

    val normalizedDescription = displayDescription
        .replace(Regex("\\s+"), " ")
        .trim()
    val offerPattern = Regex(
        pattern = """(?i)(₹\s*\d[\d,]*(?:\s*off)?|\d+\s*%\s*(?:off|cashback)?|flat\s+[^.]{1,32}|free\s+[^.]{1,32}|save\s+[^.]{1,32})""",
    )
    val matchedOffer = offerPattern.find(normalizedDescription)?.value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(44)

    return matchedOffer?.ifBlank { null }
        ?: when {
            normalizedDescription.contains("voucher", ignoreCase = true) -> "Saved voucher"
            normalizedDescription.contains("coupon", ignoreCase = true) -> "Saved coupon"
            else -> "Saved offer"
        }
}

@Composable
private fun CouponActionButtons(
    coupon: com.example.coupontracker.data.model.Coupon,
    onTrackUsageClick: () -> Unit,
    onSetReminderLeadTime: (Int) -> Unit,
    onCancelReminderClick: () -> Unit,
) {
    var reminderMenuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(BrandSpacing.Small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
        ) {
            Button(
                onClick = onTrackUsageClick,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(BrandSpacing.Small))
                Text(if (coupon.status.equals("Used", ignoreCase = true)) "Used again" else "Mark used")
            }

            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { reminderMenuExpanded = true },
                    enabled = coupon.expiryDate != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                    Spacer(modifier = Modifier.width(BrandSpacing.Small))
                    Text(if (coupon.reminderLeadTimeMinutes != null) "Reminder" else "Remind me")
                }

                DropdownMenu(
                    expanded = reminderMenuExpanded,
                    onDismissRequest = { reminderMenuExpanded = false }
                ) {
                    ReminderOption("At expiry", 0, onSetReminderLeadTime) {
                        reminderMenuExpanded = false
                    }
                    ReminderOption("1 hour before", 60, onSetReminderLeadTime) {
                        reminderMenuExpanded = false
                    }
                    ReminderOption("24 hours before", 1440, onSetReminderLeadTime) {
                        reminderMenuExpanded = false
                    }
                    ReminderOption("48 hours before", 2880, onSetReminderLeadTime) {
                        reminderMenuExpanded = false
                    }
                    if (coupon.reminderLeadTimeMinutes != null) {
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Remove reminder") },
                            onClick = {
                                reminderMenuExpanded = false
                                onCancelReminderClick()
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Used ${coupon.usageCount} time${if (coupon.usageCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            coupon.usageLimit?.takeIf { it > 0 }?.let { limit ->
                Text(
                    text = "• Limit $limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            coupon.reminderDate?.let { reminderDate ->
                Text(
                    text = "• Reminder ${DateFormatter.formatShort(reminderDate)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReminderOption(
    label: String,
    minutes: Int,
    onSetReminderLeadTime: (Int) -> Unit,
    onSelected: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            onSetReminderLeadTime(minutes)
            onSelected()
        }
    )
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
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = BrandSpacing.Medium, vertical = BrandSpacing.Small),
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
                        text = if (expanded) {
                            "Hide quality score details"
                        } else {
                            "Tap to view quality score details"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BrandSpacing.Small)
                ) {
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

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(BrandSpacing.Medium)
                    ) {
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
                                    text = insights.scoreLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                insights.secondaryLabel?.let { secondary ->
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = secondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(BrandSpacing.Small))

                        if (!insights.telemetryAvailable) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            ) {
                                Text(
                                    text = "Telemetry hasn't been reported yet. Showing an estimated score based on the captured fields.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(BrandSpacing.Small)
                                )
                            }

                            Spacer(modifier = Modifier.height(BrandSpacing.Small))
                        }

                        Text(
                            text = insights.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(BrandSpacing.Medium))

                        insights.metrics.forEach { metric ->
                            val metricColor = if (metric.isAchieved) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (metric.isAchieved) {
                                        metricColor.copy(alpha = 0.15f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
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
        }
    }
}

private data class QualityInsights(
    val score: Int,
    val metrics: List<QualityMetric>,
    val status: QualityStatus,
    val scoreLabel: String,
    val secondaryLabel: String?,
    val statusMessage: String,
    val telemetryAvailable: Boolean
)

private data class QualityMetric(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val earnedPoints: Int,
    val maxPoints: Int,
    val achieved: Boolean,
    val confidence: Float?
) {
    val isAchieved: Boolean get() = achieved
}

private enum class QualityStatus(val label: String, val message: String, val icon: ImageVector) {
    EXCELLENT("Excellent", "All critical fields look reliable.", Icons.Default.Verified),
    GOOD("Good", "Most key details look solid.", Icons.Default.ThumbUp),
    REVIEW("Needs review", "Some important fields are missing or generic.", Icons.Default.Warning),
    POOR("Incomplete", "Critical details are missing. Consider rescanning or editing.", Icons.Default.Error)
}

private fun deriveQualityInsights(coupon: com.example.coupontracker.data.model.Coupon): QualityInsights {
    val confidences = coupon.extractionConfidenceBreakdown
    val storeValid = !GenericFieldHeuristics.isGenericOrMissing(coupon.storeName)
    val codeValid = !GenericFieldHeuristics.isGenericOrMissingCode(coupon.redeemCode)
    val cashbackDetail = DescriptionUtils.extractCashbackLine(coupon.description)
    val amountValid = GenericFieldHeuristics.hasMeaningfulCashback(cashbackDetail)
    val expiryDate = coupon.expiryDate
    val descriptionValid = GenericFieldHeuristics.isMeaningfulDescription(coupon.description)

    fun formatConfidence(confidence: Float?): Float? = confidence?.coerceIn(0f, 1f)

    fun confidencePercent(confidence: Float?): Int? = confidence?.let { (it.coerceIn(0f, 1f) * 100).roundToInt() }

    fun buildMetric(
        label: String,
        confidence: Float?,
        fallbackAchieved: Boolean,
        successDescription: String,
        failureDescription: String,
        icon: ImageVector,
        maxPoints: Int
    ): QualityMetric {
        val normalized = formatConfidence(confidence)
        return if (normalized != null) {
            val points = (normalized * maxPoints).roundToInt()
            val achieved = normalized >= 0.6f
            val description = if (achieved) {
                val percent = confidencePercent(normalized)
                if (percent != null) "$successDescription • $percent% confidence" else successDescription
            } else {
                val percent = confidencePercent(normalized) ?: 0
                "Weak signal ($percent%) – $failureDescription"
            }
            QualityMetric(
                label = label,
                description = description,
                icon = icon,
                earnedPoints = points,
                maxPoints = maxPoints,
                achieved = achieved,
                confidence = normalized
            )
        } else {
            QualityMetric(
                label = label,
                description = if (fallbackAchieved) successDescription else failureDescription,
                icon = icon,
                earnedPoints = if (fallbackAchieved) maxPoints else 0,
                maxPoints = maxPoints,
                achieved = fallbackAchieved,
                confidence = null
            )
        }
    }

    val expirySuccess = when {
        expiryDate == null -> "Expiry date missing"
        expiryDate.before(Date()) -> "Expiry date captured (already past)"
        else -> "Expiry date captured"
    }
    val expiryFailure = "Add an expiry date to get reminders"

    val metrics = listOf(
        buildMetric(
            label = "Store recognition",
            confidence = confidences["storeName"],
            fallbackAchieved = storeValid,
            successDescription = "Recognized a specific store name",
            failureDescription = "Store name looks generic",
            icon = Icons.Default.Store,
            maxPoints = 25
        ),
        buildMetric(
            label = "Code readiness",
            confidence = confidences["redeemCode"],
            fallbackAchieved = codeValid,
            successDescription = "Coupon code looks redeemable",
            failureDescription = "Code missing or placeholder",
            icon = Icons.Default.Key,
            maxPoints = 30
        ),
        buildMetric(
            label = "Savings detected",
            confidence = confidences["amount"],
            fallbackAchieved = amountValid,
            successDescription = "Cashback or discount captured",
            failureDescription = "Savings not detected",
            icon = Icons.Default.CurrencyRupee,
            maxPoints = 20
        ),
        buildMetric(
            label = "Expiry tracking",
            confidence = confidences["expiryDate"],
            fallbackAchieved = expiryDate != null,
            successDescription = expirySuccess,
            failureDescription = expiryFailure,
            icon = Icons.Default.Event,
            maxPoints = 15
        ),
        buildMetric(
            label = "Offer clarity",
            confidence = confidences["description"],
            fallbackAchieved = descriptionValid,
            successDescription = "Offer description looks specific",
            failureDescription = "Description looks incomplete",
            icon = Icons.Default.Article,
            maxPoints = 10
        )
    )

    val rawScore = coupon.extractionQualityScore ?: metrics.sumOf { it.earnedPoints }
    val score = rawScore.coerceIn(0, 100)
    val status = when {
        score >= 85 -> QualityStatus.EXCELLENT
        score >= 70 -> QualityStatus.GOOD
        score >= 50 -> QualityStatus.REVIEW
        else -> QualityStatus.POOR
    }

    val weakestMetric = metrics
        .filterNot { it.achieved }
        .minByOrNull { metric -> metric.confidence ?: (metric.earnedPoints.toFloat() / metric.maxPoints).coerceIn(0f, 1f) }

    val statusMessage = weakestMetric?.let { metric ->
        val percent = metric.confidence?.let { (it * 100).roundToInt() }
        if (percent != null) {
            "${metric.label} needs attention ($percent% confidence)"
        } else {
            "${metric.label} needs attention"
        }
    } ?: status.message

    val stageLabel = coupon.extractionStage
        ?.takeIf { it.isNotBlank() }
        ?.lowercase(Locale.getDefault())
        ?.replace('_', ' ')
        ?.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }

    val telemetryAvailable = coupon.extractionQualityScore != null || confidences.isNotEmpty()

    val secondaryLabel = if (telemetryAvailable) {
        listOfNotNull(
            stageLabel?.let { "Stage: $it" },
            coupon.extractionRunPath?.takeIf { it.isNotBlank() }?.let { "Path: $it" },
            coupon.extractionTimestamp?.let { "Captured ${DateFormatter.formatShort(it)}" }
        ).joinToString(" • ").ifBlank { null }
    } else {
        null
    }

    val scoreLabel = if (telemetryAvailable) {
        "$score / 100 confidence"
    } else {
        "$score / 100 confidence (heuristic)"
    }

    return QualityInsights(
        score = score,
        metrics = metrics,
        status = status,
        scoreLabel = scoreLabel,
        secondaryLabel = secondaryLabel,
        statusMessage = statusMessage,
        telemetryAvailable = telemetryAvailable
    )
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

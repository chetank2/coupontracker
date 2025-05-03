package com.example.coupontracker.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.DetailViewModel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouponDetailScreen(
    navController: NavController,
    couponId: Long,
    viewModel: DetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coupon by viewModel.coupon.collectAsState()
    
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
                    IconButton(onClick = {
                        // Delete coupon
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(BrandSpacing.Medium)
                ) {
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
                                    clipboardManager.setText(AnnotatedString(coupon.redeemCode))
                                    Toast.makeText(context, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy code")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(BrandSpacing.Large))
                    }
                    
                    // Expiry date
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                    val now = Date()
                    val diffInMillis = coupon.expiryDate.time - now.time
                    val daysRemaining = TimeUnit.MILLISECONDS.toDays(diffInMillis)
                    
                    val expiryColor = when {
                        daysRemaining < 0 -> Color(0xFFE53935) // Expired
                        daysRemaining <= 3 -> Color(0xFFE53935) // Critical
                        daysRemaining <= 7 -> Color(0xFFFFA000) // Warning
                        else -> Color(0xFF43A047) // Valid
                    }
                    
                    val expiryText = when {
                        daysRemaining < 0 -> "Expired"
                        daysRemaining <= 1 -> "Expires tomorrow"
                        else -> "Expires in $daysRemaining days"
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
                            text = dateFormat.format(coupon.expiryDate),
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
                    
                    // Cashback amount
                    if (coupon.cashbackAmount > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CurrencyRupee,
                                contentDescription = null
                            )
                            
                            Spacer(modifier = Modifier.width(BrandSpacing.Small))
                            
                            Text(
                                text = "₹${coupon.cashbackAmount.toInt()}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(BrandSpacing.Medium))
                    }
                    
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
                                // Track usage
                                viewModel.trackUsage(coupon.cashbackAmount)
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
            } ?: run {
                // Loading or error state
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

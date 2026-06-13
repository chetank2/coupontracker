package com.example.coupontracker.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.example.coupontracker.ui.components.BrandTopBar
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.viewmodel.AnalyticsViewModel
import com.example.coupontracker.util.AnalyticsTracker
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screen for displaying analytics data
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    navController: NavController,
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var eventCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var processingTimes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var recentEvents by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    
    // Load analytics data
    LaunchedEffect(Unit) {
        isLoading = true
        eventCounts = viewModel.getEventCounts()
        processingTimes = viewModel.getAverageProcessingTimes()
        recentEvents = viewModel.getRecentEvents(10)
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            BrandTopBar(
                title = "Analytics",
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                viewModel.clearAnalyticsData()
                                eventCounts = emptyMap()
                                processingTimes = emptyMap()
                                recentEvents = emptyList()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Analytics")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = BrandSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(BrandSpacing.Medium)
            ) {
                item {
                    Text(
                        text = "Usage Statistics",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = BrandSpacing.Medium)
                    )
                    
                    Spacer(modifier = Modifier.height(BrandSpacing.Small))
                    
                    // Event counts
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(BrandSpacing.Medium)
                        ) {
                            Text(
                                text = "Event Counts",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Spacer(modifier = Modifier.height(BrandSpacing.Small))
                            
                            if (eventCounts.isEmpty()) {
                                Text("No events recorded yet")
                            } else {
                                eventCounts.forEach { (event, count) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(formatEventName(event))
                                        Text(
                                            text = count.toString(),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(BrandSpacing.Medium))
                    
                    // Processing times
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(BrandSpacing.Medium)
                        ) {
                            Text(
                                text = "Average Processing Times",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            Spacer(modifier = Modifier.height(BrandSpacing.Small))
                            
                            if (processingTimes.isEmpty()) {
                                Text("No processing time data available")
                            } else {
                                processingTimes.forEach { (operation, time) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(formatOperationName(operation))
                                        Text(
                                            text = "${time}ms",
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(BrandSpacing.Medium))
                    
                    Text(
                        text = "Recent Events",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                if (recentEvents.isEmpty()) {
                    item {
                        Text("No recent events")
                    }
                } else {
                    items(recentEvents) { event ->
                        EventCard(event = event)
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(BrandSpacing.Large))
                    
                    Button(
                        onClick = {
                            scope.launch {
                                viewModel.clearAnalyticsData()
                                eventCounts = emptyMap()
                                processingTimes = emptyMap()
                                recentEvents = emptyList()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear All Analytics Data")
                    }
                    
                    Spacer(modifier = Modifier.height(BrandSpacing.ExtraLarge))
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: JSONObject) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(BrandSpacing.Medium)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatEventName(event.getString("event_type")),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Text(
                    text = event.getString("formatted_time"),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Divider()
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Display properties
            val properties = event.getJSONObject("properties")
            val keys = properties.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatPropertyName(key),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = properties.getString(key),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Format event name for display
 */
private fun formatEventName(eventName: String): String {
    return when (eventName) {
        AnalyticsTracker.EVENT_CAPTURE_STARTED -> "Capture Started"
        AnalyticsTracker.EVENT_CAPTURE_COMPLETED -> "Capture Completed"
        AnalyticsTracker.EVENT_CAPTURE_FAILED -> "Capture Failed"
        AnalyticsTracker.EVENT_MODE_SELECTED -> "Mode Selected"
        AnalyticsTracker.EVENT_COUPON_DETECTED -> "Coupon Detected"
        AnalyticsTracker.EVENT_MULTIPLE_COUPONS_DETECTED -> "Multiple Coupons Detected"
        AnalyticsTracker.EVENT_QR_CODE_DETECTED -> "QR Code Detected"
        AnalyticsTracker.EVENT_PROCESSING_TIME -> "Processing Time"
        else -> eventName.replace("_", " ").capitalize()
    }
}

/**
 * Format operation name for display
 */
private fun formatOperationName(operation: String): String {
    return when (operation) {
        "qr_code_detection" -> "QR Code Detection"
        "single_coupon_detection" -> "Single Coupon Detection"
        "multiple_coupon_detection" -> "Multiple Coupon Detection"
        "image_processing" -> "Image Processing"
        else -> operation.replace("_", " ").capitalize()
    }
}

/**
 * Format property name for display
 */
private fun formatPropertyName(propertyName: String): String {
    return propertyName.replace("_", " ").capitalize()
}

/**
 * Extension function to capitalize first letter of a string
 */
private fun String.capitalize(): String {
    return this.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

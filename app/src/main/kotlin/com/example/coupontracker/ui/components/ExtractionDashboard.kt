package com.example.coupontracker.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupontracker.util.*
import kotlin.math.max

/**
 * Comprehensive dashboard for extraction performance monitoring
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionDashboard(
    systemPerformance: SystemPerformance,
    onRefresh: () -> Unit,
    onCleanupOldStats: () -> Unit,
    onOptimizeLearning: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedMethod by remember { mutableStateOf<ExtractionMethod?>(null) }
    var showOptimizationControls by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        // Header with refresh button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Extraction Performance",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    
                    IconButton(onClick = { showOptimizationControls = !showOptimizationControls }) {
                        Icon(Icons.Default.Settings, contentDescription = "Optimization")
                    }
                }
            }
        }

        // Optimization controls (collapsible)
        if (showOptimizationControls) {
            item {
                OptimizationControls(
                    onCleanupOldStats = onCleanupOldStats,
                    onOptimizeLearning = onOptimizeLearning
                )
            }
        }

        // System overview cards
        item {
            SystemOverviewCards(systemPerformance)
        }

        // Learning progress
        item {
            LearningProgressCard(systemPerformance.learningProgress)
        }

        // Performance trend chart
        item {
            PerformanceTrendChart(systemPerformance.recentTrends)
        }

        // Method breakdown
        item {
            Text(
                text = "Extraction Methods",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        items(systemPerformance.methodBreakdown.entries.toList()) { (method, performance) ->
            MethodPerformanceCard(
                method = method,
                performance = performance,
                isSelected = selectedMethod == method,
                onClick = { 
                    selectedMethod = if (selectedMethod == method) null else method
                }
            )
        }

        // Selected method details
        selectedMethod?.let { method ->
            item {
                MethodDetailCard(
                    method = method,
                    performance = systemPerformance.methodBreakdown[method]!!
                )
            }
        }
    }
}

@Composable
private fun SystemOverviewCards(systemPerformance: SystemPerformance) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Total extractions
        MetricCard(
            title = "Total Extractions",
            value = systemPerformance.totalExtractions.toString(),
            icon = Icons.Default.Analytics,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )

        // Success rate
        MetricCard(
            title = "Success Rate",
            value = "${(systemPerformance.overallSuccessRate * 100).toInt()}%",
            icon = Icons.Default.CheckCircle,
            color = if (systemPerformance.overallSuccessRate > 0.8f) 
                Color(0xFF4CAF50) else Color(0xFFFF9800),
            modifier = Modifier.weight(1f)
        )

        // Top method
        MetricCard(
            title = "Best Method",
            value = systemPerformance.topPerformingMethod?.name?.replace("_", " ") ?: "N/A",
            icon = Icons.Default.Star,
            color = Color(0xFFFFD700),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LearningProgressCard(learningProgress: LearningProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Learning Progress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LearningMetric(
                    label = "Total Feedback",
                    value = learningProgress.totalFeedback.toString(),
                    icon = Icons.Default.Feedback
                )

                LearningMetric(
                    label = "Patterns Learned",
                    value = learningProgress.patternsLearned.toString(),
                    icon = Icons.Default.Pattern
                )

                LearningMetric(
                    label = "Improvement",
                    value = "${(learningProgress.improvementRate * 100).toInt()}%",
                    icon = Icons.Default.TrendingUp,
                    color = if (learningProgress.improvementRate > 0) 
                        Color(0xFF4CAF50) else Color(0xFFFF5722)
                )
            }
        }
    }
}

@Composable
private fun PerformanceTrendChart(trends: List<TrendPoint>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Performance Trend (Last 7 Days)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (trends.isNotEmpty()) {
                SimpleLineChart(
                    data = trends.map { it.value },
                    labels = trends.map { it.date.takeLast(5) }, // Show MM-DD
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No trend data available yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MethodPerformanceCard(
    method: ExtractionMethod,
    performance: MethodPerformance,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = method.name.replace("_", " "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${performance.totalAttempts} attempts",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${(performance.successRate * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (performance.successRate > 0.8f) 
                            Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Icon(
                        if (isSelected) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                }
            }

            // Quick stats row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                QuickStat("Confidence", "${(performance.averageConfidence * 100).toInt()}%")
                QuickStat("Avg Time", "${performance.averageProcessingTime}ms")
            }
        }
    }
}

@Composable
private fun MethodDetailCard(
    method: ExtractionMethod,
    performance: MethodPerformance
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "${method.name.replace("_", " ")} - Detailed Stats",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Field accuracy breakdown
            if (performance.fieldAccuracy.isNotEmpty()) {
                Text(
                    text = "Field Accuracy",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                performance.fieldAccuracy.entries.forEach { (field, accuracy) ->
                    FieldAccuracyBar(
                        fieldName = field,
                        accuracy = accuracy
                    )
                }
            }

            // Learning trend mini chart
            if (performance.learningTrend.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Learning Trend",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                SimpleLineChart(
                    data = performance.learningTrend.map { it.value },
                    labels = performance.learningTrend.map { it.date.takeLast(5) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                )
            }
        }
    }
}

@Composable
private fun OptimizationControls(
    onCleanupOldStats: () -> Unit,
    onOptimizeLearning: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "System Optimization",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCleanupOldStats,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.CleaningServices, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cleanup Stats")
                }

                Button(
                    onClick = onOptimizeLearning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Optimize Learning")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "• Cleanup removes statistics older than 30 days\n• Optimize Learning recalibrates pattern weights",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Helper composables

@Composable
private fun MetricCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LearningMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickStat(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FieldAccuracyBar(
    fieldName: String,
    accuracy: Float
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = fieldName.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${(accuracy * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        LinearProgressIndicator(
            progress = accuracy,
            modifier = Modifier.fillMaxWidth(),
            color = if (accuracy > 0.8f) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SimpleLineChart(
    data: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    
    Canvas(modifier = modifier) {
        if (data.isEmpty()) return@Canvas
        
        val canvasWidth = size.width
        val canvasHeight = size.height
        val padding = 40.dp.toPx()
        
        val chartWidth = canvasWidth - padding * 2
        val chartHeight = canvasHeight - padding * 2
        
        val maxValue = data.maxOrNull() ?: 1f
        val minValue = data.minOrNull() ?: 0f
        val valueRange = max(maxValue - minValue, 0.1f)
        
        // Draw data points and lines
        val points = data.mapIndexed { index, value ->
            val x = padding + (index.toFloat() / (data.size - 1).coerceAtLeast(1)) * chartWidth
            val y = padding + chartHeight - ((value - minValue) / valueRange) * chartHeight
            Offset(x, y)
        }
        
        // Draw lines between points
        for (i in 0 until points.size - 1) {
            drawLine(
                color = primaryColor,
                start = points[i],
                end = points[i + 1],
                strokeWidth = 3.dp.toPx()
            )
        }
        
        // Draw data points
        points.forEach { point ->
            drawCircle(
                color = primaryColor,
                radius = 4.dp.toPx(),
                center = point
            )
        }
    }
}

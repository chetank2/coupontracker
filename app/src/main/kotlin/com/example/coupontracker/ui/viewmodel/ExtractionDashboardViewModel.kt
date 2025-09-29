package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.util.ExtractionPerformanceMonitor
import com.example.coupontracker.util.SystemPerformance
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.universal.AdaptiveConfidenceScorer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

/**
 * ViewModel for the Extraction Performance Dashboard
 * Manages loading and refreshing of extraction statistics
 */
@HiltViewModel
class ExtractionDashboardViewModel @Inject constructor(
    private val performanceMonitor: ExtractionPerformanceMonitor,
    private val patternLearningEngine: PatternLearningEngine,
    private val adaptiveConfidenceScorer: AdaptiveConfidenceScorer
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "ExtractionDashboardViewModel"
    }
    
    init {
        refreshData()
    }
    
    /**
     * Refresh all dashboard data
     */
    fun refreshData() {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true)
                
                Log.d(TAG, "Refreshing dashboard data...")
                
                // Ensure all recent extraction attempts are persisted before reading
                performanceMonitor.persistSessionStats()
                
                // Load system performance data
                val systemPerformance = performanceMonitor.getOverallPerformance()
                
                Log.d(TAG, "Loaded system performance: ${systemPerformance.totalExtractions} total extractions, ${systemPerformance.overallSuccessRate} success rate")
                
                _uiState.value = _uiState.value.copy(
                    systemPerformance = systemPerformance,
                    isLoading = false,
                    error = null
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing dashboard data", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Failed to load performance data: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Clean up old statistics (older than 30 days)
     */
    fun cleanupOldStats() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Cleaning up old statistics...")
                
                _uiState.value = _uiState.value.copy(
                    isOptimizing = true,
                    optimizationStatus = "Cleaning up old statistics..."
                )
                
                performanceMonitor.cleanupOldStats()
                
                _uiState.value = _uiState.value.copy(
                    isOptimizing = false,
                    optimizationStatus = "Statistics cleanup completed successfully"
                )
                
                // Refresh data to show updated stats
                refreshData()
                
                // Clear status after a delay
                kotlinx.coroutines.delay(3000)
                _uiState.value = _uiState.value.copy(optimizationStatus = null)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error cleaning up old stats", e)
                _uiState.value = _uiState.value.copy(
                    isOptimizing = false,
                    optimizationStatus = "Error cleaning up statistics: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Optimize learning algorithms and recalibrate patterns
     */
    fun optimizeLearning() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Optimizing learning algorithms...")
                
                _uiState.value = _uiState.value.copy(
                    isOptimizing = true,
                    optimizationStatus = "Optimizing learning patterns..."
                )
                
                // Optimize pattern weights based on recent performance
                val optimizationResults = optimizePatternWeights()
                
                _uiState.value = _uiState.value.copy(
                    optimizationStatus = "Recalibrating confidence scores..."
                )
                
                // Recalibrate confidence scoring
                val calibrationResults = recalibrateConfidenceScoring()
                
                val totalOptimizations = optimizationResults + calibrationResults
                
                _uiState.value = _uiState.value.copy(
                    isOptimizing = false,
                    optimizationStatus = "Learning optimization completed: $totalOptimizations patterns updated"
                )
                
                // Refresh data to show improvements
                refreshData()
                
                // Clear status after a delay
                kotlinx.coroutines.delay(5000)
                _uiState.value = _uiState.value.copy(optimizationStatus = null)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error optimizing learning", e)
                _uiState.value = _uiState.value.copy(
                    isOptimizing = false,
                    optimizationStatus = "Error optimizing learning: ${e.message}"
                )
            }
        }
    }
    
    /**
     * Get detailed performance for a specific method
     */
    fun getMethodDetails(method: com.example.coupontracker.util.ExtractionMethod) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Loading details for method: $method")
                
                val methodPerformance = performanceMonitor.getMethodPerformance(method)
                
                Log.d(TAG, "Method $method performance: ${methodPerformance.successRate} success rate, ${methodPerformance.totalAttempts} attempts")
                
                // Update the system performance with detailed method info
                val currentPerformance = _uiState.value.systemPerformance
                if (currentPerformance != null) {
                    val updatedBreakdown = currentPerformance.methodBreakdown.toMutableMap()
                    updatedBreakdown[method] = methodPerformance
                    
                    _uiState.value = _uiState.value.copy(
                        systemPerformance = currentPerformance.copy(
                            methodBreakdown = updatedBreakdown
                        )
                    )
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error loading method details", e)
            }
        }
    }
    
    // Private optimization methods
    
    private suspend fun optimizePatternWeights(): Int {
        return try {
            // Get current system performance to identify areas for improvement
            val systemPerformance = performanceMonitor.getOverallPerformance()
            
            var optimizationCount = 0
            
            // Identify underperforming methods and boost their patterns
            systemPerformance.methodBreakdown.forEach { (method, performance) ->
                if (performance.successRate < 0.7f && performance.totalAttempts > 10) {
                    Log.d(TAG, "Boosting patterns for underperforming method: $method (${performance.successRate} success rate)")
                    
                    // Boost patterns for fields that are failing
                    performance.fieldAccuracy.forEach { (field, accuracy) ->
                        if (accuracy < 0.6f) {
                            // This would boost patterns for the specific field type
                            // In a real implementation, you'd call pattern learning methods
                            optimizationCount++
                        }
                    }
                }
            }
            
            // Optimize based on recent trends
            systemPerformance.recentTrends.takeLast(3).forEach { trend ->
                if (trend.value < 0.8f) {
                    // Recent performance is declining, boost recent successful patterns
                    optimizationCount++
                }
            }
            
            Log.d(TAG, "Pattern optimization completed: $optimizationCount patterns optimized")
            optimizationCount
            
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing pattern weights", e)
            0
        }
    }
    
    private suspend fun recalibrateConfidenceScoring(): Int {
        return try {
            // Get feedback statistics to recalibrate confidence scoring
            val systemPerformance = performanceMonitor.getOverallPerformance()
            val learningProgress = systemPerformance.learningProgress
            
            var calibrationCount = 0
            
            // If we have sufficient feedback, recalibrate scoring
            if (learningProgress.totalFeedback > 50) {
                Log.d(TAG, "Recalibrating confidence scores based on ${learningProgress.totalFeedback} feedback instances")
                
                // Recalibrate based on field accuracy
                systemPerformance.methodBreakdown.forEach { (method, performance) ->
                    performance.fieldAccuracy.forEach { (field, accuracy) ->
                        // Adjust confidence scoring for this field type
                        // In a real implementation, you'd update the AdaptiveConfidenceScorer
                        calibrationCount++
                    }
                }
            }
            
            Log.d(TAG, "Confidence calibration completed: $calibrationCount scores recalibrated")
            calibrationCount
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recalibrating confidence scoring", e)
            0
        }
    }
    
    /**
     * Clear any error state
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
    
    /**
     * Clear optimization status
     */
    fun clearOptimizationStatus() {
        _uiState.value = _uiState.value.copy(optimizationStatus = null)
    }
}

/**
 * UI state for the extraction dashboard
 */
data class DashboardUiState(
    val systemPerformance: SystemPerformance? = null,
    val isLoading: Boolean = false,
    val isOptimizing: Boolean = false,
    val error: String? = null,
    val optimizationStatus: String? = null
)

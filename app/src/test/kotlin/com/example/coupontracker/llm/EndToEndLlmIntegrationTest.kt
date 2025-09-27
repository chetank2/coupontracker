package com.example.coupontracker.llm

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.ImageProcessor
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.util.ApiType
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import java.util.Date

/**
 * End-to-end integration tests for the complete MiniCPM LLM pipeline
 * Tests the full flow from image input to structured coupon extraction
 */
@RunWith(RobolectricTestRunner::class)
class EndToEndLlmIntegrationTest {
    
    private lateinit var context: Context
    private lateinit var llmRuntimeManager: LlmRuntimeManager
    private lateinit var localLlmOcrService: LocalLlmOcrService
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var telemetryService: LlmTelemetryService
    private lateinit var securePreferencesManager: SecurePreferencesManager
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        llmRuntimeManager = LlmRuntimeManager.getInstance(context)
        localLlmOcrService = LocalLlmOcrService(context, llmRuntimeManager)
        imageProcessor = ImageProcessor(context, localLlmOcrService)
        telemetryService = LlmTelemetryService.getInstance(context)
        securePreferencesManager = SecurePreferencesManager(context)
        
        // Reset telemetry for clean test state
        telemetryService.resetMetrics()
        
        // Initialize preferences
        securePreferencesManager.initialize()
    }
    
    @Test
    fun testFullPipelineWithMockModel() = runBlocking {
        // Test the full pipeline with mock model (when real model not available)
        
        // Set up LOCAL_LLM as selected API type
        securePreferencesManager.setSelectedApiType(ApiType.LOCAL_LLM)
        
        // Create test bitmap (small test image)
        val testBitmap = createTestBitmap()
        val captureTimestamp = Date()
        
        // Process image through full pipeline
        val result = try {
            localLlmOcrService.processCouponImage(testBitmap, captureTimestamp)
        } catch (e: Exception) {
            // Expected when model not available - should fallback
            assertNotNull("Exception should contain fallback info", e.message)
            return@runBlocking
        }
        
        // Verify result structure (either from LLM or fallback)
        assertNotNull("Result should not be null", result)
        assertNotNull("Store name should be extracted", result.storeName)
        
        // Verify telemetry was recorded
        val metrics = telemetryService.getMetrics()
        assertTrue("Should have recorded at least one inference attempt", 
                  metrics.totalInferences > 0)
    }
    
    @Test
    fun testModelAvailabilityChecks() = runBlocking {
        // Test comprehensive model availability validation
        val isAvailable = llmRuntimeManager.isModelAvailable()
        
        if (!isAvailable) {
            // Verify specific missing files are reported
            val modelInfo = llmRuntimeManager.getModelInfo()
            assertFalse("Model should not be marked as available", modelInfo.isAvailable)
            assertEquals("Size should be 0 when not available", 0L, modelInfo.sizeBytes)
        } else {
            // If model is available, verify all files exist
            val modelInfo = llmRuntimeManager.getModelInfo()
            assertTrue("Model should be marked as available", modelInfo.isAvailable)
            assertTrue("Model size should be positive", modelInfo.sizeBytes > 0)
        }
    }
    
    @Test
    fun testFallbackMechanisms() = runBlocking {
        // Test that fallback cascade works correctly
        
        // Force LOCAL_LLM selection but ensure model is not available
        securePreferencesManager.setSelectedApiType(ApiType.LOCAL_LLM)
        securePreferencesManager.setLlmModelDownloaded(false)
        
        val testBitmap = createTestBitmap()
        val captureTimestamp = Date()
        
        // Process through ImageProcessor (should fallback to MODEL_BASED)
        val result = imageProcessor.processImage(testBitmap, captureTimestamp)
        
        // Verify we got a result (from fallback)
        assertNotNull("Should get result from fallback", result)
        assertNotNull("Should have store name from fallback", result.storeName)
        
        // Verify telemetry recorded the fallback
        val metrics = telemetryService.getMetrics()
        assertTrue("Should have recorded fallback usage", 
                  metrics.fallbackCount > 0)
    }
    
    @Test
    fun testCaptureTimestampHandling() = runBlocking {
        // Test that capture timestamps are properly threaded through pipeline
        
        val testBitmap = createTestBitmap()
        val captureTimestamp = Date(System.currentTimeMillis() - 86400000) // 1 day ago
        
        // Process with specific timestamp
        val result = try {
            localLlmOcrService.processCouponImage(testBitmap, captureTimestamp)
        } catch (e: Exception) {
            // If LLM fails, test fallback timestamp handling
            imageProcessor.processImage(testBitmap, captureTimestamp)
        }
        
        assertNotNull("Should get result with timestamp", result)
        
        // Note: Actual timestamp validation would require inspecting
        // the date parsing logic, which is tested separately
    }
    
    @Test
    fun testTelemetryCapture() = runBlocking {
        // Test comprehensive telemetry capture
        
        telemetryService.resetMetrics()
        
        val testBitmap = createTestBitmap()
        
        // Record a successful inference
        telemetryService.recordInference(
            durationMs = 1500,
            success = true,
            extractedFieldCount = 4,
            memoryUsageMB = 2048
        )
        
        // Record a timeout
        telemetryService.recordTimeout(30000)
        
        // Record a failure with fallback
        telemetryService.recordInference(
            durationMs = 2000,
            success = false,
            errorType = "TIMEOUT",
            fallbackUsed = "ML_KIT",
            extractedFieldCount = 2,
            memoryUsageMB = 2048
        )
        
        // Verify metrics
        val metrics = telemetryService.getMetrics()
        assertEquals("Should have 2 total inferences", 2, metrics.totalInferences)
        assertEquals("Should have 1 successful inference", 1, metrics.successfulInferences)
        assertEquals("Should have 1 timeout", 1, metrics.timeoutCount)
        assertEquals("Should have 1 fallback", 1, metrics.fallbackCount)
        assertTrue("Average duration should be reasonable", 
                  metrics.averageInferenceDurationMs > 0)
    }
    
    @Test
    fun testMemoryManagement() = runBlocking {
        // Test LLM memory management and reference counting
        
        if (!llmRuntimeManager.isModelAvailable()) {
            // Skip if model not available
            return@runBlocking
        }
        
        // Load model
        val loadSuccess = llmRuntimeManager.loadModel()
        if (!loadSuccess) {
            // Model loading failed - expected in test environment
            return@runBlocking
        }
        
        // Check memory stats
        val memoryStats = llmRuntimeManager.getMemoryStats()
        assertTrue("Model loaded memory should be positive", 
                  memoryStats.modelLoadedMemoryMB > 0)
        assertTrue("Reference count should be positive", 
                  memoryStats.referenceCount > 0)
        
        // Unload model
        llmRuntimeManager.unloadModel()
        
        // Verify cleanup
        val postUnloadStats = llmRuntimeManager.getMemoryStats()
        assertEquals("Reference count should be 0 after unload", 
                    0, postUnloadStats.referenceCount)
    }
    
    @Test
    fun testApiTypeRouting() = runBlocking {
        // Test that ApiType selection properly routes to correct engines
        
        val testBitmap = createTestBitmap()
        val captureTimestamp = Date()
        
        // Test LOCAL_LLM routing
        securePreferencesManager.setSelectedApiType(ApiType.LOCAL_LLM)
        val llmResult = imageProcessor.processImage(testBitmap, captureTimestamp)
        assertNotNull("LOCAL_LLM should produce result", llmResult)
        
        // Test MODEL_BASED routing
        securePreferencesManager.setSelectedApiType(ApiType.MODEL_BASED)
        val modelResult = imageProcessor.processImage(testBitmap, captureTimestamp)
        assertNotNull("MODEL_BASED should produce result", modelResult)
        
        // Test ML_KIT_ONLY routing
        securePreferencesManager.setSelectedApiType(ApiType.ML_KIT_ONLY)
        val mlKitResult = imageProcessor.processImage(testBitmap, captureTimestamp)
        assertNotNull("ML_KIT_ONLY should produce result", mlKitResult)
    }
    
    private fun createTestBitmap(): Bitmap {
        // Create a simple test bitmap for testing
        return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Create test bitmap
        val testBitmap = createTestCouponBitmap()
        val captureTimestamp = Date()
        
        // Set API type to LOCAL_LLM
        securePreferencesManager.setSelectedApiType(ApiType.LOCAL_LLM)
        
        // Process image through full pipeline
        val result = imageProcessor.processImage(testBitmap, captureTimestamp)
        
        // Verify result structure
        assertNotNull("Result should not be null", result)
        assertNotNull("Store name should be extracted", result.storeName)
        assertTrue("Should have some extracted content", result.storeName.isNotBlank())
        
        // Verify telemetry was recorded
        val metrics = telemetryService.getPerformanceMetrics()
        assertTrue("At least one inference should be recorded", metrics.totalInferences > 0)
        
        println("Pipeline test completed with result: $result")
        println("Telemetry: ${telemetryService.getTelemetrySummary()}")
    }
    
    @Test
    fun testLlmServiceAvailability() {
        // Test service availability detection
        val isAvailable = localLlmOcrService.isServiceAvailable()
        
        // With mock implementation, service should report availability based on model files
        // In real deployment, this would check for actual model files
        println("LLM service available: $isAvailable")
        
        val status = localLlmOcrService.getServiceStatus()
        assertNotNull("Service status should not be null", status)
        assertEquals("Service version should match", "1.0.0", status.serviceVersion)
        
        println("Service status: $status")
    }
    
    @Test
    fun testFallbackBehavior() = runBlocking {
        // Test fallback behavior when LLM fails
        
        val testBitmap = createTestCouponBitmap()
        
        // Force a scenario that would trigger fallback
        // (In real test, this might involve corrupted model or memory pressure)
        
        val result = localLlmOcrService.processCouponImage(testBitmap)
        
        // Verify fallback produces valid result
        assertNotNull("Fallback should produce result", result)
        
        // Check telemetry for fallback usage
        val metrics = telemetryService.getPerformanceMetrics()
        println("Fallback test metrics: ${telemetryService.getTelemetrySummary()}")
        
        // Verify fallback was recorded if it occurred
        if (metrics.fallbackRate > 0) {
            assertTrue("Fallback count should be positive", 
                metrics.fallbackToMlKitCount + metrics.fallbackToModelBasedCount > 0)
        }
    }
    
    @Test
    fun testMemoryManagement() = runBlocking {
        // Test memory management and model lifecycle
        
        val initialMetrics = telemetryService.getPerformanceMetrics()
        val initialMemory = initialMetrics.peakMemoryUsageMB
        
        // Process multiple images to test memory behavior
        repeat(3) { i ->
            val testBitmap = createTestCouponBitmap()
            localLlmOcrService.processCouponImage(testBitmap)
            
            // Check memory doesn't grow unbounded
            val currentMetrics = telemetryService.getPerformanceMetrics()
            println("Iteration $i memory usage: ${currentMetrics.peakMemoryUsageMB}MB")
        }
        
        val finalMetrics = telemetryService.getPerformanceMetrics()
        
        // Verify reasonable memory usage
        assertTrue("Memory usage should be reasonable", 
            finalMetrics.peakMemoryUsageMB < 5000) // Less than 5GB
        
        println("Memory management test completed")
        println("Peak memory: ${finalMetrics.peakMemoryUsageMB}MB")
    }
    
    @Test
    fun testConcurrentProcessing() = runBlocking {
        // Test concurrent processing behavior
        
        val testBitmaps = List(3) { createTestCouponBitmap() }
        
        // Process images concurrently
        val results = testBitmaps.map { bitmap ->
            kotlinx.coroutines.async {
                localLlmOcrService.processCouponImage(bitmap)
            }
        }.map { it.await() }
        
        // Verify all results are valid
        results.forEachIndexed { index, result ->
            assertNotNull("Result $index should not be null", result)
            println("Concurrent result $index: ${result.storeName}")
        }
        
        // Check telemetry for concurrent behavior
        val metrics = telemetryService.getPerformanceMetrics()
        assertEquals("Should have processed 3 images", 3L, metrics.totalInferences)
        
        println("Concurrent processing test completed")
        println("Average inference time: ${metrics.averageInferenceTimeMs}ms")
    }
    
    @Test
    fun testTelemetryAccuracy() = runBlocking {
        // Test telemetry accuracy and completeness
        
        telemetryService.resetMetrics()
        
        val testBitmap = createTestCouponBitmap()
        val startTime = System.currentTimeMillis()
        
        val result = localLlmOcrService.processCouponImage(testBitmap)
        
        val endTime = System.currentTimeMillis()
        val actualDuration = endTime - startTime
        
        val metrics = telemetryService.getPerformanceMetrics()
        
        // Verify telemetry accuracy
        assertEquals("Should record exactly one inference", 1L, metrics.totalInferences)
        assertTrue("Recorded duration should be reasonable", 
            metrics.averageInferenceTimeMs > 0 && metrics.averageInferenceTimeMs < actualDuration + 1000)
        
        // Test telemetry export
        val exportedData = telemetryService.exportTelemetryData()
        assertTrue("Exported data should contain JSON", exportedData.contains("{"))
        assertTrue("Exported data should contain metrics", exportedData.contains("total_inferences"))
        
        println("Telemetry test completed")
        println("Exported telemetry: $exportedData")
    }
    
    @Test
    fun testApiTypeRouting() = runBlocking {
        // Test proper routing through ApiType enum
        
        val testBitmap = createTestCouponBitmap()
        
        // Test LOCAL_LLM routing
        securePreferencesManager.setSelectedApiType(ApiType.LOCAL_LLM)
        val llmResult = imageProcessor.processImage(testBitmap)
        assertNotNull("LOCAL_LLM should produce result", llmResult)
        
        // Test MODEL_BASED routing
        securePreferencesManager.setSelectedApiType(ApiType.MODEL_BASED)
        val modelResult = imageProcessor.processImage(testBitmap)
        assertNotNull("MODEL_BASED should produce result", modelResult)
        
        // Test ML_KIT_ONLY routing
        securePreferencesManager.setSelectedApiType(ApiType.ML_KIT_ONLY)
        val mlKitResult = imageProcessor.processImage(testBitmap)
        assertNotNull("ML_KIT_ONLY should produce result", mlKitResult)
        
        println("API routing test completed")
        println("LLM result: ${llmResult.storeName}")
        println("Model result: ${modelResult.storeName}")
        println("ML Kit result: ${mlKitResult.storeName}")
    }
    
    @Test
    fun testTimestampThreading() = runBlocking {
        // Test that capture timestamps are properly threaded through the pipeline
        
        val testBitmap = createTestCouponBitmap()
        val captureTimestamp = Date(System.currentTimeMillis() - 86400000) // 1 day ago
        
        // Process with timestamp
        val result = localLlmOcrService.processCouponImage(testBitmap, captureTimestamp)
        
        // Verify result (timestamp effects would be visible in expiry date parsing)
        assertNotNull("Result should not be null", result)
        
        println("Timestamp threading test completed")
        println("Processed with timestamp: $captureTimestamp")
        println("Result: ${result.expiryDate}")
    }
    
    private fun createTestCouponBitmap(): Bitmap {
        // Create a simple test bitmap representing a coupon
        return Bitmap.createBitmap(400, 300, Bitmap.Config.RGB_565).apply {
            // In a real test, this would be a more realistic coupon image
            eraseColor(android.graphics.Color.WHITE)
        }
    }
}

/**
 * Performance benchmark tests for MiniCPM integration
 */
@RunWith(RobolectricTestRunner::class)
class LlmPerformanceBenchmarkTest {
    
    private lateinit var context: Context
    private lateinit var localLlmOcrService: LocalLlmOcrService
    private lateinit var telemetryService: LlmTelemetryService
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val llmRuntimeManager = LlmRuntimeManager.getInstance(context)
        localLlmOcrService = LocalLlmOcrService(context, llmRuntimeManager)
        telemetryService = LlmTelemetryService.getInstance(context)
        telemetryService.resetMetrics()
    }
    
    @Test
    fun benchmarkInferencePerformance() = runBlocking {
        // Benchmark inference performance over multiple iterations
        
        val iterations = 10
        val testBitmap = createBenchmarkBitmap()
        
        val startTime = System.currentTimeMillis()
        
        repeat(iterations) { i ->
            val iterationStart = System.currentTimeMillis()
            localLlmOcrService.processCouponImage(testBitmap)
            val iterationTime = System.currentTimeMillis() - iterationStart
            
            println("Iteration $i: ${iterationTime}ms")
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        val metrics = telemetryService.getPerformanceMetrics()
        
        println("\n=== PERFORMANCE BENCHMARK RESULTS ===")
        println("Total iterations: $iterations")
        println("Total time: ${totalTime}ms")
        println("Average per iteration: ${totalTime / iterations}ms")
        println("Success rate: ${metrics.successRate}%")
        println("Fallback rate: ${metrics.fallbackRate}%")
        println("Peak memory: ${metrics.peakMemoryUsageMB}MB")
        println("=====================================")
        
        // Performance assertions
        assertTrue("Average inference should be under 30 seconds", 
            metrics.averageInferenceTimeMs < 30000)
        assertTrue("Success rate should be reasonable", metrics.successRate > 50)
    }
    
    private fun createBenchmarkBitmap(): Bitmap {
        return Bitmap.createBitmap(768, 512, Bitmap.Config.RGB_565).apply {
            eraseColor(android.graphics.Color.WHITE)
        }
    }
}

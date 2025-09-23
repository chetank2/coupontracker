package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Golden-set testing framework for evaluating coupon recognition accuracy
 * Tests the pipeline against a curated set of 50-100 images with known ground truth
 */
class GoldenSetTester(private val context: Context) {
    
    data class TestResult(
        val imageName: String,
        val expected: ExpectedResult,
        val actual: ActualResult,
        val accuracy: Float,
        val processingTimeMs: Long,
        val passed: Boolean
    )
    
    data class ExpectedResult(
        val storeName: String,
        val code: String?,
        val amount: String?,
        val expiryDate: String?,
        val description: String?
    )
    
    data class ActualResult(
        val storeName: String,
        val code: String?,
        val amount: String?,
        val expiryDate: String?,
        val description: String?,
        val confidence: Float
    )
    
    data class TestSummary(
        val totalTests: Int,
        val passedTests: Int,
        val failedTests: Int,
        val averageAccuracy: Float,
        val averageProcessingTimeMs: Long,
        val results: List<TestResult>
    )
    
    private val integratedPipeline = IntegratedCouponPipeline(context)
    private val loggerTag = "GoldenSetTester"
    
    companion object {
        private const val GOLDEN_SET_DIR = "golden_set"
        private const val METADATA_FILE = "metadata.json"
        private const val RESULTS_FILE = "test_results.json"
    }
    
    /**
     * Run the complete golden set test suite
     */
    suspend fun runGoldenSetTests(): TestSummary = withContext(Dispatchers.IO) {
        Log.d(loggerTag, "Starting golden set testing")
        
        val goldenSetDir = File(context.filesDir, GOLDEN_SET_DIR)
        if (!goldenSetDir.exists()) {
            Log.e(loggerTag, "Golden set directory not found: ${goldenSetDir.absolutePath}")
            return@withContext TestSummary(0, 0, 0, 0f, 0L, emptyList())
        }
        
        val metadataFile = File(goldenSetDir, METADATA_FILE)
        if (!metadataFile.exists()) {
            Log.e(loggerTag, "Metadata file not found: ${metadataFile.absolutePath}")
            return@withContext TestSummary(0, 0, 0, 0f, 0L, emptyList())
        }
        
        val metadata = loadMetadata(metadataFile)
        val testResults = mutableListOf<TestResult>()
        
        for (testCase in metadata.testCases) {
            try {
                val result = runSingleTest(goldenSetDir, testCase)
                testResults.add(result)
                Log.d(loggerTag, "Test ${testCase.imageName}: ${if (result.passed) "PASSED" else "FAILED"} (${result.accuracy}%)")
            } catch (e: Exception) {
                Log.e(loggerTag, "Test failed for ${testCase.imageName}", e)
                val failedResult = TestResult(
                    imageName = testCase.imageName,
                    expected = testCase.expected,
                    actual = ActualResult("", null, null, null, null, 0f),
                    accuracy = 0f,
                    processingTimeMs = 0L,
                    passed = false
                )
                testResults.add(failedResult)
            }
        }
        
        val summary = calculateSummary(testResults)
        saveTestResults(goldenSetDir, summary)
        
        Log.d(loggerTag, "Golden set testing completed: ${summary.passedTests}/${summary.totalTests} passed")
        summary
    }
    
    /**
     * Run a single test case
     */
    private suspend fun runSingleTest(goldenSetDir: File, testCase: TestCase): TestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // Load the test image
        val imageFile = File(goldenSetDir, testCase.imageName)
        if (!imageFile.exists()) {
            throw Exception("Image file not found: ${imageFile.absolutePath}")
        }
        
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        if (bitmap == null) {
            throw Exception("Could not decode image: ${imageFile.absolutePath}")
        }
        
        // Process with integrated pipeline
        val pipelineResult = integratedPipeline.processCouponImage(bitmap)
        val processingTime = System.currentTimeMillis() - startTime
        
        // Extract actual results
        val actual = ActualResult(
            storeName = pipelineResult.coupon.storeName,
            code = pipelineResult.coupon.redeemCode,
            amount = pipelineResult.coupon.benefitValue?.toString() ?: pipelineResult.coupon.cashbackAmount.toString(),
            expiryDate = pipelineResult.coupon.expiryIso,
            description = pipelineResult.coupon.description,
            confidence = pipelineResult.coupon.confidence
        )
        
        // Calculate accuracy
        val accuracy = calculateAccuracy(testCase.expected, actual)
        val passed = accuracy >= 0.7f // 70% threshold for passing
        
        TestResult(
            imageName = testCase.imageName,
            expected = testCase.expected,
            actual = actual,
            accuracy = accuracy,
            processingTimeMs = processingTime,
            passed = passed
        )
    }
    
    /**
     * Calculate accuracy between expected and actual results
     */
    private fun calculateAccuracy(expected: ExpectedResult, actual: ActualResult): Float {
        var score = 0f
        var totalWeight = 0f
        
        // Store name (weight: 0.3)
        if (expected.storeName.isNotBlank()) {
            val storeNameScore = calculateStringSimilarity(expected.storeName, actual.storeName)
            score += storeNameScore * 0.3f
            totalWeight += 0.3f
        }
        
        // Code (weight: 0.25)
        if (!expected.code.isNullOrBlank()) {
            val codeScore = if (expected.code == actual.code) 1f else 0f
            score += codeScore * 0.25f
            totalWeight += 0.25f
        }
        
        // Amount (weight: 0.2)
        if (!expected.amount.isNullOrBlank()) {
            val amountScore = calculateAmountSimilarity(expected.amount, actual.amount)
            score += amountScore * 0.2f
            totalWeight += 0.2f
        }
        
        // Expiry date (weight: 0.15)
        if (!expected.expiryDate.isNullOrBlank()) {
            val expiryScore = calculateDateSimilarity(expected.expiryDate, actual.expiryDate)
            score += expiryScore * 0.15f
            totalWeight += 0.15f
        }
        
        // Description (weight: 0.1)
        if (!expected.description.isNullOrBlank()) {
            val descriptionScore = calculateStringSimilarity(expected.description, actual.description ?: "")
            score += descriptionScore * 0.1f
            totalWeight += 0.1f
        }
        
        return if (totalWeight > 0f) score / totalWeight else 0f
    }
    
    /**
     * Calculate string similarity using Levenshtein distance
     */
    private fun calculateStringSimilarity(expected: String, actual: String): Float {
        if (expected == actual) return 1f
        if (expected.isBlank() || actual.isBlank()) return 0f
        
        val distance = levenshteinDistance(expected.lowercase(), actual.lowercase())
        val maxLength = maxOf(expected.length, actual.length)
        return 1f - (distance.toFloat() / maxLength)
    }
    
    /**
     * Calculate amount similarity
     */
    private fun calculateAmountSimilarity(expected: String, actual: String?): Float {
        if (actual.isNullOrBlank()) return 0f
        
        // Extract numeric values
        val expectedNum = extractNumericValue(expected)
        val actualNum = extractNumericValue(actual)
        
        if (expectedNum == null || actualNum == null) {
            return calculateStringSimilarity(expected, actual)
        }
        
        return if (expectedNum == actualNum) 1f else 0f
    }
    
    /**
     * Calculate date similarity
     */
    private fun calculateDateSimilarity(expected: String, actual: String?): Float {
        if (actual.isNullOrBlank()) return 0f
        
        // Try to parse both dates and compare
        val expectedDate = parseDate(expected)
        val actualDate = parseDate(actual)
        
        if (expectedDate != null && actualDate != null) {
            val diffDays = kotlin.math.abs((expectedDate.time - actualDate.time) / (1000 * 60 * 60 * 24))
            return if (diffDays <= 1) 1f else 0f // Allow 1 day difference
        }
        
        return calculateStringSimilarity(expected, actual)
    }
    
    /**
     * Extract numeric value from string
     */
    private fun extractNumericValue(text: String): Double? {
        val regex = "\\d+(?:\\.\\d+)?".toRegex()
        val match = regex.find(text)
        return match?.value?.toDoubleOrNull()
    }
    
    /**
     * Parse date string
     */
    private fun parseDate(dateString: String): Date? {
        val formats = listOf(
            "yyyy-MM-dd",
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "dd-MM-yyyy",
            "MM-dd-yyyy"
        )
        
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                return sdf.parse(dateString)
            } catch (e: Exception) {
                // Try next format
            }
        }
        return null
    }
    
    /**
     * Calculate Levenshtein distance between two strings
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        
        for (i in 0..s1.length) {
            for (j in 0..s2.length) {
                when {
                    i == 0 -> dp[i][j] = j
                    j == 0 -> dp[i][j] = i
                    s1[i - 1] == s2[j - 1] -> dp[i][j] = dp[i - 1][j - 1]
                    else -> dp[i][j] = 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        
        return dp[s1.length][s2.length]
    }
    
    /**
     * Load test metadata
     */
    private fun loadMetadata(metadataFile: File): TestMetadata {
        val jsonString = metadataFile.readText()
        val json = JSONObject(jsonString)
        
        val testCases = mutableListOf<TestCase>()
        val testCasesArray = json.getJSONArray("testCases")
        
        for (i in 0 until testCasesArray.length()) {
            val testCaseObj = testCasesArray.getJSONObject(i)
            val expectedObj = testCaseObj.getJSONObject("expected")
            
            val expected = ExpectedResult(
                storeName = expectedObj.getString("storeName"),
                code = expectedObj.optString("code").takeIf { it.isNotBlank() },
                amount = expectedObj.optString("amount").takeIf { it.isNotBlank() },
                expiryDate = expectedObj.optString("expiryDate").takeIf { it.isNotBlank() },
                description = expectedObj.optString("description").takeIf { it.isNotBlank() }
            )
            
            testCases.add(
                TestCase(
                    imageName = testCaseObj.getString("imageName"),
                    expected = expected
                )
            )
        }
        
        return TestMetadata(testCases)
    }
    
    /**
     * Calculate test summary
     */
    private fun calculateSummary(results: List<TestResult>): TestSummary {
        val totalTests = results.size
        val passedTests = results.count { it.passed }
        val failedTests = totalTests - passedTests
        val averageAccuracy = results.map { it.accuracy }.average().toFloat()
        val averageProcessingTime = results.map { it.processingTimeMs }.average().toLong()
        
        return TestSummary(
            totalTests = totalTests,
            passedTests = passedTests,
            failedTests = failedTests,
            averageAccuracy = averageAccuracy,
            averageProcessingTimeMs = averageProcessingTime,
            results = results
        )
    }
    
    /**
     * Save test results to file
     */
    private fun saveTestResults(goldenSetDir: File, summary: TestSummary) {
        val resultsFile = File(goldenSetDir, RESULTS_FILE)
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        
        val json = JSONObject().apply {
            put("timestamp", timestamp)
            put("totalTests", summary.totalTests)
            put("passedTests", summary.passedTests)
            put("failedTests", summary.failedTests)
            put("averageAccuracy", summary.averageAccuracy)
            put("averageProcessingTimeMs", summary.averageProcessingTimeMs)
            
            val resultsArray = org.json.JSONArray()
            for (result in summary.results) {
                val resultObj = JSONObject().apply {
                    put("imageName", result.imageName)
                    put("accuracy", result.accuracy)
                    put("processingTimeMs", result.processingTimeMs)
                    put("passed", result.passed)
                    
                    val expectedObj = JSONObject().apply {
                        put("storeName", result.expected.storeName)
                        put("code", result.expected.code ?: "")
                        put("amount", result.expected.amount ?: "")
                        put("expiryDate", result.expected.expiryDate ?: "")
                        put("description", result.expected.description ?: "")
                    }
                    put("expected", expectedObj)
                    
                    val actualObj = JSONObject().apply {
                        put("storeName", result.actual.storeName)
                        put("code", result.actual.code ?: "")
                        put("amount", result.actual.amount ?: "")
                        put("expiryDate", result.actual.expiryDate ?: "")
                        put("description", result.actual.description ?: "")
                        put("confidence", result.actual.confidence)
                    }
                    put("actual", actualObj)
                }
                resultsArray.put(resultObj)
            }
            put("results", resultsArray)
        }
        
        resultsFile.writeText(json.toString(2))
        Log.d(loggerTag, "Test results saved to: ${resultsFile.absolutePath}")
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        integratedPipeline.close()
    }
    
    private data class TestMetadata(val testCases: List<TestCase>)
    private data class TestCase(val imageName: String, val expected: ExpectedResult)
}

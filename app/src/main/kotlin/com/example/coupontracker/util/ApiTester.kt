package com.example.coupontracker.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Utility class to test API connections
 */
class ApiTester(private val context: Context) {
    
    private val TAG = "ApiTester"
    
    /**
     * Test the Mistral OCR API connection with a sample image
     * @param apiKey The API key to test
     * @return A pair containing success status and result message
     */
    suspend fun testMistralApi(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Pair(false, "API key cannot be empty")
            }
            
            Log.d(TAG, "Testing Mistral API with key: ${apiKey.take(5)}...")
            
            // Create a service instance
            val service = MistralOcrService(apiKey)
            
            // Load a test image from resources
            val testImage = BitmapFactory.decodeResource(context.resources, 
                context.resources.getIdentifier("test_image", "drawable", context.packageName))
            
            if (testImage == null) {
                Log.e(TAG, "Failed to load test image")
                return@withContext Pair(false, "Failed to load test image")
            }
            
            // Try to extract text
            val result = service.extractTextFromImage(testImage)
            
            if (result.isBlank()) {
                Log.e(TAG, "API test failed: No text extracted")
                return@withContext Pair(false, "API test failed: No text extracted")
            }
            
            Log.d(TAG, "API test successful: ${result.take(50)}...")
            return@withContext Pair(true, "API connection successful")
            
        } catch (e: IOException) {
            Log.e(TAG, "API test failed with network error", e)
            return@withContext Pair(false, "Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "API test failed with error", e)
            return@withContext Pair(false, "Error: ${e.message}")
        }
    }
    
    /**
     * Test the Google Cloud Vision API connection with a sample image
     * @param apiKey The API key to test
     * @return A pair containing success status and result message
     */
    suspend fun testGoogleVisionApi(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Pair(false, "API key cannot be empty")
            }
            
            Log.d(TAG, "Testing Google Cloud Vision API with key: ${apiKey.take(5)}...")
            
            // Create a helper instance
            val helper = GoogleVisionHelper(apiKey)
            
            // Load a test image from resources
            val testImage = BitmapFactory.decodeResource(context.resources, 
                context.resources.getIdentifier("test_image", "drawable", context.packageName))
            
            if (testImage == null) {
                Log.e(TAG, "Failed to load test image")
                return@withContext Pair(false, "Failed to load test image")
            }
            
            // Try to extract text
            val result = helper.processImageFromBitmap(testImage)
            
            if (result.isBlank()) {
                Log.e(TAG, "API test failed: No text extracted")
                return@withContext Pair(false, "API test failed: No text extracted")
            }
            
            Log.d(TAG, "API test successful: ${result.take(100)}...")
            return@withContext Pair(true, "API connection successful")
            
        } catch (e: IOException) {
            Log.e(TAG, "API test failed with network error", e)
            return@withContext Pair(false, "Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "API test failed with error", e)
            return@withContext Pair(false, "Error: ${e.message}")
        }
    }
    
    /**
     * Quick check to verify if the API key format is valid
     * (This doesn't guarantee the key works, just that it's in the expected format)
     */
    fun isApiKeyFormatValid(apiKey: String): Boolean {
        // Mistral API keys typically start with "mis_" and are around 40-50 characters long
        return apiKey.startsWith("mis_") && apiKey.length >= 40
    }
    
    /**
     * Quick check to verify if the Google Cloud Vision API key format is valid
     */
    fun isGoogleVisionApiKeyValid(apiKey: String): Boolean {
        // Google Cloud API keys are typically at least 30 characters
        return apiKey.length >= 30
    }
} 
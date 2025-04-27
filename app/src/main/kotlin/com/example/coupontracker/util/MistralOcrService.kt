package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Service class to interact with Mistral OCR AI API
 */
class MistralOcrService(private val apiKey: String) {
    
    private val TAG = "MistralOcrService"
    private val API_URL = "https://api.mistral.ai/v1/chat/completions"
    
    init {
        if (apiKey.isBlank()) {
            Log.e(TAG, "Mistral API key is blank or empty")
        } else if (apiKey.length < 30) {
            Log.w(TAG, "Mistral API key seems too short - it may be invalid")
        } else {
            Log.d(TAG, "Initialized Mistral OCR service with key: ${apiKey.take(5)}...")
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Extract text from an image using Mistral OCR API
     * @param bitmap The image to process
     * @return The extracted text
     */
    suspend fun extractTextFromImage(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(bitmap)
            
            // Create JSON payload
            val jsonPayload = createJsonPayload(base64Image)
            
            Log.d(TAG, "Sending request to Mistral API for text extraction")
            Log.d(TAG, "JSON payload: ${jsonPayload.take(100)}...")
            
            // Create request
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            // Execute request
            Log.d(TAG, "Executing Mistral API request...")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Mistral API call failed: ${response.code} - ${response.message}")
                Log.e(TAG, "Error details: $errorBody")
                return@withContext ""
            }
            
            // Parse response
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Received response from Mistral: ${responseBody.take(100)}...")
            
            // Check if response contains error
            if (responseBody.contains("error")) {
                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.has("error")) {
                    val error = jsonResponse.getJSONObject("error")
                    val message = error.optString("message", "Unknown error")
                    val type = error.optString("type", "Unknown type")
                    Log.e(TAG, "Mistral API returned error: $type - $message")
                    return@withContext ""
                }
            }
            
            val jsonResponse = JSONObject(responseBody)
            
            // Extract text from response
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                val content = message.getString("content")
                Log.d(TAG, "Extracted text: ${content.take(100)}...")
                return@withContext content
            } else {
                Log.w(TAG, "No choices found in Mistral response")
                Log.d(TAG, "Full response: $responseBody")
                return@withContext ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text with Mistral API: ${e.message}", e)
            return@withContext ""
        }
    }
    
    /**
     * Extract coupon information from an image using Mistral OCR API
     * @param bitmap The image to process
     * @return The extracted coupon information
     */
    suspend fun extractCouponInfo(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            // First try to extract all text from the image
            val extractedText = extractTextFromImage(bitmap)
            
            if (extractedText.isBlank()) {
                Log.e(TAG, "Failed to extract any text from the image")
                return@withContext CouponInfo()
            }
            
            Log.d(TAG, "Successfully extracted text, now parsing for coupon info")
            
            // Use the TextExtractor to parse the extracted text
            val textExtractor = TextExtractor()
            val couponInfo = textExtractor.extractCouponInfoSync(extractedText)
            
            Log.d(TAG, "Extracted coupon info: $couponInfo")
            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting coupon info from image", e)
            return@withContext CouponInfo()
        }
    }
    
    /**
     * Convert bitmap to base64 string
     * @param bitmap The bitmap to convert
     * @return Base64 encoded string
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * Create JSON payload for OCR request
     * @param base64Image Base64 encoded image
     * @return JSON payload as string
     */
    private fun createJsonPayload(base64Image: String): String {
        // Create the message content array
        val contentArray = JSONArray()
        
        // Add text content with a more specific prompt
        val textContent = JSONObject()
        textContent.put("type", "text")
        textContent.put("text", 
            "Please extract all text from this coupon image. " +
            "I need to identify the following information for EACH coupon in the image (there may be multiple coupons):\n" +
            "1. Brand/Store name (e.g., XYXX, ABHIBUS, NEWMEE, IXIGO, BOAT)\n" +
            "2. Offer description (e.g., 'You won 5 products at ₹999 + ₹150 cashback via CRED pay on XYXX')\n" +
            "3. Coupon/redeem code (e.g., 'CRDLUKES799')\n" +
            "4. Status (e.g., 'Available to Redeem')\n" +
            "5. Rating if present (e.g., '⭐ 4.31')\n" +
            "6. Expiry information (e.g., 'Expires in 14 hours')\n" +
            "7. Cashback amount (the numeric value, e.g., 150 from '₹150 cashback')\n\n" +
            "Please extract and list ALL text you can see in the image, and clearly separate different coupons if multiple are present."
        )
        contentArray.put(textContent)
        
        // Add image content
        val imageContent = JSONObject()
        imageContent.put("type", "image_url")
        
        val imageUrl = JSONObject()
        imageUrl.put("url", "data:image/jpeg;base64,$base64Image")
        imageContent.put("image_url", imageUrl)
        contentArray.put(imageContent)
        
        // Create the message object
        val message = JSONObject()
        message.put("role", "user")
        message.put("content", contentArray)
        
        // Create the messages array
        val messages = JSONArray()
        messages.put(message)
        
        // Create the main request object
        val requestObject = JSONObject()
        requestObject.put("model", "mistral-medium")
        requestObject.put("messages", messages)
        requestObject.put("temperature", 0.0)
        
        return requestObject.toString()
    }
    
    /**
     * Process text with a custom prompt
     * Used for validation and enhancement of Google Cloud Vision results
     * 
     * @param bitmap The image to process
     * @param customPrompt The custom prompt to use
     * @return The processed text
     */
    suspend fun processTextWithCustomPrompt(
        bitmap: Bitmap,
        customPrompt: String
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing with custom prompt: ${customPrompt.take(100)}...")
            
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(bitmap)
            
            // Create JSON payload with custom prompt
            val jsonPayload = createCustomPromptPayload(base64Image, customPrompt)
            
            Log.d(TAG, "Sending request to Mistral API with custom prompt")
            
            // Create request
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            // Execute request
            Log.d(TAG, "Executing Mistral API request...")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "Mistral API call failed: ${response.code} - ${response.message}")
                Log.e(TAG, "Error details: $errorBody")
                return@withContext ""
            }
            
            // Parse response
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Received response from Mistral: ${responseBody.take(100)}...")
            
            val jsonResponse = JSONObject(responseBody)
            
            // Extract text from response
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).getJSONObject("message")
                val content = message.getString("content")
                Log.d(TAG, "Processed text: ${content.take(100)}...")
                return@withContext content
            } else {
                Log.w(TAG, "No choices found in Mistral response")
                Log.d(TAG, "Full response: $responseBody")
                return@withContext ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Mistral custom prompt: ${e.message}", e)
            return@withContext ""
        }
    }
    
    /**
     * Create a JSON payload with a custom prompt
     * 
     * @param base64Image Base64 encoded image
     * @param customPrompt The custom prompt to use
     * @return The JSON payload as a string
     */
    private fun createCustomPromptPayload(base64Image: String, customPrompt: String): String {
        // Create the message content array
        val contentArray = JSONArray()
        
        // Add custom prompt as text content
        val textContent = JSONObject()
        textContent.put("type", "text")
        textContent.put("text", customPrompt)
        contentArray.put(textContent)
        
        // Add image content
        val imageContent = JSONObject()
        imageContent.put("type", "image_url")
        
        val imageUrl = JSONObject()
        imageUrl.put("url", "data:image/jpeg;base64,$base64Image")
        imageContent.put("image_url", imageUrl)
        contentArray.put(imageContent)
        
        // Create the message object
        val message = JSONObject()
        message.put("role", "user")
        message.put("content", contentArray)
        
        // Create the messages array
        val messages = JSONArray()
        messages.put(message)
        
        // Create the main request object
        val requestObject = JSONObject()
        requestObject.put("model", "mistral-medium")
        requestObject.put("messages", messages)
        
        // Use lower temperature for more precise and deterministic output
        requestObject.put("temperature", 0.0)
        
        return requestObject.toString()
    }
    
    /**
     * Test the Mistral API connection with the provided key
     * @return True if the connection is successful, false otherwise
     */
    suspend fun testApiConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing Mistral API connection...")
            
            // Create a simple request to test the API
            val testPayload = JSONObject()
            testPayload.put("model", "mistral-medium")
            
            val messagesArray = JSONArray()
            val messageObject = JSONObject()
            messageObject.put("role", "user")
            messageObject.put("content", "Hello, this is a test message")
            messagesArray.put(messageObject)
            
            testPayload.put("messages", messagesArray)
            testPayload.put("max_tokens", 5) // Minimal response to save quota
            
            val request = Request.Builder()
                .url(API_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(testPayload.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "API connection test failed: ${response.code} - ${response.message}")
                Log.e(TAG, "Error details: $errorBody")
                return@withContext false
            }
            
            Log.d(TAG, "API connection test successful")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Error testing API connection: ${e.message}", e)
            return@withContext false
        }
    }
    
    /**
     * Test API availability by sending a minimal request
     * @return True if the API is available, false otherwise
     */
    suspend fun testApiAvailability(): Boolean = withContext(Dispatchers.IO) {
        return@withContext testApiConnection()
    }
} 
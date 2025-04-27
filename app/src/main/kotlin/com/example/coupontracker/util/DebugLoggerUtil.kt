package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
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
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Utility class for debugging API issues
 */
class DebugLoggerUtil(private val context: Context) {
    
    private val TAG = "DebugLoggerUtil"
    
    /**
     * Test the Google Cloud Vision API with a direct HTTP request to diagnose issues
     */
    suspend fun testVisionApiDirectly(apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing Google Vision API directly with key: ${apiKey.take(5)}...")
            
            // Load test image
            val testImage = BitmapFactory.decodeResource(
                context.resources,
                context.resources.getIdentifier("test_image", "drawable", context.packageName)
            )
            
            if (testImage == null) {
                return@withContext "Error: Failed to load test image"
            }
            
            // Create base64 encoded image
            val base64Image = bitmapToBase64(testImage)
            Log.d(TAG, "Converted image to base64 (length: ${base64Image.length})")
            
            // Create request JSON
            val jsonObject = JSONObject()
            val requestsArray = JSONArray()
            val requestObject = JSONObject()
            
            // Add image
            val imageObject = JSONObject()
            imageObject.put("content", base64Image)
            requestObject.put("image", imageObject)
            
            // Add features
            val featuresArray = JSONArray()
            val textDetectionFeature = JSONObject()
            textDetectionFeature.put("type", "TEXT_DETECTION")
            textDetectionFeature.put("maxResults", 10)
            featuresArray.put(textDetectionFeature)
            
            requestObject.put("features", featuresArray)
            requestsArray.put(requestObject)
            jsonObject.put("requests", requestsArray)
            
            val jsonRequest = jsonObject.toString()
            
            // Setup HTTP client with longer timeouts
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            
            // Create request
            val request = Request.Builder()
                .url("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
                .addHeader("Content-Type", "application/json")
                .post(jsonRequest.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            // Execute request
            Log.d(TAG, "Executing Vision API request...")
            val response = client.newCall(request).execute()
            
            // Process response
            val responseBody = response.body?.string() ?: ""
            
            if (!response.isSuccessful) {
                Log.e(TAG, "API call failed: ${response.code} - Response body: $responseBody")
                return@withContext "Error ${response.code}: ${responseBody.take(500)}"
            }
            
            Log.d(TAG, "Vision API Response: ${responseBody.take(100)}...")
            
            // Pretty-print and log the response
            val formattedJson = formatJsonForDisplay(responseBody)
            saveResponseToFile(formattedJson)
            
            // Extract text from response
            val result = extractTextFromResponse(responseBody)
            return@withContext "Success: $result"
            
        } catch (e: Exception) {
            Log.e(TAG, "Error testing Vision API directly", e)
            return@withContext "Error: ${e.message ?: "Unknown error"}"
        }
    }
    
    /**
     * Extract text from Google Cloud Vision API response
     */
    private fun extractTextFromResponse(responseJson: String): String {
        try {
            val jsonObject = JSONObject(responseJson)
            
            // Check if the response contains an error
            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val code = error.optInt("code", -1)
                return "API Error $code: $message"
            }
            
            // Check if responses array exists
            if (!jsonObject.has("responses")) {
                return "No 'responses' field in JSON response"
            }
            
            val responsesArray = jsonObject.getJSONArray("responses")
            
            if (responsesArray.length() == 0) {
                return "Empty 'responses' array in JSON response"
            }
            
            val firstResponse = responsesArray.getJSONObject(0)
            
            // First try TEXT_DETECTION result
            if (firstResponse.has("textAnnotations")) {
                val textAnnotations = firstResponse.getJSONArray("textAnnotations")
                
                // The first annotation contains the entire text
                if (textAnnotations.length() > 0) {
                    val firstAnnotation = textAnnotations.getJSONObject(0)
                    if (firstAnnotation.has("description")) {
                        return firstAnnotation.getString("description")
                    }
                }
            }
            
            // Then try DOCUMENT_TEXT_DETECTION result
            if (firstResponse.has("fullTextAnnotation")) {
                val fullTextAnnotation = firstResponse.getJSONObject("fullTextAnnotation")
                if (fullTextAnnotation.has("text")) {
                    return fullTextAnnotation.getString("text")
                }
            }
            
            return "No text found in response"
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from response", e)
            return "Error parsing response: ${e.message}"
        }
    }
    
    /**
     * Convert bitmap to base64 string
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * Format JSON for better display
     */
    private fun formatJsonForDisplay(jsonString: String): String {
        try {
            val json = if (jsonString.trim().startsWith("{")) {
                JSONObject(jsonString)
            } else {
                JSONArray(jsonString)
            }
            
            return if (json is JSONObject) {
                json.toString(4)  // 4 spaces for indentation
            } else if (json is JSONArray) {
                (json as JSONArray).toString(4)
            } else {
                jsonString
            }
        } catch (e: Exception) {
            return jsonString  // Return original string if formatting fails
        }
    }
    
    /**
     * Save API response to a file for analysis
     */
    private fun saveResponseToFile(response: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "vision_api_response_$timestamp.json"
            
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(storageDir, fileName)
            
            FileOutputStream(file).use { output ->
                output.write(response.toByteArray())
            }
            
            Log.d(TAG, "Response saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save response to file", e)
        }
    }
} 
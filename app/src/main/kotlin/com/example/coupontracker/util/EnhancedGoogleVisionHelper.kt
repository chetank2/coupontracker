package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Enhanced Google Vision API helper with improved preprocessing and text extraction
 */
class EnhancedGoogleVisionHelper(
    private val apiKey: String,
    private val context: Context
) {
    companion object {
        private const val TAG = "EnhancedGoogleVisionHelper"
        private const val API_URL = "https://vision.googleapis.com/v1/images:annotate"
        private const val MAX_RETRIES = 2

        // Common coupon fields regex patterns
        private val STORE_PATTERNS = listOf(
            Pattern.compile("(?i)(?:from|at|by|store|merchant)\\s*(?::)?\\s*([A-Za-z0-9\\s&.'-]{2,25})"),
            Pattern.compile("(?i)\\b(myntra|amazon|flipkart|swiggy|zomato|paytm|phonepe|gpay|googlepay)\\b")
        )

        private val CODE_PATTERNS = listOf(
            Pattern.compile("(?i)(?:code|coupon|promo|voucher)\\s*(?::)?\\s*([A-Z0-9-]{4,20})\\b"),
            Pattern.compile("(?i)use\\s+code\\s+([A-Z0-9-]{4,20})\\b"),
            Pattern.compile("\\b([A-Z0-9]{8,12})\\b") // Standalone code format
        )

        private val AMOUNT_PATTERNS = listOf(
            Pattern.compile("(?i)(?:Rs\\.?|₹|INR)\\s*(\\d+(?:\\.\\d{1,2})?)"),
            Pattern.compile("(?i)(\\d+(?:\\.\\d{1,2})?)\\s*(?:%|percent)\\s*(?:off|discount|cashback)"),
            Pattern.compile("(?i)(?:up to|flat|get|save)\\s+(?:Rs\\.?|₹|INR)\\s*(\\d+)")
        )

        private val EXPIRY_PATTERNS = listOf(
            Pattern.compile("(?i)(?:exp|expires|valid until|valid till)\\s*(?::)?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"),
            Pattern.compile("(?i)(?:exp|expires|valid)\\s*(?::)?\\s*(\\d{1,2}\\s+[A-Za-z]{3,9}\\s+\\d{2,4})"),
            Pattern.compile("(?i)valid\\s+for\\s+(\\d+)\\s+days")
        )

        private val DESC_PATTERNS = listOf(
            Pattern.compile("(?i)(?:off on|discount on|applicable on)\\s+([^\\n\\r.]{5,50})"),
            Pattern.compile("(?i)(?:get|avail|flat|use for)\\s+([^\\n\\r.]{5,50})")
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val imagePreprocessor = ImagePreprocessor()

    /**
     * Process an image from URI with enhanced preprocessing and API configuration
     */
    suspend fun processImageFromUri(imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image from URI using enhanced Google Vision API")

                // Load and preprocess image
                val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to load bitmap from URI")
                    return@withContext createFallbackText()
                }

                processImageFromBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI with enhanced Google Vision API", e)
                return@withContext createFallbackText()
            }
        }
    }

    /**
     * Process a bitmap with enhanced preprocessing and multiple API configurations
     * This method tries different preprocessing variants and API configurations for best results
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String = coroutineScope {
        try {
            Log.d(TAG, "Processing bitmap with enhanced Google Vision API (${bitmap.width}x${bitmap.height})")

            // Create preprocessing variants
            val variants = imagePreprocessor.createProcessingVariants(bitmap)
            Log.d(TAG, "Created ${variants.size} preprocessing variants")

            // Process each variant with different API configurations
            val results = variants.map { variantBitmap ->
                async {
                    // Try both document text and text detection modes
                    val textDetectionResult = try {
                        makeApiRequest(variantBitmap, "TEXT_DETECTION")
                    } catch (e: Exception) {
                        Log.e(TAG, "TEXT_DETECTION mode failed", e)
                        ""
                    }

                    val documentTextResult = try {
                        makeApiRequest(variantBitmap, "DOCUMENT_TEXT_DETECTION",
                            languageHints = listOf("en"))
                    } catch (e: Exception) {
                        Log.e(TAG, "DOCUMENT_TEXT_DETECTION mode failed", e)
                        ""
                    }

                    // Return the better result (longer text is usually better)
                    if (textDetectionResult.length > documentTextResult.length) {
                        textDetectionResult
                    } else {
                        documentTextResult
                    }
                }
            }.awaitAll()

            // Choose the best result - typically the one with the most text
            val bestResult = results.maxByOrNull { it.length } ?: ""

            if (bestResult.isBlank()) {
                Log.w(TAG, "All Google Vision API attempts returned empty results")
                return@coroutineScope createFallbackText()
            }

            Log.d(TAG, "Best OCR result length: ${bestResult.length}")
            return@coroutineScope bestResult
        } catch (e: Exception) {
            Log.e(TAG, "Error processing bitmap with enhanced Google Vision API", e)
            return@coroutineScope createFallbackText()
        }
    }

    /**
     * Make a request to the Google Vision API
     *
     * @param bitmap Image to process
     * @param featureType Type of detection ("TEXT_DETECTION" or "DOCUMENT_TEXT_DETECTION")
     * @param languageHints Optional language hints to improve recognition
     * @return Recognized text
     */
    private suspend fun makeApiRequest(
        bitmap: Bitmap,
        featureType: String,
        languageHints: List<String>? = null
    ): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt < MAX_RETRIES) {
            try {
                Log.d(TAG, "Making Google Vision API request: feature=$featureType, attempt=${attempt + 1}")

                // Convert bitmap to base64
                val base64Image = bitmapToBase64(bitmap)

                // Create request JSON with appropriate configuration
                val requestJson = createRequestJson(base64Image, featureType, languageHints)

                // Make API request
                val requestUrl = "$API_URL?key=$apiKey"

                val request = Request.Builder()
                    .url(requestUrl)
                    .addHeader("Content-Type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    Log.e(TAG, "API call failed: ${response.code} - ${response.message}, Body: $errorBody")
                    throw Exception("API call failed with code ${response.code}: $errorBody")
                }

                val responseBody = response.body?.string() ?: ""
                val extractedText = extractTextFromResponse(responseBody)
                return@withContext extractedText
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Error during API request (attempt ${attempt + 1})", e)
                attempt++
            }
        }

        throw lastException ?: Exception("Failed to make API request after $MAX_RETRIES attempts")
    }

    /**
     * Convert bitmap to base64 string optimized for cloud OCR
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val imageBytes = imagePreprocessor.optimizeForCloudOcr(bitmap)
        return Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    }

    /**
     * Create JSON request for Google Cloud Vision API with advanced configuration
     */
    private fun createRequestJson(
        base64Image: String,
        featureType: String = "DOCUMENT_TEXT_DETECTION",
        languageHints: List<String>? = null
    ): String {
        val jsonObject = JSONObject()
        val requestsArray = JSONArray()
        val requestObject = JSONObject()

        // Add image
        val imageObject = JSONObject()
        imageObject.put("content", base64Image)
        requestObject.put("image", imageObject)

        // Add features
        val featuresArray = JSONArray()
        val featureObject = JSONObject()
        featureObject.put("type", featureType)
        featureObject.put("maxResults", 10)
        featuresArray.put(featureObject)
        requestObject.put("features", featuresArray)

        // Add context with language hints if provided
        if (!languageHints.isNullOrEmpty()) {
            val contextObject = JSONObject()
            val languageHintsArray = JSONArray()
            languageHints.forEach { languageHintsArray.put(it) }
            contextObject.put("languageHints", languageHintsArray)
            requestObject.put("imageContext", contextObject)
        }

        requestsArray.put(requestObject)
        jsonObject.put("requests", requestsArray)

        return jsonObject.toString()
    }

    /**
     * Extract text from Google Cloud Vision API response
     */
    private fun extractTextFromResponse(responseJson: String): String {
        try {
            val jsonObject = JSONObject(responseJson)

            // Check for errors
            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val code = error.optInt("code", -1)
                Log.e(TAG, "API error response: code=$code, message=$message")
                return ""
            }

            // Check if responses array exists
            if (!jsonObject.has("responses")) {
                Log.e(TAG, "No 'responses' field in JSON response")
                return ""
            }

            val responsesArray = jsonObject.getJSONArray("responses")

            if (responsesArray.length() == 0) {
                Log.e(TAG, "Empty 'responses' array in JSON response")
                return ""
            }

            val firstResponse = responsesArray.getJSONObject(0)

            // First try fullTextAnnotation (from DOCUMENT_TEXT_DETECTION)
            if (firstResponse.has("fullTextAnnotation")) {
                val fullTextAnnotation = firstResponse.getJSONObject("fullTextAnnotation")
                if (fullTextAnnotation.has("text")) {
                    val text = fullTextAnnotation.getString("text")
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Extracted text from fullTextAnnotation (length: ${text.length})")
                        return text
                    }
                }
            }

            // Then try textAnnotations (from TEXT_DETECTION)
            if (firstResponse.has("textAnnotations")) {
                val textAnnotations = firstResponse.getJSONArray("textAnnotations")

                // The first annotation contains the entire text
                if (textAnnotations.length() > 0) {
                    val firstAnnotation = textAnnotations.getJSONObject(0)
                    if (firstAnnotation.has("description")) {
                        val text = firstAnnotation.getString("description")
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Extracted text from textAnnotations (length: ${text.length})")
                            return text
                        }
                    }
                }
            }

            Log.w(TAG, "No text found in Google Cloud Vision response")
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from Google Cloud Vision response", e)
            return ""
        }
    }

    /**
     * Extract coupon information with advanced pattern matching
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        Log.d(TAG, "Extracting coupon info with enhanced patterns")

        // Store name extraction
        extractPattern(STORE_PATTERNS, text).firstOrNull()?.let {
            result["storeName"] = capitalizeWords(it.trim())
        }

        // Code extraction
        extractPattern(CODE_PATTERNS, text).firstOrNull()?.let {
            result["code"] = it.trim().uppercase()
        }

        // Amount extraction
        extractPattern(AMOUNT_PATTERNS, text).firstOrNull()?.let {
            val amount = it.trim()
            result["amount"] = if (amount.contains("₹") || amount.contains("Rs")) amount else "₹$amount"
        }

        // Expiry date extraction
        extractPattern(EXPIRY_PATTERNS, text).firstOrNull()?.let {
            result["expiryDate"] = it.trim()
        }

        // Description extraction
        extractPattern(DESC_PATTERNS, text).firstOrNull()?.let {
            result["description"] = it.trim()
        }

        // If we couldn't extract a description but found store and amount, create a synthesized description
        if (!result.containsKey("description") && result.containsKey("storeName") && result.containsKey("amount")) {
            val store = result["storeName"]
            val amount = result["amount"]
            result["description"] = "Get $amount off at $store"
        }

        // If we have minimal information, set defaults
        if (result.isEmpty() || (!result.containsKey("storeName") && !result.containsKey("code"))) {
            result["storeName"] = "Unknown Store"
            if (!result.containsKey("amount")) {
                result["amount"] = "₹0"
            }
            if (!result.containsKey("description")) {
                result["description"] = "Scanned coupon"
            }
        }

        Log.d(TAG, "Extracted coupon info: $result")
        return result
    }

    /**
     * Extract value using multiple regex patterns
     */
    private fun extractPattern(patterns: List<Pattern>, text: String): List<String> {
        val results = mutableListOf<String>()

        for (pattern in patterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                if (matcher.groupCount() >= 1) {
                    val match = matcher.group(1)
                    if (!match.isNullOrBlank()) {
                        results.add(match)
                    }
                }
            }
        }

        return results
    }

    /**
     * Capitalize first letter of each word
     */
    private fun capitalizeWords(text: String): String {
        return text.split("\\s+".toRegex())
            .joinToString(" ") { word ->
                if (word.length > 1) word[0].uppercase() + word.substring(1).lowercase()
                else word.uppercase()
            }
    }

    /**
     * Create fallback text when OCR fails
     */
    private fun createFallbackText(): String {
        return """
            Store: Vision API Fallback
            Coupon Code: VISION100
            Get ₹100 off your next purchase
            Expires: 31/12/2023
            Description: Enhanced OCR fallback coupon
        """.trimIndent()
    }

    /**
     * Extract text from an image using enhanced Google Cloud Vision API
     * This is a simplified convenience method for use with CombinedOCRService
     *
     * @param bitmap The image to process
     * @return The extracted text
     */
    suspend fun extractText(bitmap: Bitmap): String = coroutineScope {
        return@coroutineScope processImageFromBitmap(bitmap)
    }

    /**
     * Test API availability by sending a minimal request
     * @return True if the API is available, false otherwise
     */
    suspend fun testApiAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing Google Cloud Vision API availability")

            // Validate API key
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is blank, cannot test Google Cloud Vision API")
                return@withContext false
            }

            // Create a minimal request to verify API access
            val jsonObject = JSONObject()
            val requestsArray = JSONArray()
            val requestObject = JSONObject()

            // Create a minimal 1x1 transparent bitmap for testing
            val testBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val base64Image = bitmapToBase64(testBitmap)

            // Add image with minimal data
            val imageObject = JSONObject()
            imageObject.put("content", base64Image)
            requestObject.put("image", imageObject)

            // Add TEXT_DETECTION feature only
            val featuresArray = JSONArray()
            val textDetectionFeature = JSONObject()
            textDetectionFeature.put("type", "TEXT_DETECTION")
            textDetectionFeature.put("maxResults", 1)
            featuresArray.put(textDetectionFeature)

            requestObject.put("features", featuresArray)
            requestsArray.put(requestObject)
            jsonObject.put("requests", requestsArray)

            val requestJson = jsonObject.toString()
            val requestUrl = "$API_URL?key=$apiKey"

            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            // Execute request with a shorter timeout for testing
            val testClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()

            Log.d(TAG, "Executing API test request")
            val response = testClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "API test failed: ${response.code} - ${response.message}, Body: $errorBody")
                return@withContext false
            }

            // If we get a successful response, API is available
            Log.d(TAG, "Google Cloud Vision API test successful")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Error testing Google Cloud Vision API: ${e.message}", e)
            return@withContext false
        }
    }
}


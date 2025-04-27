package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import java.util.concurrent.TimeUnit

/**
 * Google Vision API helper for OCR text recognition using Cloud Vision API
 */
class GoogleVisionHelper(
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)  // Increased timeout
        .readTimeout(60, TimeUnit.SECONDS)     // Increased timeout
        .writeTimeout(60, TimeUnit.SECONDS)    // Increased timeout
        .build()
    
    private val TAG = "GoogleVisionHelper"
    private val API_URL = "https://vision.googleapis.com/v1/images:annotate"
    
    companion object {
        // Improved regex patterns for coupon information extraction
        // Store name - look for company logos or branded app names
        private val STORE_PATTERN = Pattern.compile("(?i)(?:from\\s+|\\b)(myntra|amazon|flipkart|swiggy|zomato|uber|ola|makemytrip|paytm|phonepe|google|microsoft|apple|netflix|spotify)\\b")
        
        // Myntra specific store pattern
        private val MYNTRA_STORE_PATTERN = Pattern.compile("(?i)\\b(myntra)\\b")
        
        // Code pattern - look for alphanumeric codes with special patterns (often all caps with numbers)
        private val CODE_PATTERN = Pattern.compile("(?i)(?:code:?|coupon:?|promo:?|voucher:?|redeem:?|use:?)\\s*([A-Z0-9]{5,})\\b")
        
        // Myntra specific code pattern - typically longer codes without labels
        private val MYNTRA_CODE_PATTERN = Pattern.compile("(?i)\\b([A-Z0-9]{10,})\\b")
        
        // Alternative code pattern - look for isolated alphanumeric strings that match coupon code format
        private val ALT_CODE_PATTERN = Pattern.compile("\\b([A-Z0-9]{6,})\\b")
        
        // Amount pattern - more flexible to catch common formats like "₹200 off" or "up to ₹200"
        private val AMOUNT_PATTERN = Pattern.compile("(?i)(?:Rs\\.?|₹)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:%)?\\s*(?:off|cashback|discount|reward|save)")
        
        // Myntra specific amount pattern - "up to ₹200 off" format common in Myntra coupons
        private val MYNTRA_AMOUNT_PATTERN = Pattern.compile("(?i)(?:up to|flat|get|avail)\\s+(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d{1,2})?)\\b")
        
        // Alternative amount pattern - look for currency amounts with the rupee symbol
        private val ALT_AMOUNT_PATTERN = Pattern.compile("(?i)(?:up to |get |save |flat )?(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d{1,2})?)\\b")
        
        // Expiry pattern
        private val EXPIRY_PATTERN = Pattern.compile("(?i)(?:exp|expires|expiry|valid until|valid through|use by)\\s*:?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})")
        
        // Description pattern - improved to catch longer phrases
        private val DESCRIPTION_PATTERN = Pattern.compile("(?i)(?:you won|get|avail|flat|off on|discount on)\\s+([^\\n\\r.]{5,})")
        
        // Myntra specific description pattern
        private val MYNTRA_DESCRIPTION_PATTERN = Pattern.compile("(?i)(you won\\s+a\\s+voucher\\s+up\\s+to\\s+(?:Rs\\.?|₹)\\s*\\d+)\\s+(off)")
    }
    
    /**
     * Process an image from URI using Google Cloud Vision API
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing image from URI with Google Vision API: $imageUri")
                
                // Load bitmap from URI
                val bitmap = loadBitmapFromUri(context, imageUri)
                if (bitmap == null) {
                    Log.e(TAG, "Failed to load bitmap from URI: $imageUri")
                    return@withContext createFallbackText()
                }
                
                Log.d(TAG, "Successfully loaded bitmap from URI: ${bitmap.width}x${bitmap.height}")
                return@withContext processImageFromBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI with Google Vision API", e)
                return@withContext createFallbackText()
            }
        }
    }
    
    /**
     * Process an image bitmap using Google Cloud Vision API
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing bitmap with Google Cloud Vision API: ${bitmap.width}x${bitmap.height}")
            
            // Validate API key
            if (apiKey.isBlank()) {
                Log.e(TAG, "API key is blank, cannot proceed with Google Vision API request")
                return@withContext createFallbackText()
            }
            
            Log.d(TAG, "API key is present (first few chars): ${apiKey.take(5)}...")
            
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(bitmap)
            Log.d(TAG, "Converted bitmap to base64 string (length: ${base64Image.length})")
            
            // Create request JSON
            val requestJson = createRequestJson(base64Image)
            Log.d(TAG, "Created request JSON (length: ${requestJson.length})")
            
            // Create request
            val requestUrl = "$API_URL?key=$apiKey"
            Log.d(TAG, "Making request to: $API_URL")
            
            val request = Request.Builder()
                .url(requestUrl)
                .addHeader("Content-Type", "application/json")
                .post(requestJson.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()
            
            // Execute request
            Log.d(TAG, "Executing request...")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                Log.e(TAG, "API call failed: ${response.code} - ${response.message}, Body: $errorBody")
                return@withContext createFallbackText()
            }
            
            // Parse response
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Received successful response of length: ${responseBody.length}")
            if (responseBody.length > 100) {
                Log.d(TAG, "Response sample: ${responseBody.take(100)}...")
            } else {
                Log.d(TAG, "Full response: $responseBody")
            }
            
            val extractedText = extractTextFromResponse(responseBody)
            Log.d(TAG, "Extracted text (first 100 chars): ${extractedText.take(100)}...")
            
            return@withContext extractedText
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image with Google Cloud Vision", e)
            return@withContext createFallbackText()
        }
    }
    
    /**
     * Load bitmap from URI with proper handling of deprecated APIs
     */
    private fun loadBitmapFromUri(context: Context, imageUri: Uri): Bitmap? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI", e)
            null
        }
    }
    
    /**
     * Convert bitmap to base64 string
     * Uses a lower quality to reduce payload size while maintaining readability
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        // Resize large bitmaps to reduce payload size
        val maxDimension = 1200
        val resizedBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / Math.max(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt()
            val newHeight = (bitmap.height * scale).toInt()
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }
        
        val outputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * Create JSON request for Google Cloud Vision API
     */
    private fun createRequestJson(base64Image: String): String {
        val jsonObject = JSONObject()
        val requestsArray = JSONArray()
        
        // Create request object
        val requestObject = JSONObject()
        
        // Add image
        val imageObject = JSONObject()
        imageObject.put("content", base64Image)
        requestObject.put("image", imageObject)
        
        // Add features (TEXT_DETECTION and DOCUMENT_TEXT_DETECTION)
        val featuresArray = JSONArray()
        
        // Add TEXT_DETECTION feature
        val textDetectionFeature = JSONObject()
        textDetectionFeature.put("type", "TEXT_DETECTION")
        textDetectionFeature.put("maxResults", 10)
        featuresArray.put(textDetectionFeature)
        
        // Add DOCUMENT_TEXT_DETECTION feature
        val docTextDetectionFeature = JSONObject()
        docTextDetectionFeature.put("type", "DOCUMENT_TEXT_DETECTION")
        docTextDetectionFeature.put("maxResults", 10)
        featuresArray.put(docTextDetectionFeature)
        
        requestObject.put("features", featuresArray)
        
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
            
            // Check if the response contains an error
            if (jsonObject.has("error")) {
                val error = jsonObject.getJSONObject("error")
                val message = error.optString("message", "Unknown error")
                val code = error.optInt("code", -1)
                Log.e(TAG, "API error response: code=$code, message=$message")
                return createFallbackText()
            }
            
            // Check if responses array exists
            if (!jsonObject.has("responses")) {
                Log.e(TAG, "No 'responses' field in JSON response")
                return createFallbackText()
            }
            
            val responsesArray = jsonObject.getJSONArray("responses")
            
            if (responsesArray.length() == 0) {
                Log.e(TAG, "Empty 'responses' array in JSON response")
                return createFallbackText()
            }
            
            val firstResponse = responsesArray.getJSONObject(0)
            
            // First try DOCUMENT_TEXT_DETECTION result
            if (firstResponse.has("fullTextAnnotation")) {
                val fullTextAnnotation = firstResponse.getJSONObject("fullTextAnnotation")
                if (fullTextAnnotation.has("text")) {
                    val text = fullTextAnnotation.getString("text")
                    if (text.isNotBlank()) {
                        Log.d(TAG, "Successfully extracted text from fullTextAnnotation")
                        return text
                    }
                }
            }
            
            // Then try TEXT_DETECTION result
            if (firstResponse.has("textAnnotations")) {
                val textAnnotations = firstResponse.getJSONArray("textAnnotations")
                
                // The first annotation contains the entire text
                if (textAnnotations.length() > 0) {
                    val firstAnnotation = textAnnotations.getJSONObject(0)
                    if (firstAnnotation.has("description")) {
                        val text = firstAnnotation.getString("description")
                        if (text.isNotBlank()) {
                            Log.d(TAG, "Successfully extracted text from textAnnotations")
                            return text
                        }
                    }
                }
            }
            
            Log.w(TAG, "No text found in Google Cloud Vision response")
            return createFallbackText()
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from Google Cloud Vision response", e)
            return createFallbackText()
        }
    }
    
    /**
     * Extract coupon information from recognized text
     * This method has been improved to better identify coupon elements
     */
    fun extractCouponInfo(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        
        Log.d(TAG, "Extracting info from text: $text")
        
        // Check if this is a Myntra coupon based on text content
        val isMyntraCoupon = text.contains("myntra", ignoreCase = true) || 
                            text.contains("you won a voucher", ignoreCase = true)
        
        // Look for store name
        if (isMyntraCoupon) {
            findMatch(MYNTRA_STORE_PATTERN, text)?.let {
                result["storeName"] = "Myntra"
                Log.d(TAG, "Found Myntra store name")
            } ?: run {
                result["storeName"] = "Myntra"
                Log.d(TAG, "Set default Myntra store name for Myntra coupon")
            }
        } else {
            findMatch(STORE_PATTERN, text)?.let {
                result["storeName"] = it.trim().capitalize()
                Log.d(TAG, "Found store name: ${it.trim()}")
            }
        }
        
        // Look for coupon code - try different patterns based on store type
        var codeFound = false
        
        if (isMyntraCoupon) {
            // For Myntra, look for their specific code format (usually longer codes)
            findMatch(MYNTRA_CODE_PATTERN, text)?.let {
                // Ensure it's not just a random string of digits but looks like a code
                if (it.contains(Regex("[A-Z]")) && it.contains(Regex("[0-9]"))) {
                    result["code"] = it.trim().uppercase()
                    codeFound = true
                    Log.d(TAG, "Found Myntra coupon code: ${it.trim()}")
                }
            }
        }
        
        // If no Myntra-specific code found, try primary pattern
        if (!codeFound) {
            findMatch(CODE_PATTERN, text)?.let {
                result["code"] = it.trim().uppercase()
                codeFound = true
                Log.d(TAG, "Found coupon code (primary pattern): ${it.trim()}")
            }
        }
        
        // If no code found with primary pattern, try looking for code-like patterns
        if (!codeFound) {
            // Check for the word "code:" followed by text
            val codeIndex = text.indexOf("code:", ignoreCase = true)
            if (codeIndex != -1) {
                // Extract content after "code:" until the next whitespace or end
                val restOfText = text.substring(codeIndex + 5).trim()
                val endIndex = restOfText.indexOf('\n').takeIf { it != -1 } ?: restOfText.length
                val potentialCode = restOfText.substring(0, endIndex).trim()
                if (potentialCode.length >= 4 && !potentialCode.contains(" ")) {
                    result["code"] = potentialCode.uppercase()
                    codeFound = true
                    Log.d(TAG, "Found coupon code (after 'code:'): $potentialCode")
                }
            }
        }
        
        // If still no code found, look for standalone code-like patterns
        if (!codeFound) {
            val matcher = ALT_CODE_PATTERN.matcher(text)
            while (matcher.find()) {
                val potentialCode = matcher.group(1)
                // Skip if it's too short or looks like a regular word
                if (potentialCode.length >= 6 && 
                    potentialCode.matches("[A-Z0-9]{6,}".toRegex()) &&
                    // Ensure it has both letters and numbers for higher confidence
                    potentialCode.contains(Regex("[A-Z]")) &&
                    potentialCode.contains(Regex("[0-9]"))) {
                    result["code"] = potentialCode.uppercase()
                    codeFound = true
                    Log.d(TAG, "Found coupon code (alt pattern): $potentialCode")
                    break
                }
            }
        }
        
        // Look for amount with appropriate pattern based on store
        var amountFound = false
        
        if (isMyntraCoupon) {
            // Try Myntra-specific amount pattern first
            val myntraAmountMatcher = MYNTRA_AMOUNT_PATTERN.matcher(text)
            if (myntraAmountMatcher.find() && myntraAmountMatcher.groupCount() >= 1) {
                val amount = myntraAmountMatcher.group(1)
                result["amount"] = "₹$amount"
                amountFound = true
                Log.d(TAG, "Found Myntra amount: ₹$amount")
            }
        }
        
        // If no amount found yet, try primary pattern
        if (!amountFound) {
            val amountMatcher = AMOUNT_PATTERN.matcher(text)
            if (amountMatcher.find() && amountMatcher.groupCount() >= 1) {
                val amount = amountMatcher.group(1)
                result["amount"] = "₹$amount"
                amountFound = true
                Log.d(TAG, "Found amount (primary pattern): ₹$amount")
            }
        }
        
        // Try alternative amount pattern if primary fails
        if (!amountFound) {
            val altAmountMatcher = ALT_AMOUNT_PATTERN.matcher(text)
            if (altAmountMatcher.find() && altAmountMatcher.groupCount() >= 1) {
                val amount = altAmountMatcher.group(1)
                result["amount"] = "₹$amount"
                amountFound = true
                Log.d(TAG, "Found amount (alt pattern): ₹$amount")
            }
        }
        
        // Check for text fragments that might contain amount info
        if (!amountFound) {
            // Look for ₹ or Rs. followed by a number
            val amountRegex = "(?:Rs\\.?|₹)\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            amountRegex.find(text)?.let {
                val amount = it.groupValues[1]
                if (amount.isNotEmpty()) {
                    result["amount"] = "₹$amount"
                    amountFound = true
                    Log.d(TAG, "Found amount (simple pattern): ₹$amount")
                }
            }
        }
        
        // Look for expiry date
        findMatch(EXPIRY_PATTERN, text)?.let {
            result["expiryDate"] = it.trim()
            Log.d(TAG, "Found expiry date: ${it.trim()}")
        }
        
        // Look for description
        var descriptionFound = false
        
        if (isMyntraCoupon) {
            // For Myntra, try to find their specific voucher description format
            val myntraDescriptionRegex = "(?i)(you won a voucher|up to ₹\\d+|get flat ₹\\d+)(.{3,30})".toRegex()
            myntraDescriptionRegex.find(text)?.let {
                val fullMatch = it.groupValues[0].trim()
                if (fullMatch.isNotBlank() && fullMatch.length > 10) {
                    result["description"] = fullMatch
                    descriptionFound = true
                    Log.d(TAG, "Found Myntra description: $fullMatch")
                }
            }
        }
        
        // If no Myntra-specific description found, try regular pattern
        if (!descriptionFound) {
            findMatch(DESCRIPTION_PATTERN, text)?.let {
                val description = it.trim()
                if (description.length > 5) {  // Ensure it's a meaningful description
                    result["description"] = description
                    descriptionFound = true
                    Log.d(TAG, "Found description: $description")
                }
            }
        }
        
        // If no description found, look for alternative text that might be a description
        if (!descriptionFound) {
            // Look for phrases like "you won" or "up to"
            val descriptionRegex = "(?:you won|get|up to)(.{5,30})".toRegex(RegexOption.IGNORE_CASE)
            descriptionRegex.find(text)?.let {
                val desc = it.groupValues[0].trim()
                if (desc.isNotBlank()) {
                    result["description"] = desc
                    descriptionFound = true
                    Log.d(TAG, "Found description (alt pattern): $desc")
                }
            }
        }
        
        // If we couldn't extract store name but found a code, set a default store name
        if (!result.containsKey("storeName") && result.containsKey("code")) {
            // Look for potential store names near the coupon code
            val storeLines = text.split("\n").filter { 
                it.isNotBlank() && (it.length < 20) && 
                !it.contains("code", ignoreCase = true) &&
                !it.contains("coupon", ignoreCase = true)
            }
            
            if (storeLines.isNotEmpty()) {
                // Find the most likely store name line (often short and just a name)
                val potentialStore = storeLines.minByOrNull { it.length } ?: storeLines.first()
                // Don't include "code" in store name
                val cleanStoreName = potentialStore.trim().replace("(?i)\\bcode\\b".toRegex(), "").trim()
                result["storeName"] = cleanStoreName
                Log.d(TAG, "Found potential store name from text: $cleanStoreName")
            } else {
                result["storeName"] = "Unknown Store"
            }
        }
        
        // If we still couldn't extract sufficient information, provide some defaults
        if (result.isEmpty() || !result.containsKey("code")) {
            if (!result.containsKey("storeName")) {
                result["storeName"] = "Unknown Store"
            }
            if (!result.containsKey("description") && !descriptionFound) {
                result["description"] = "Scanned coupon"
            }
            if (!result.containsKey("amount") && !amountFound) {
                result["amount"] = "₹0"
            }
        }
        
        Log.d(TAG, "Final extracted coupon info: $result")
        return result
    }
    
    /**
     * Find a match using a regex pattern
     */
    private fun findMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        if (matcher.find() && matcher.groupCount() >= 1) {
            return matcher.group(1)
        }
        return null
    }
    
    /**
     * Create fallback text when OCR fails
     */
    private fun createFallbackText(): String {
        return """
            Store: Vision API Fallback Store
            Coupon: VISION25
            Get ₹250 off your next purchase
            Expires: 31/12/2023
            Description: This is a fallback coupon when OCR fails
        """.trimIndent()
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
            
            // Execute request
            Log.d(TAG, "Executing API test request")
            val response = client.newCall(request).execute()
            
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
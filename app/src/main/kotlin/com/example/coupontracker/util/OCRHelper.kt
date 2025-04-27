package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Helper class for OCR processing using ML Kit
 */
class OCRHelper {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    companion object {
        private const val TAG = "OCRHelper"
        
        // Improved regex patterns for extracting coupon information
        private val STORE_PATTERN = Pattern.compile("(?i)(store|shop|merchant|retailer|brand|company|from)\\s*:?\\s*([A-Za-z0-9\\s&.'-]+)")
        private val CODE_PATTERN = Pattern.compile("(?i)(code|coupon|promo|voucher|redeem|use)\\s*:?\\s*([A-Za-z0-9\\-_]+)")
        private val AMOUNT_PATTERN = Pattern.compile("(?i)(\\$?\\d+(\\.\\d{1,2})?|\\d+(\\.\\d{1,2})?)\\s*%?\\s*(off|cashback|discount|reward|save)")
        private val EXPIRY_PATTERN = Pattern.compile("(?i)(exp|expires|expiry|valid until|valid through|use by)\\s*:?\\s*(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})")
        private val DESCRIPTION_PATTERN = Pattern.compile("(?i)(description|details|offer|deal|get|save)\\s*:?\\s*([^\\n\\r.]+)")
    }
    
    /**
     * Process an image from URI
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): Text {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                processImageFromBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from URI", e)
                throw e
            }
        }
    }
    
    /**
     * Process an image from Bitmap
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): Text {
        return withContext(Dispatchers.IO) {
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                recognizeText(image)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing image from bitmap", e)
                throw e
            }
        }
    }
    
    /**
     * Recognize text in an image using ML Kit
     */
    private suspend fun recognizeText(image: InputImage): Text = suspendCancellableCoroutine { continuation ->
        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            }
            .addOnFailureListener { e ->
                if (continuation.isActive) {
                    Log.e(TAG, "Text recognition failed", e)
                    continuation.resumeWithException(e)
                }
            }
    }
    
    /**
     * Extract coupon information from recognized text
     */
    fun extractCouponInfo(text: Text): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val fullText = text.text
        
        Log.d(TAG, "Extracting info from text: $fullText")
        
        // Try to extract information from structured text blocks first
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text
                
                // Check for store name in the first few lines
                if (result["storeName"] == null && block == text.textBlocks.firstOrNull()) {
                    if (lineText.length > 3 && !lineText.contains("code", ignoreCase = true) && 
                        !lineText.contains("coupon", ignoreCase = true)) {
                        result["storeName"] = lineText.trim()
                    }
                }
                
                // Look for coupon code (usually all caps with numbers)
                if (result["code"] == null) {
                    val codeMatch = Regex("[A-Z0-9]{4,12}").find(lineText)
                    if (codeMatch != null) {
                        result["code"] = codeMatch.value
                    }
                }
                
                // Look for amount/discount
                if (result["amount"] == null) {
                    val amountMatch = Regex("\\$?(\\d+(\\.\\d{1,2})?)").find(lineText)
                    if (amountMatch != null && lineText.contains(Regex("(off|discount|save|cashback)", RegexOption.IGNORE_CASE))) {
                        result["amount"] = amountMatch.groupValues[1]
                    }
                }
                
                // Look for expiry date
                if (result["expiryDate"] == null) {
                    val dateMatch = Regex("\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}").find(lineText)
                    if (dateMatch != null && lineText.contains(Regex("(exp|valid|until)", RegexOption.IGNORE_CASE))) {
                        result["expiryDate"] = dateMatch.value
                    }
                }
            }
        }
        
        // Fall back to regex pattern matching on the full text
        if (!result.containsKey("storeName")) {
            findMatch(STORE_PATTERN, fullText)?.let {
                result["storeName"] = it.trim()
            }
        }
        
        if (!result.containsKey("code")) {
            findMatch(CODE_PATTERN, fullText)?.let {
                result["code"] = it.trim()
            }
        }
        
        if (!result.containsKey("amount")) {
            findMatch(AMOUNT_PATTERN, fullText)?.let {
                result["amount"] = it.trim()
            }
        }
        
        if (!result.containsKey("expiryDate")) {
            findMatch(EXPIRY_PATTERN, fullText)?.let {
                result["expiryDate"] = it.trim()
            }
        }
        
        // Extract description
        findMatch(DESCRIPTION_PATTERN, fullText)?.let {
            result["description"] = it.trim()
        }
        
        // If no description was found, use the first line or part of the text
        if (!result.containsKey("description") && fullText.isNotEmpty()) {
            val firstLine = fullText.split("\n").firstOrNull()?.trim() ?: ""
            if (firstLine.length > 10) {
                result["description"] = firstLine
            } else {
                // Use the longest text block as description
                val longestBlock = text.textBlocks.maxByOrNull { it.text.length }
                longestBlock?.let { block ->
                    if (block.text.length > 10) {
                        result["description"] = block.text.trim()
                    }
                }
            }
        }
        
        // If we still don't have a code, try to find any alphanumeric sequence that looks like a code
        if (!result.containsKey("code")) {
            val codeMatch = Regex("[A-Za-z0-9]{4,12}").find(fullText)
            codeMatch?.let {
                result["code"] = it.value
            }
        }
        
        return result
    }
    
    /**
     * Find a match using a regex pattern
     */
    private fun findMatch(pattern: Pattern, text: String): String? {
        val matcher = pattern.matcher(text)
        return if (matcher.find() && matcher.groupCount() >= 2) {
            matcher.group(2)
        } else {
            null
        }
    }
} 
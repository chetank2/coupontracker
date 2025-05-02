package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import com.example.coupontracker.data.model.Coupon
import java.util.Date

/**
 * Recognizes coupon elements using patterns learned from training data
 */
class CouponPatternRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "CouponPatternRecognizer"
        private const val PATTERNS_FILE = "coupon_model/coupon_patterns.txt"
    }
    
    // Tesseract OCR helper
    private val tesseractOCRHelper = TesseractOCRHelper(context)
    
    // Patterns for different coupon elements
    private val patterns: Map<String, List<Rect>> by lazy { loadPatterns() }
    
    // Initialization flag
    private val isInitialized = AtomicBoolean(false)
    
    /**
     * Initialize the recognizer
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized.get()) {
            return@withContext true
        }
        
        try {
            // Initialize Tesseract
            val success = tesseractOCRHelper.initialize()
            
            if (success) {
                Log.d(TAG, "CouponPatternRecognizer initialized successfully")
                isInitialized.set(true)
            } else {
                Log.e(TAG, "Failed to initialize CouponPatternRecognizer")
            }
            
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CouponPatternRecognizer", e)
            return@withContext false
        }
    }
    
    /**
     * Load patterns from the patterns file
     */
    private fun loadPatterns(): Map<String, List<Rect>> {
        val result = mutableMapOf<String, MutableList<Rect>>()
        
        try {
            val inputStream = context.assets.open(PATTERNS_FILE)
            val reader = inputStream.bufferedReader()
            
            reader.lines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) {
                    // Skip blank lines and comments
                    return@forEach
                }
                
                if (line.contains(":")) {
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        val type = parts[0]
                        val coords = parts[1].split(",")
                        if (coords.size == 4) {
                            val rect = Rect(
                                coords[0].toInt(),
                                coords[1].toInt(),
                                coords[2].toInt(),
                                coords[3].toInt()
                            )
                            
                            if (!result.containsKey(type)) {
                                result[type] = mutableListOf()
                            }
                            result[type]!!.add(rect)
                        }
                    }
                }
            }
            
            reader.close()
            inputStream.close()
            
            Log.d(TAG, "Loaded ${result.size} pattern types with ${result.values.sumOf { it.size }} total patterns")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading patterns", e)
        }
        
        return result
    }
    
    /**
     * Recognize coupon elements in an image
     */
    suspend fun recognizeElements(bitmap: Bitmap): Map<String, String> = withContext(Dispatchers.IO) {
        if (!isInitialized.get() && !initialize()) {
            return@withContext emptyMap<String, String>()
        }
        
        val result = mutableMapOf<String, String>()
        
        try {
            // Scale the patterns to match the bitmap dimensions
            val scaledPatterns = scalePatterns(bitmap.width, bitmap.height)
            
            // Process each pattern type
            for ((type, rects) in scaledPatterns) {
                for (rect in rects) {
                    // Ensure rect is within bitmap bounds
                    val safeRect = ensureRectWithinBounds(rect, bitmap.width, bitmap.height)
                    
                    // Extract region from bitmap
                    val regionBitmap = Bitmap.createBitmap(
                        bitmap,
                        safeRect.left,
                        safeRect.top,
                        safeRect.width(),
                        safeRect.height()
                    )
                    
                    // Process with Tesseract
                    val text = tesseractOCRHelper.processImageFromBitmap(regionBitmap)
                    
                    // If we got text and this type isn't in the result yet, add it
                    if (text.isNotBlank() && !result.containsKey(type)) {
                        result[type] = text
                        Log.d(TAG, "Recognized $type: $text")
                    }
                }
            }
            
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing elements", e)
            return@withContext emptyMap<String, String>()
        }
    }
    
    /**
     * Scale patterns to match the bitmap dimensions
     */
    private fun scalePatterns(width: Int, height: Int): Map<String, List<Rect>> {
        val result = mutableMapOf<String, MutableList<Rect>>()
        
        // Reference dimensions from training data
        val refWidth = 1080
        val refHeight = 1920
        
        // Scale factors
        val scaleX = width.toFloat() / refWidth
        val scaleY = height.toFloat() / refHeight
        
        for ((type, rects) in patterns) {
            result[type] = mutableListOf()
            
            for (rect in rects) {
                val scaledRect = Rect(
                    (rect.left * scaleX).toInt(),
                    (rect.top * scaleY).toInt(),
                    (rect.right * scaleX).toInt(),
                    (rect.bottom * scaleY).toInt()
                )
                
                result[type]!!.add(scaledRect)
            }
        }
        
        return result
    }
    
    /**
     * Ensure a rectangle is within the bounds of the bitmap
     */
    private fun ensureRectWithinBounds(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)
        
        return Rect(left, top, right, bottom)
    }
    
    /**
     * Convert recognized elements to CouponInfo
     */
    fun convertToCouponInfo(elements: Map<String, String>): CouponInfo {
        val storeName = elements["store"] ?: ""
        val description = elements["description"] ?: ""
        val expiryDateStr = elements["expiry"]
        val redeemCode = elements["code"]
        val amountStr = elements["amount"]
        
        // Parse expiry date
        val expiryDate = if (expiryDateStr != null) {
            parseExpiryDate(expiryDateStr)
        } else {
            null
        }
        
        // Parse cashback amount
        val cashbackAmount = if (amountStr != null) {
            parseCashbackAmount(amountStr)
        } else {
            null
        }
        
        // Determine discount type
        val discountType = if (amountStr != null && amountStr.contains("%")) {
            "PERCENTAGE"
        } else {
            "AMOUNT"
        }
        
        return CouponInfo(
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode,
            discountType = discountType
        )
    }
    
    /**
     * Parse expiry date from string
     */
    private fun parseExpiryDate(dateStr: String): Date? {
        val textExtractor = TextExtractor()
        return textExtractor.parseExpiryDate(dateStr)
    }
    
    /**
     * Parse cashback amount from string
     */
    private fun parseCashbackAmount(amountStr: String): Double? {
        return try {
            when {
                // Percentage discount
                amountStr.contains("%") -> {
                    val percentStr = amountStr.replace("[^0-9.]".toRegex(), "")
                    percentStr.toDoubleOrNull()
                }
                // Fixed amount discount
                amountStr.contains("₹") || amountStr.contains("Rs") -> {
                    val amountNumStr = amountStr.replace("[^0-9.]".toRegex(), "")
                    amountNumStr.toDoubleOrNull()
                }
                // Try to parse as a number
                else -> {
                    amountStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cashback amount: $amountStr", e)
            null
        }
    }
}

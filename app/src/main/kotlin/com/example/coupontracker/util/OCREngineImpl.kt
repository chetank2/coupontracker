package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.util.CouponInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main OCR engine that orchestrates the OCR process using multiple OCR implementations
 */
class OCREngineImpl(private val context: Context) {
    companion object {
        private const val TAG = "OCREngineImpl"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)

    // Primary OCR engine using the trained model
    private val modelBasedOCRService = ModelBasedOCRService(context)

    // Text extraction helper
    private val textExtractor = TextExtractor()

    // Track if model-based OCR is initialized
    private val modelInitialized = AtomicBoolean(false)

    init {
        // Initialize model-based OCR service in a background thread
        MainScope().launch {
            try {
                modelBasedOCRService.initialize()
                modelInitialized.set(true)
                Log.d(TAG, "Model-based OCR service initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing model-based OCR service", e)
                modelInitialized.set(false)
            }
        }
    }

    data class OCRPayload(
        val rawText: String,
        val couponInfo: CouponInfo,
        val fieldMap: Map<String, String>
    )

    /**
     * Process an image and extract structured coupon info along with the synthesized text
     */
    suspend fun processImageDetailed(imageUri: Uri): OCRPayload {
        return withContext(Dispatchers.IO) {
            val bitmap = loadBitmapFromUri(imageUri)
                ?: throw OCRProcessingException("Failed to load bitmap from URI")

            processImageDetailed(bitmap)
        }
    }

    /**
     * Process a bitmap and extract structured coupon info along with the synthesized text
     */
    suspend fun processImageDetailed(bitmap: Bitmap): OCRPayload {
        return withContext(Dispatchers.IO) {
            if (!modelInitialized.get()) {
                throw OCRProcessingException("Model-based OCR not initialized")
            }

            Log.d(TAG, "Processing bitmap with Model-based OCR")
            val couponInfo = modelBasedOCRService.processCouponImage(bitmap)
            val text = convertCouponInfoToText(couponInfo)
            val fieldMap = convertCouponInfoToMap(couponInfo)
            OCRPayload(text, couponInfo, fieldMap)
        }
    }

    suspend fun processImage(imageUri: Uri): String = processImageDetailed(imageUri).rawText

    suspend fun processImage(bitmap: Bitmap): String = processImageDetailed(bitmap).rawText

    /**
     * Convert CouponInfo to text format for compatibility with existing code
     */
    private fun convertCouponInfoToText(couponInfo: CouponInfo): String {
        val sb = StringBuilder()

        // Add store name
        if (couponInfo.storeName.isNotBlank()) {
            sb.appendLine("Store: ${couponInfo.storeName}")
        }

        // Add coupon code
        if (!couponInfo.redeemCode.isNullOrBlank()) {
            sb.appendLine("Coupon: ${couponInfo.redeemCode}")
        }

        // Add description
        if (couponInfo.description.isNotBlank()) {
            sb.appendLine(couponInfo.description)
        }

        // Add expiry date
        if (couponInfo.expiryDate != null) {
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            sb.appendLine("Expires: ${sdf.format(couponInfo.expiryDate)}")
        }

        // Add amount
        if (couponInfo.cashbackAmount != null) {
            sb.appendLine("Amount: ₹${couponInfo.cashbackAmount}")
        }

        return sb.toString()
    }

    /**
     * Load bitmap from URI
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading bitmap from URI", e)
                null
            }
        }
    }

    /**
     * Extract coupon info from recognized text
     * This extracts coupon information including amount in rupees (₹)
     */
    suspend fun extractCouponInfo(text: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            val normalizedText = text.trim()
            if (normalizedText.isBlank()) {
                throw OCRProcessingException("OCR text was empty")
            }

            val couponInfo = textExtractor.extractCouponInfoSync(normalizedText)
            if (couponInfo.isValid()) {
                return@withContext convertCouponInfoToMap(couponInfo)
            }

            val heuristicResult = mutableMapOf<String, String>()

            if (couponInfo.storeName.isNotBlank()) {
                heuristicResult["storeName"] = couponInfo.storeName
            }

            if (couponInfo.description.isNotBlank()) {
                heuristicResult["description"] = couponInfo.description
            }

            couponInfo.cashbackAmount?.takeIf { it > 0 }?.let {
                heuristicResult["amount"] = "₹$it"
            }

            extractBasicCode(normalizedText)?.let { heuristicResult["code"] = it }

            if (heuristicResult.isEmpty()) {
                throw OCRProcessingException("Unable to derive structured coupon details from OCR text")
            }

            heuristicResult
        }
    }

    /**
     * Convert CouponInfo to MutableMap<String, String> for compatibility with existing code
     */
    private fun convertCouponInfoToMap(couponInfo: CouponInfo): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()

        // Add store name
        if (couponInfo.storeName.isNotBlank()) {
            result["storeName"] = couponInfo.storeName
        }

        // Add coupon code
        if (!couponInfo.redeemCode.isNullOrBlank()) {
            result["code"] = couponInfo.redeemCode
        }

        // Add description
        if (couponInfo.description.isNotBlank()) {
            result["description"] = couponInfo.description
        }

        // Add expiry date
        if (couponInfo.expiryDate != null) {
            val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            result["expiryDate"] = sdf.format(couponInfo.expiryDate)
        }

        // Add amount
        if (couponInfo.cashbackAmount != null) {
            result["amount"] = "₹${couponInfo.cashbackAmount}"
        }

        return result
    }

    /**
     * Simple method to extract a basic code from text
     */
    private fun extractBasicCode(text: String): String? {
        val codePattern = Regex("""(?i)(code\s*[:\-]?\s*)([A-Z0-9]{4,})""")
        val directMatch = codePattern.find(text)
        if (directMatch != null && directMatch.groupValues.size > 2) {
            return directMatch.groupValues[2].uppercase()
        }

        val fallbackPattern = Regex("[A-Z0-9]{6,}")
        return fallbackPattern.find(text)?.value?.uppercase()
    }
}

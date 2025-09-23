package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.model.CouponData
import com.example.coupontracker.util.ConfidenceLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advanced OCR Pipeline for coupon extraction with multiple OCR engines,
 * preprocessing, and structured data extraction
 */
class AdvancedOCRPipeline @JvmOverloads constructor(
    private val context: Context,
    private val modelBasedOCRService: ModelBasedOCRService = ModelBasedOCRService(context),
    private val imagePreprocessor: ImagePreprocessor = ImagePreprocessor()
) {
    companion object {
        private const val TAG = "AdvancedOCRPipeline"
    }

    // Components

    /**
     * Process an image URI and extract structured coupon data
     * @param imageUri URI of the image to process
     * @return Extracted coupon data
     */
    suspend fun processCouponImage(imageUri: Uri): CouponData {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon image: $imageUri")

                // Load bitmap
                val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
                    ?: throw Exception("Failed to load image")

                processCouponImage(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon image from URI", e)
                createFallbackCoupon("Error: ${e.message}")
            }
        }
    }

    /**
     * Process a bitmap and extract structured coupon data
     * Uses the trained model for coupon recognition
     * @param bitmap Bitmap to process
     * @return Extracted coupon data
     */
    suspend fun processCouponImage(bitmap: Bitmap): CouponData = coroutineScope {
        try {
            Log.d(TAG, "Processing coupon bitmap: ${bitmap.width}x${bitmap.height}")

            // 1. Preprocess image
            val processedBitmap = imagePreprocessor.preprocess(bitmap)
            Log.d(TAG, "Preprocessed image: ${processedBitmap.width}x${processedBitmap.height}")

            // 2. Process with model-based OCR service
            val couponInfo = modelBasedOCRService.processCouponImage(processedBitmap)
            Log.d(TAG, "Processed with model-based OCR: $couponInfo")

            // 3. Convert CouponInfo to CouponData
            val couponData = couponInfo.toCouponData()

            if (!couponData.isValid()) {
                throw OCRProcessingException("Extracted coupon data failed validation")
            }

            couponData
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupon image", e)
            createFallbackCoupon("Error: ${e.message}")
        }
    }

    /**
     * Merge two coupon data objects, preferring the first one
     */
    /**
     * Create a fallback coupon when extraction fails
     */
    private fun createFallbackCoupon(reason: String): CouponData {
        return CouponData(
            merchantName = "Extraction Failed",
            code = "RETRY",
            amount = "₹0",
            expiryDate = null,
            description = "Failed to extract coupon: $reason",
            terms = null,
            extractionScore = 0
        )
    }

    /**
     * Format a Date object to a string representation for CouponData
     */
    private fun formatExpiryDate(date: Date?): String? {
        if (date == null) return null

        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        return sdf.format(date)
    }

    private fun CouponInfo.toCouponData(): CouponData {
        val extractionScore = if (fieldConfidences.isNotEmpty()) {
            fieldConfidences.mapValues { (_, value) -> value.toConfidenceLevel() }.toMutableMap()
        } else {
            mutableMapOf<String, ConfidenceLevel>().apply {
                redeemCode?.takeIf { it.isNotBlank() }?.let { put("code", ConfidenceLevel.HIGH) }
                cashbackAmount?.takeIf { it > 0 }?.let { put("amount", ConfidenceLevel.MEDIUM) }
            }
        }

        return CouponData(
            merchantName = storeName,
            code = redeemCode,
            amount = cashbackAmount?.toString(),
            expiryDate = formatExpiryDate(expiryDate),
            description = description,
            terms = null,
            confidenceLevels = extractionScore,
            extractionScore = if (extractionScore.isEmpty()) 0 else 80
        )
    }

    private fun Float.toConfidenceLevel(): ConfidenceLevel = when {
        this >= 0.85f -> ConfidenceLevel.HIGH
        this >= 0.6f -> ConfidenceLevel.MEDIUM
        this >= 0.4f -> ConfidenceLevel.LOW
        else -> ConfidenceLevel.SYNTHETIC
    }
}

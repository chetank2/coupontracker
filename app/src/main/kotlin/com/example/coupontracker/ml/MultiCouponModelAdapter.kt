package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.CouponField
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.util.ImageUtils
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Adapter for the multi-coupon recognition model
 *
 * This class provides an interface for detecting and processing multiple coupons
 * from a single image.
 */
class MultiCouponModelAdapter(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var modelConfig: JSONObject? = null
    private var inputSize: Int = 640
    private var supportsMultipleCoupons: Boolean = false
    private var detectionEnabled: Boolean = false

    companion object {
        private const val TAG = "MultiCouponModelAdapter"
        private const val MODEL_CONFIG_FILE = "models/coupon_model_config.json"
        private const val MODEL_FILE = "models/coupon_model.tflite"
    }

    init {
        try {
            // Load model configuration
            val configJson = context.assets.open(MODEL_CONFIG_FILE).bufferedReader().use { it.readText() }
            modelConfig = JSONObject(configJson)

            // Check if model supports multiple coupons
            supportsMultipleCoupons = modelConfig?.optBoolean("supports_multiple_coupons", false) ?: false
            detectionEnabled = modelConfig?.optBoolean("detection_enabled", false) ?: false

            // Get input size
            inputSize = modelConfig?.optInt("input_size", 640) ?: 640

            // Load model
            val modelFile = FileUtil.loadMappedFile(context, MODEL_FILE)
            interpreter = Interpreter(modelFile)

            Log.d(TAG, "Model loaded successfully. Supports multiple coupons: $supportsMultipleCoupons")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing model: ${e.message}")
        }
    }

    /**
     * Process an image containing one or more coupons
     *
     * @param bitmap The input image
     * @return List of detected coupons with extracted fields
     */
    fun processCoupons(bitmap: Bitmap): List<CouponResult> {
        if (interpreter == null) {
            Log.e(TAG, "Model not initialized")
            return emptyList()
        }

        try {
            // If model supports multiple coupons and detection is enabled
            if (supportsMultipleCoupons && detectionEnabled) {
                // Detect coupons
                val couponRegions = detectCoupons(bitmap)
                Log.d(TAG, "Detected ${couponRegions.size} coupons")

                // Process each coupon
                return couponRegions.mapIndexed { index, region ->
                    // Extract coupon image
                    val couponBitmap = extractCouponImage(bitmap, region)

                    // Extract fields
                    val fields = extractFields(couponBitmap)

                    // Create result
                    CouponResult(
                        couponIndex = index + 1,
                        region = region,
                        image = couponBitmap,
                        fields = fields
                    )
                }
            } else {
                // Process as a single coupon
                val fields = extractFields(bitmap)

                // Create result
                return listOf(
                    CouponResult(
                        couponIndex = 1,
                        region = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                        image = bitmap,
                        fields = fields
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupons: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Detect coupons in an image
     *
     * @param bitmap The input image
     * @return List of regions (RectF) for detected coupons
     */
    private fun detectCoupons(bitmap: Bitmap): List<RectF> {
        // This is a simplified implementation
        // In a real implementation, this would use the model to detect coupons

        // For now, just return the whole image as a single coupon
        return listOf(RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
    }

    /**
     * Extract a coupon image from the original image
     *
     * @param bitmap The original image
     * @param region The region of the coupon
     * @return The extracted coupon image
     */
    private fun extractCouponImage(bitmap: Bitmap, region: RectF): Bitmap {
        val x = region.left.toInt()
        val y = region.top.toInt()
        val width = region.width().toInt()
        val height = region.height().toInt()

        // Ensure coordinates are within bounds
        val safeX = max(0, x)
        val safeY = max(0, y)
        val safeWidth = min(bitmap.width - safeX, width)
        val safeHeight = min(bitmap.height - safeY, height)

        // Extract the region
        return Bitmap.createBitmap(bitmap, safeX, safeY, safeWidth, safeHeight)
    }

    /**
     * Extract fields from a coupon image
     *
     * @param bitmap The coupon image
     * @return Map of field types to field values
     */
    private fun extractFields(bitmap: Bitmap): Map<FieldType, String> {
        // This is a simplified implementation
        // In a real implementation, this would use the model to extract fields

        // For now, just return dummy data
        return mapOf(
            FieldType.STORE_NAME to "Sample Store",
            FieldType.COUPON_CODE to "SAMPLE123",
            FieldType.AMOUNT to "50% OFF",
            FieldType.EXPIRY_DATE to "2025-12-31",
            FieldType.DESCRIPTION to "Sample coupon description"
        )
    }

    /**
     * Convert a coupon result to a Coupon object
     *
     * @param result The coupon result
     * @return The Coupon object
     */
    fun toCoupon(result: CouponResult): Coupon {
        // Parse amount from string to double
        val amountStr = result.fields[FieldType.AMOUNT] ?: "0"
        val amount = try {
            // Extract numeric value from string like "50% OFF" or "₹100"
            val numericValue = amountStr.replace(Regex("[^0-9.]"), "")
            if (numericValue.isNotEmpty()) numericValue.toDouble() else 0.0
        } catch (e: Exception) {
            0.0
        }

        // Parse expiry date
        val expiryDateStr = result.fields[FieldType.EXPIRY_DATE] ?: ""
        val expiryDate = try {
            // Try to parse date in format "yyyy-MM-dd"
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.parse(expiryDateStr) ?: Date()
        } catch (e: Exception) {
            Date() // Default to current date if parsing fails
        }

        // Create coupon
        return Coupon(
            id = 0, // Will be assigned by Room
            storeName = result.fields[FieldType.STORE_NAME] ?: "",
            description = result.fields[FieldType.DESCRIPTION] ?: "",
            expiryDate = expiryDate,
            cashbackAmount = amount,
            redeemCode = result.fields[FieldType.COUPON_CODE],
            imageUri = "", // Will be set by the app
            createdAt = Date(),
            updatedAt = Date()
        )
    }

    /**
     * Release resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    /**
     * Result of processing a coupon
     */
    data class CouponResult(
        val couponIndex: Int,
        val region: RectF,
        val image: Bitmap,
        val fields: Map<FieldType, String>
    )
}

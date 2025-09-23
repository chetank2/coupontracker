package com.example.coupontracker.ml

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.ImageLoaderUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper class for batch processing of coupons
 */
class BatchCouponProcessor(private val context: Context) {
    private val modelAdapter = MultiCouponModelAdapter(context)
    
    companion object {
        private const val TAG = "BatchCouponProcessor"
    }
    
    /**
     * Process an image containing one or more coupons
     *
     * @param imageUri URI of the image to process
     * @return List of detected coupons
     */
    suspend fun processImage(imageUri: Uri): List<Coupon> = withContext(Dispatchers.IO) {
        try {
            // Load the image
            val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
                ?: return@withContext emptyList()
            
            // Process coupons
            val results = modelAdapter.processCoupons(bitmap)
            Log.d(TAG, "Processed ${results.size} coupons")
            
            // Convert results to coupons
            val coupons = results.map { result ->
                // Create coupon
                val coupon = modelAdapter.toCoupon(result)
                
                // Save coupon image
                val imagePath = saveCouponImage(result.image, result.couponIndex)
                
                // Set image URI
                coupon.copy(imageUri = imagePath)
            }
            
            return@withContext coupons
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image: ${e.message}")
            return@withContext emptyList()
        }
    }
    
    /**
     * Save a coupon image to the app's files directory
     *
     * @param bitmap The coupon image
     * @param index The coupon index
     * @return The path to the saved image
     */
    private fun saveCouponImage(bitmap: Bitmap, index: Int): String {
        val timestamp = System.currentTimeMillis()
        val filename = "coupon_${timestamp}_${index}.jpg"
        val file = File(context.filesDir, filename)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        
        return file.absolutePath
    }
    
    /**
     * Release resources
     */
    fun close() {
        modelAdapter.close()
    }
}

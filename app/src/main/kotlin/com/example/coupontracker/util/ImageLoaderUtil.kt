package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/**
 * Utility class for loading images from various sources
 */
object ImageLoaderUtil {
    private const val TAG = "ImageLoaderUtil"
    
    /**
     * Load a bitmap from a URI
     * @param context The context
     * @param uri The URI to load from
     * @return The loaded bitmap or null if loading failed
     */
    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Use modern approach for Android P and above
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                // Use legacy approach for older Android versions
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }
    
    /**
     * Resize a bitmap to the specified dimensions
     * @param bitmap The bitmap to resize
     * @param maxWidth The maximum width
     * @param maxHeight The maximum height
     * @param filter Whether to apply filtering
     * @return The resized bitmap
     */
    fun resizeBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int, filter: Boolean = true): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Calculate the target dimensions while maintaining aspect ratio
        val ratio = Math.min(maxWidth.toFloat() / width, maxHeight.toFloat() / height)
        val targetWidth = (width * ratio).toInt()
        val targetHeight = (height * ratio).toInt()
        
        // Only resize if necessary
        if (targetWidth != width || targetHeight != height) {
            return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, filter)
        }
        
        return bitmap
    }
}

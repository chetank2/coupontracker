package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Utility class for optimizing images before processing
 */
class ImageOptimizer(private val context: Context) {
    
    companion object {
        private const val TAG = "ImageOptimizer"
        private const val MAX_IMAGE_DIMENSION = 1920 // Maximum dimension for processing
        private const val COMPRESSION_QUALITY = 85 // JPEG compression quality (0-100)
        private const val CACHE_DIR_NAME = "optimized_images"
    }
    
    /**
     * Optimize an image from a URI
     * @param imageUri The URI of the image to optimize
     * @return The URI of the optimized image
     */
    suspend fun optimizeImage(imageUri: Uri): Uri = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Optimizing image: $imageUri")
            
            // Create cache directory if it doesn't exist
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            // Create output file
            val outputFile = File(cacheDir, "optimized_${System.currentTimeMillis()}.jpg")
            
            // Load bitmap with sampling to reduce memory usage
            val bitmap = loadScaledBitmap(imageUri)
            
            // Compress and save the bitmap
            val compressedBitmap = compressBitmap(bitmap)
            
            // Save to file
            FileOutputStream(outputFile).use { out ->
                compressedBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, out)
            }
            
            // Clean up
            bitmap.recycle()
            compressedBitmap.recycle()
            
            // Return URI of optimized image
            return@withContext Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error optimizing image", e)
            // Return original URI if optimization fails
            return@withContext imageUri
        }
    }
    
    /**
     * Load a bitmap from a URI with appropriate scaling
     */
    private fun loadScaledBitmap(imageUri: Uri): Bitmap {
        // First, decode bounds to determine image size
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        
        context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        
        // Calculate appropriate sample size
        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight)
        
        // Load bitmap with sampling
        val loadOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        
        var bitmap: Bitmap? = null
        context.contentResolver.openInputStream(imageUri)?.use { inputStream ->
            bitmap = BitmapFactory.decodeStream(inputStream, null, loadOptions)
            
            // Apply rotation if needed
            bitmap = rotateImageIfRequired(bitmap!!, inputStream)
        }
        
        return bitmap ?: throw IllegalStateException("Failed to load bitmap from $imageUri")
    }
    
    /**
     * Calculate appropriate sample size for loading bitmap
     */
    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width
            while ((halfWidth / sampleSize) >= MAX_IMAGE_DIMENSION || 
                   (halfHeight / sampleSize) >= MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }
        }
        
        return sampleSize
    }
    
    /**
     * Rotate image according to EXIF orientation
     */
    private fun rotateImageIfRequired(bitmap: Bitmap, inputStream: InputStream): Bitmap {
        inputStream.reset() // Reset stream to beginning
        
        try {
            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }
            
            return Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading EXIF data", e)
            return bitmap
        }
    }
    
    /**
     * Compress bitmap to reduce file size
     */
    private fun compressBitmap(bitmap: Bitmap): Bitmap {
        // If bitmap is already small enough, return it as is
        if (bitmap.width <= MAX_IMAGE_DIMENSION && bitmap.height <= MAX_IMAGE_DIMENSION) {
            return bitmap
        }
        
        // Calculate new dimensions while maintaining aspect ratio
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val newWidth: Int
        val newHeight: Int
        
        if (bitmap.width > bitmap.height) {
            newWidth = MAX_IMAGE_DIMENSION
            newHeight = (newWidth / ratio).toInt()
        } else {
            newHeight = MAX_IMAGE_DIMENSION
            newWidth = (newHeight * ratio).toInt()
        }
        
        // Scale down the bitmap
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        
        // Compress to JPEG
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream)
        
        // Convert back to bitmap
        val compressedBitmap = BitmapFactory.decodeByteArray(
            outputStream.toByteArray(), 0, outputStream.size()
        )
        
        // Clean up if needed
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        return compressedBitmap ?: bitmap
    }
    
    /**
     * Clean up cached optimized images
     */
    fun clearCache() {
        try {
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME)
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache", e)
        }
    }
}

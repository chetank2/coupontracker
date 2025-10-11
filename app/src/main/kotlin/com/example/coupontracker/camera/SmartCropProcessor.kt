package com.example.coupontracker.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Intelligent image cropping service that:
 * - Detects document boundaries using text regions
 * - Auto-rotates skewed images
 * - Crops to optimal aspect ratio
 * - Enhances contrast for better OCR
 */
class SmartCropProcessor @Inject constructor() {
    
    companion object {
        private const val TAG = "SmartCrop"
        private const val MAX_DIMENSION = 1920
        private const val MIN_CROP_RATIO = 0.3f // Minimum crop area must be 30% of original
    }
    
    /**
     * Automatically crop image to text region with smart boundaries
     */
    fun cropToTextRegion(
        bitmap: Bitmap,
        textBlocks: List<DetectedTextBlock>
    ): CropResult {
        if (textBlocks.isEmpty()) {
            return CropResult.NoCropNeeded(bitmap)
        }
        
        // Calculate bounding box
        val cropRect = calculateCropBounds(bitmap, textBlocks)
        
        // Validate crop region
        val cropArea = cropRect.width() * cropRect.height()
        val totalArea = bitmap.width * bitmap.height
        val cropRatio = cropArea.toFloat() / totalArea
        
        if (cropRatio < MIN_CROP_RATIO) {
            Log.w(TAG, "Crop region too small ($cropRatio), skipping")
            return CropResult.CropTooSmall(bitmap)
        }
        
        // Detect and correct rotation
        val rotation = detectRotation(textBlocks)
        val rotatedBitmap = if (abs(rotation) > 2.0) {
            rotateBitmap(bitmap, rotation)
        } else {
            bitmap
        }
        
        // Apply crop
        val croppedBitmap = try {
            Bitmap.createBitmap(
                rotatedBitmap,
                maxOf(0, cropRect.left),
                maxOf(0, cropRect.top),
                minOf(cropRect.width(), rotatedBitmap.width - cropRect.left),
                minOf(cropRect.height(), rotatedBitmap.height - cropRect.top)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Crop failed", e)
            return CropResult.CropFailed(bitmap, e.message ?: "Unknown error")
        }
        
        // Downscale if too large
        val finalBitmap = downscaleIfNeeded(croppedBitmap)
        
        return CropResult.Success(
            bitmap = finalBitmap,
            originalSize = Pair(bitmap.width, bitmap.height),
            croppedSize = Pair(finalBitmap.width, finalBitmap.height),
            rotation = rotation,
            cropRatio = cropRatio
        )
    }
    
    /**
     * Calculate optimal crop bounds from text blocks
     */
    private fun calculateCropBounds(bitmap: Bitmap, textBlocks: List<DetectedTextBlock>): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        
        textBlocks.forEach { block ->
            val box = block.boundingBox
            left = minOf(left, box.left)
            top = minOf(top, box.top)
            right = maxOf(right, box.right)
            bottom = maxOf(bottom, box.bottom)
        }
        
        // Add smart padding based on image size
        val width = right - left
        val height = bottom - top
        val padding = maxOf(width, height) / 10
        
        return Rect(
            maxOf(0, left - padding),
            maxOf(0, top - padding),
            minOf(bitmap.width, right + padding),
            minOf(bitmap.height, bottom + padding)
        )
    }
    
    /**
     * Detect rotation angle from text block alignment
     */
    private fun detectRotation(textBlocks: List<DetectedTextBlock>): Double {
        if (textBlocks.size < 2) return 0.0
        
        // Calculate average angle from text block edges
        val angles = mutableListOf<Double>()
        
        for (i in 0 until minOf(textBlocks.size - 1, 5)) {
            val box1 = textBlocks[i].boundingBox
            val box2 = textBlocks[i + 1].boundingBox
            
            val dx = (box2.centerX() - box1.centerX()).toDouble()
            val dy = (box2.centerY() - box1.centerY()).toDouble()
            
            if (abs(dx) > 10) { // Only consider horizontal text
                val angle = Math.toDegrees(atan2(dy, dx))
                angles.add(angle)
            }
        }
        
        return if (angles.isNotEmpty()) {
            angles.average()
        } else {
            0.0
        }
    }
    
    /**
     * Rotate bitmap by given angle
     */
    private fun rotateBitmap(bitmap: Bitmap, angle: Double): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle.toFloat())
        
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }
    
    /**
     * Downscale bitmap if dimensions exceed maximum
     */
    private fun downscaleIfNeeded(bitmap: Bitmap): Bitmap {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        
        if (maxDim <= MAX_DIMENSION) {
            return bitmap
        }
        
        val scale = MAX_DIMENSION.toFloat() / maxDim
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

/**
 * Result of smart crop operation
 */
sealed class CropResult {
    abstract val bitmap: Bitmap
    
    data class Success(
        override val bitmap: Bitmap,
        val originalSize: Pair<Int, Int>,
        val croppedSize: Pair<Int, Int>,
        val rotation: Double,
        val cropRatio: Float
    ) : CropResult()
    
    data class NoCropNeeded(override val bitmap: Bitmap) : CropResult()
    data class CropTooSmall(override val bitmap: Bitmap) : CropResult()
    data class CropFailed(override val bitmap: Bitmap, val error: String) : CropResult()
}


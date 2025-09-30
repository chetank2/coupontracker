package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized bitmap memory management for extraction pipeline
 * Enforces pixel budget to prevent OOM errors on low-memory devices
 * 
 * V2 Architecture: Single source of truth for bitmap lifecycle
 */
@Singleton
class BitmapManager @Inject constructor() {
    
    companion object {
        const val MAX_DIMENSION = 768
        const val MAX_TOTAL_PIXELS = 3 * MAX_DIMENSION * MAX_DIMENSION  // 1,769,472 pixels (~6.75 MB ARGB_8888)
    private const val TAG = "BitmapManager"
    }
    
    private val activeBuffers = mutableListOf<WeakReference<Bitmap>>()
    
    /**
     * Enforce pixel budget by recycling old bitmaps if necessary
     * Called automatically before creating new bitmaps
     */
    fun enforcePixelBudget() {
        val totalPixels = activeBuffers
            .mapNotNull { it.get() }
            .filter { !it.isRecycled }
            .sumOf { it.width.toLong() * it.height }
        
        if (totalPixels > MAX_TOTAL_PIXELS) {
            Log.w(TAG, "Pixel budget exceeded: $totalPixels > $MAX_TOTAL_PIXELS, recycling old buffers")
            
            var freedPixels = 0L
            activeBuffers.removeAll { ref ->
                val bitmap = ref.get()
                if (bitmap != null && !bitmap.isRecycled && totalPixels - freedPixels > MAX_TOTAL_PIXELS) {
                    freedPixels += bitmap.width.toLong() * bitmap.height
                    bitmap.recycle()
                    Log.d(TAG, "Recycled bitmap: ${bitmap.width}x${bitmap.height}, freed $freedPixels pixels")
                    true
                } else {
                    bitmap == null // Remove dead references
                }
            }
            
            Log.d(TAG, "Pixel budget enforced: freed $freedPixels pixels, ${activeBuffers.size} active buffers remaining")
        }
    }
    
    /**
     * Track a bitmap for memory management
     * Should be called for all bitmaps created in extraction pipeline
     */
    fun trackBitmap(bitmap: Bitmap) {
        enforcePixelBudget()
        activeBuffers.add(WeakReference(bitmap))
        Log.d(TAG, "Tracking bitmap: ${bitmap.width}x${bitmap.height}, total buffers: ${activeBuffers.size}")
    }
    
    /**
     * Resize a bitmap to fit within maximum dimension while respecting pixel budget
     * @param source Source bitmap (will not be recycled)
     * @param maxDim Maximum dimension for width or height (default: MAX_DIMENSION)
     * @return Resized bitmap (may be same as source if already small enough)
     */
    fun resizeWithBudget(source: Bitmap, maxDim: Int = MAX_DIMENSION): Bitmap {
        enforcePixelBudget()
        
        if (source.width <= maxDim && source.height <= maxDim) {
            Log.d(TAG, "Bitmap already within size limit: ${source.width}x${source.height}")
            return source
        }
        
        val scale = minOf(
            maxDim.toFloat() / source.width,
            maxDim.toFloat() / source.height
        )
        
        val newWidth = (source.width * scale).toInt()
        val newHeight = (source.height * scale).toInt()
        
        val resized = Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
        trackBitmap(resized)
        
        Log.d(TAG, "Resized bitmap: ${source.width}x${source.height} → ${newWidth}x${newHeight} (scale: $scale)")
        
        return resized
    }
    
    /**
     * Crop a bitmap to a specific region while respecting pixel budget
     * @param source Source bitmap (will not be recycled)
     * @param x X coordinate of crop region
     * @param y Y coordinate of crop region
     * @param width Width of crop region
     * @param height Height of crop region
     * @return Cropped bitmap
     */
    fun cropWithBudget(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
        enforcePixelBudget()
        
        // Clamp crop region to bitmap bounds
        val clampedX = x.coerceIn(0, source.width - 1)
        val clampedY = y.coerceIn(0, source.height - 1)
        val clampedWidth = width.coerceIn(1, source.width - clampedX)
        val clampedHeight = height.coerceIn(1, source.height - clampedY)
        
        val cropped = Bitmap.createBitmap(source, clampedX, clampedY, clampedWidth, clampedHeight)
        trackBitmap(cropped)
        
        Log.d(TAG, "Cropped bitmap: region ($clampedX,$clampedY) ${clampedWidth}x${clampedHeight}")
        
        return cropped
    }
    
    /**
     * Get current memory usage statistics
     * Useful for monitoring and diagnostics
     */
    fun getMemoryStats(): MemoryStats {
        val activeBitmaps = activeBuffers.mapNotNull { it.get() }.filter { !it.isRecycled }
        
        val totalPixels = activeBitmaps.sumOf { it.width.toLong() * it.height }
        val totalBytes = activeBitmaps.sumOf { it.allocationByteCount.toLong() }
        
        return MemoryStats(
            activeBitmapCount = activeBitmaps.size,
            totalPixels = totalPixels,
            totalBytesMB = totalBytes / (1024f * 1024f),
            pixelBudgetUsage = totalPixels.toFloat() / MAX_TOTAL_PIXELS
        )
    }
    
    /**
     * Clean up all tracked bitmaps (use with caution!)
     * Should only be called when clearing entire extraction pipeline
     */
    fun recycleAll() {
        activeBuffers.forEach { ref ->
            ref.get()?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        activeBuffers.clear()
        Log.d(TAG, "Recycled all tracked bitmaps")
    }
}

/**
 * Memory usage statistics for bitmap manager
 */
data class MemoryStats(
    val activeBitmapCount: Int,
    val totalPixels: Long,
    val totalBytesMB: Float,
    val pixelBudgetUsage: Float  // 0.0 to 1.0+
)
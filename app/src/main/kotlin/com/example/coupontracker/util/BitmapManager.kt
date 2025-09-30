package com.example.coupontracker.util

import android.graphics.Bitmap
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized bitmap memory management for extraction pipeline
 * Enforces pixel budget to prevent OOM errors on low-memory devices
 * 
 * V2 Architecture: Single source of truth for bitmap lifecycle
 * Uses reference counting to ensure bitmaps are only recycled when no longer in use
 */
@Singleton
class BitmapManager @Inject constructor() {
    
    companion object {
        const val MAX_DIMENSION = 768
        const val MAX_TOTAL_PIXELS = 3 * MAX_DIMENSION * MAX_DIMENSION  // 1,769,472 pixels (~6.75 MB ARGB_8888)
        private const val TAG = "BitmapManager"
    }
    
    private data class ManagedBitmap(
        val bitmap: Bitmap,
        var refCount: Int = 1,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    private val managedBitmaps = mutableMapOf<Bitmap, ManagedBitmap>()
    
    /**
     * Track a bitmap for memory management
     * Increments reference count if already tracked
     * @return The same bitmap for chaining
     */
    fun trackBitmap(bitmap: Bitmap): Bitmap {
        synchronized(managedBitmaps) {
            val managed = managedBitmaps[bitmap]
            if (managed != null) {
                managed.refCount++
                Log.d(TAG, "Incremented ref count for bitmap ${bitmap.width}x${bitmap.height}: refCount=${managed.refCount}")
            } else {
                managedBitmaps[bitmap] = ManagedBitmap(bitmap)
                Log.d(TAG, "Tracking new bitmap: ${bitmap.width}x${bitmap.height}, total managed: ${managedBitmaps.size}")
            }
            
            // Enforce budget after adding new bitmap
            enforcePixelBudgetInternal()
        }
        return bitmap
    }
    
    /**
     * Release a bitmap reference
     * Bitmap will be recycled when reference count reaches zero
     * @param bitmap Bitmap to release
     */
    fun releaseBitmap(bitmap: Bitmap) {
        synchronized(managedBitmaps) {
            val managed = managedBitmaps[bitmap] ?: return
            
            managed.refCount--
            Log.d(TAG, "Decremented ref count for bitmap ${bitmap.width}x${bitmap.height}: refCount=${managed.refCount}")
            
            if (managed.refCount <= 0) {
                if (!bitmap.isRecycled) {
                    val pixels = bitmap.width.toLong() * bitmap.height
                    bitmap.recycle()
                    Log.d(TAG, "Recycled bitmap ${bitmap.width}x${bitmap.height} (freed $pixels pixels)")
                }
                managedBitmaps.remove(bitmap)
            }
        }
    }
    
    /**
     * Enforce pixel budget by recycling ONLY bitmaps with zero references
     * Called automatically when tracking new bitmaps
     */
    private fun enforcePixelBudgetInternal() {
        val totalPixels = managedBitmaps.values
            .filter { !it.bitmap.isRecycled }
            .sumOf { it.bitmap.width.toLong() * it.bitmap.height }
        
        if (totalPixels > MAX_TOTAL_PIXELS) {
            Log.w(TAG, "Pixel budget exceeded: $totalPixels > $MAX_TOTAL_PIXELS")
            
            // Find unreferenced bitmaps, sorted by age (oldest first)
            val unreferenced = managedBitmaps.values
                .filter { it.refCount == 0 && !it.bitmap.isRecycled }
                .sortedBy { it.createdAt }
            
            var freedPixels = 0L
            val toRecycle = mutableListOf<Bitmap>()
            
            for (managed in unreferenced) {
                if (totalPixels - freedPixels <= MAX_TOTAL_PIXELS) break
                
                val pixels = managed.bitmap.width.toLong() * managed.bitmap.height
                freedPixels += pixels
                toRecycle.add(managed.bitmap)
            }
            
            // Recycle outside the iteration to avoid concurrent modification
            toRecycle.forEach { bitmap ->
                bitmap.recycle()
                managedBitmaps.remove(bitmap)
                Log.d(TAG, "Recycled unreferenced bitmap: ${bitmap.width}x${bitmap.height}")
            }
            
            if (toRecycle.isEmpty()) {
                Log.w(TAG, "Cannot free memory: all ${managedBitmaps.size} bitmaps are still in use!")
            } else {
                Log.d(TAG, "Freed $freedPixels pixels by recycling ${toRecycle.size} unreferenced bitmaps")
            }
        }
    }
    
    /**
     * Resize a bitmap to fit within maximum dimension while respecting pixel budget
     * @param source Source bitmap (will not be recycled unless you release it)
     * @param maxDim Maximum dimension for width or height (default: MAX_DIMENSION)
     * @return Resized bitmap (tracked and with refCount=1)
     */
    fun resizeWithBudget(source: Bitmap, maxDim: Int = MAX_DIMENSION): Bitmap {
        if (source.width <= maxDim && source.height <= maxDim) {
            Log.d(TAG, "Bitmap already within size limit: ${source.width}x${source.height}")
            // Track source if not already tracked
            return trackBitmap(source)
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
     * @param source Source bitmap (will not be recycled unless you release it)
     * @param x X coordinate of crop region
     * @param y Y coordinate of crop region
     * @param width Width of crop region
     * @param height Height of crop region
     * @return Cropped bitmap (tracked and with refCount=1)
     */
    fun cropWithBudget(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap {
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
        synchronized(managedBitmaps) {
            val activeBitmaps = managedBitmaps.values.filter { !it.bitmap.isRecycled }
            
            val totalPixels = activeBitmaps.sumOf { it.bitmap.width.toLong() * it.bitmap.height }
            val totalBytes = activeBitmaps.sumOf { it.bitmap.allocationByteCount.toLong() }
            val referencedCount = activeBitmaps.count { it.refCount > 0 }
            
            return MemoryStats(
                activeBitmapCount = activeBitmaps.size,
                totalPixels = totalPixels,
                totalBytesMB = totalBytes / (1024f * 1024f),
                pixelBudgetUsage = totalPixels.toFloat() / MAX_TOTAL_PIXELS,
                referencedCount = referencedCount
            )
        }
    }
    
    /**
     * Clean up all tracked bitmaps (use with caution!)
     * Recycles ALL bitmaps regardless of reference count
     * Should only be called when clearing entire extraction pipeline
     */
    fun recycleAll() {
        synchronized(managedBitmaps) {
            managedBitmaps.values.forEach { managed ->
                if (!managed.bitmap.isRecycled) {
                    managed.bitmap.recycle()
                }
            }
            val count = managedBitmaps.size
            managedBitmaps.clear()
            Log.d(TAG, "Force-recycled all $count tracked bitmaps")
        }
    }
}

/**
 * Memory usage statistics for bitmap manager
 */
data class MemoryStats(
    val activeBitmapCount: Int,
    val totalPixels: Long,
    val totalBytesMB: Float,
    val pixelBudgetUsage: Float,  // 0.0 to 1.0+
    val referencedCount: Int = 0   // Number of bitmaps with refCount > 0
)
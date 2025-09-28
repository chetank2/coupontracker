package com.example.coupontracker.util

import android.graphics.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Bitmap memory management with pressure controls and automatic recycling
 * Prevents OOM errors during multi-coupon processing and large image handling
 */
object BitmapManager {
    
    private const val TAG = "BitmapManager"
    
    // Memory budget configuration
    private const val MAX_PIXEL_BUDGET = 3 * 768 * 768 // 3 × 768² pixels max per scan
    private const val MAX_SINGLE_DIMENSION = 2048 // Max width or height for any bitmap
    private const val BYTES_PER_PIXEL = 4 // ARGB_8888
    
    // Tracking
    private val currentPixelUsage = AtomicLong(0)
    private val activeBitmaps = ConcurrentHashMap<String, BitmapInfo>()
    private var nextBitmapId = AtomicLong(0)
    
    /**
     * Bitmap information for tracking
     */
    private data class BitmapInfo(
        val bitmap: Bitmap,
        val pixels: Long,
        val createdAt: Long,
        val tag: String
    )
    
    /**
     * Result of bitmap processing with memory tracking
     */
    data class ProcessedBitmap(
        val bitmap: Bitmap,
        val id: String,
        val originalPixels: Long,
        val finalPixels: Long,
        val wasDownsampled: Boolean
    )
    
    /**
     * Create a managed bitmap with automatic downsampling if needed
     */
    suspend fun createManagedBitmap(
        source: Bitmap,
        tag: String = "unknown",
        maxDimension: Int = MAX_SINGLE_DIMENSION
    ): ProcessedBitmap = withContext(Dispatchers.Default) {
        
        val originalPixels = source.width.toLong() * source.height.toLong()
        Log.d(TAG, "Creating managed bitmap: ${source.width}x${source.height} ($originalPixels pixels) - $tag")
        
        // Check if we need to downsample
        val downsampledBitmap = if (needsDownsampling(source, maxDimension)) {
            downsampleBitmap(source, maxDimension)
        } else {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }
        
        val finalPixels = downsampledBitmap.width.toLong() * downsampledBitmap.height.toLong()
        
        // Check pixel budget
        if (!canAllocatePixels(finalPixels)) {
            // Try to free some memory
            recycleOldestBitmaps(finalPixels)
            
            if (!canAllocatePixels(finalPixels)) {
                // Still can't allocate - force downsample more aggressively
                downsampledBitmap.recycle()
                val aggressiveBitmap = downsampleBitmapAggressive(source, finalPixels)
                return@withContext trackBitmap(aggressiveBitmap, tag, originalPixels, true)
            }
        }
        
        return@withContext trackBitmap(downsampledBitmap, tag, originalPixels, downsampledBitmap !== source)
    }
    
    /**
     * Create a crop from a managed bitmap with bounds checking
     */
    suspend fun createManagedCrop(
        source: Bitmap,
        cropRect: Rect,
        tag: String = "crop"
    ): ProcessedBitmap? = withContext(Dispatchers.Default) {
        
        // Validate and adjust crop bounds
        val adjustedRect = Rect(
            max(0, cropRect.left),
            max(0, cropRect.top),
            min(source.width, cropRect.right),
            min(source.height, cropRect.bottom)
        )
        
        if (adjustedRect.width() <= 0 || adjustedRect.height() <= 0) {
            Log.w(TAG, "Invalid crop bounds: $adjustedRect")
            return@withContext null
        }
        
        val cropPixels = adjustedRect.width().toLong() * adjustedRect.height().toLong()
        
        // Check pixel budget
        if (!canAllocatePixels(cropPixels)) {
            recycleOldestBitmaps(cropPixels)
            
            if (!canAllocatePixels(cropPixels)) {
                Log.w(TAG, "Cannot allocate pixels for crop: $cropPixels (budget: ${getRemainingPixelBudget()})")
                return@withContext null
            }
        }
        
        try {
            val croppedBitmap = Bitmap.createBitmap(
                source,
                adjustedRect.left,
                adjustedRect.top,
                adjustedRect.width(),
                adjustedRect.height()
            )
            
            Log.d(TAG, "Created crop: ${adjustedRect.width()}x${adjustedRect.height()} ($cropPixels pixels) - $tag")
            return@withContext trackBitmap(croppedBitmap, tag, cropPixels, false)
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM creating crop: ${adjustedRect.width()}x${adjustedRect.height()}", e)
            return@withContext null
        }
    }
    
    /**
     * Release a managed bitmap and update tracking
     */
    fun releaseBitmap(id: String) {
        activeBitmaps.remove(id)?.let { info ->
            if (!info.bitmap.isRecycled) {
                info.bitmap.recycle()
            }
            currentPixelUsage.addAndGet(-info.pixels)
            Log.d(TAG, "Released bitmap $id (${info.pixels} pixels) - ${info.tag}")
        }
    }
    
    /**
     * Get current memory usage statistics
     */
    fun getMemoryStats(): BitmapMemoryStats {
        val currentPixels = currentPixelUsage.get()
        val currentBytes = currentPixels * BYTES_PER_PIXEL
        val budgetBytes = MAX_PIXEL_BUDGET * BYTES_PER_PIXEL
        
        return BitmapMemoryStats(
            currentPixels = currentPixels,
            maxPixelBudget = MAX_PIXEL_BUDGET.toLong(),
            currentMemoryMB = currentBytes / (1024f * 1024f),
            maxMemoryMB = budgetBytes / (1024f * 1024f),
            activeBitmapCount = activeBitmaps.size,
            utilizationPercent = (currentPixels * 100f / MAX_PIXEL_BUDGET).coerceAtMost(100f)
        )
    }
    
    /**
     * Force cleanup of all managed bitmaps
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up ${activeBitmaps.size} managed bitmaps")
        
        activeBitmaps.values.forEach { info ->
            if (!info.bitmap.isRecycled) {
                info.bitmap.recycle()
            }
        }
        
        activeBitmaps.clear()
        currentPixelUsage.set(0)
        
        Log.d(TAG, "Bitmap cleanup completed")
    }
    
    /**
     * Check if downsampling is needed
     */
    private fun needsDownsampling(bitmap: Bitmap, maxDimension: Int): Boolean {
        return bitmap.width > maxDimension || bitmap.height > maxDimension
    }
    
    /**
     * Downsample bitmap to fit within max dimension while preserving aspect ratio
     */
    private fun downsampleBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val scale = min(
            maxDimension.toFloat() / source.width,
            maxDimension.toFloat() / source.height
        )
        
        val newWidth = (source.width * scale).toInt()
        val newHeight = (source.height * scale).toInt()
        
        Log.d(TAG, "Downsampling ${source.width}x${source.height} → ${newWidth}x${newHeight} (scale: $scale)")
        
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
    
    /**
     * Aggressively downsample to fit within pixel budget
     */
    private fun downsampleBitmapAggressive(source: Bitmap, targetPixels: Long): Bitmap {
        val currentPixels = source.width.toLong() * source.height.toLong()
        val remainingBudget = getRemainingPixelBudget()
        val maxAllowedPixels = min(targetPixels, remainingBudget)
        
        if (maxAllowedPixels <= 0) {
            // Create minimal bitmap
            return Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)
        }
        
        val scale = kotlin.math.sqrt(maxAllowedPixels.toDouble() / currentPixels.toDouble()).toFloat()
        val newWidth = max(64, (source.width * scale).toInt())
        val newHeight = max(64, (source.height * scale).toInt())
        
        Log.w(TAG, "Aggressive downsampling ${source.width}x${source.height} → ${newWidth}x${newHeight} (scale: $scale)")
        
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
    
    /**
     * Check if we can allocate the requested pixels
     */
    private fun canAllocatePixels(pixels: Long): Boolean {
        return currentPixelUsage.get() + pixels <= MAX_PIXEL_BUDGET
    }
    
    /**
     * Get remaining pixel budget
     */
    private fun getRemainingPixelBudget(): Long {
        return max(0, MAX_PIXEL_BUDGET - currentPixelUsage.get())
    }
    
    /**
     * Recycle oldest bitmaps to free up memory
     */
    private fun recycleOldestBitmaps(neededPixels: Long) {
        val sortedBitmaps = activeBitmaps.values.sortedBy { it.createdAt }
        var freedPixels = 0L
        val toRemove = mutableListOf<String>()
        
        for (info in sortedBitmaps) {
            if (freedPixels >= neededPixels) break
            
            val id = activeBitmaps.entries.find { it.value == info }?.key
            if (id != null) {
                toRemove.add(id)
                freedPixels += info.pixels
                Log.d(TAG, "Recycling old bitmap $id (${info.pixels} pixels) - ${info.tag}")
            }
        }
        
        toRemove.forEach { id ->
            releaseBitmap(id)
        }
        
        Log.d(TAG, "Recycled ${toRemove.size} bitmaps, freed $freedPixels pixels")
    }
    
    /**
     * Track a bitmap in the management system
     */
    private fun trackBitmap(
        bitmap: Bitmap,
        tag: String,
        originalPixels: Long,
        wasDownsampled: Boolean
    ): ProcessedBitmap {
        val id = "bitmap_${nextBitmapId.incrementAndGet()}"
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        
        val info = BitmapInfo(
            bitmap = bitmap,
            pixels = pixels,
            createdAt = System.currentTimeMillis(),
            tag = tag
        )
        
        activeBitmaps[id] = info
        currentPixelUsage.addAndGet(pixels)
        
        Log.d(TAG, "Tracking bitmap $id: ${bitmap.width}x${bitmap.height} ($pixels pixels) - $tag")
        
        return ProcessedBitmap(
            bitmap = bitmap,
            id = id,
            originalPixels = originalPixels,
            finalPixels = pixels,
            wasDownsampled = wasDownsampled
        )
    }
}

/**
 * Bitmap memory usage statistics
 */
data class BitmapMemoryStats(
    val currentPixels: Long,
    val maxPixelBudget: Long,
    val currentMemoryMB: Float,
    val maxMemoryMB: Float,
    val activeBitmapCount: Int,
    val utilizationPercent: Float
)

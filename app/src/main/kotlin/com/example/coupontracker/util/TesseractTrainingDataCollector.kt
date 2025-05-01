package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for collecting and preparing training data for Tesseract OCR
 */
class TesseractTrainingDataCollector(private val context: Context) {
    companion object {
        private const val TAG = "TesseractTraining"
        private const val TRAINING_DATA_DIR = "tesseract_training"
        private const val GROUND_TRUTH_FILE = "ground_truth.txt"
    }
    
    // Directory for storing training data
    private val trainingDataDir: File by lazy {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), TRAINING_DATA_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }
    
    // Ground truth file for storing text annotations
    private val groundTruthFile: File by lazy {
        File(trainingDataDir, GROUND_TRUTH_FILE)
    }
    
    /**
     * Save an image for training with its ground truth text
     * @param bitmap The image to save
     * @param text The correct text in the image (ground truth)
     * @param label Optional label for categorizing the image (e.g., "store_name", "expiry_date")
     * @return The path to the saved image or null if saving failed
     */
    suspend fun saveTrainingImage(
        bitmap: Bitmap,
        text: String,
        label: String = "general"
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Create timestamp for unique filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "${label}_${timestamp}.png"
            
            // Create directory for this label if it doesn't exist
            val labelDir = File(trainingDataDir, label)
            if (!labelDir.exists()) {
                labelDir.mkdirs()
            }
            
            // Save the image
            val imageFile = File(labelDir, filename)
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            // Append to ground truth file
            val groundTruthEntry = "$filename\\t$text\\n"
            groundTruthFile.appendText(groundTruthEntry)
            
            Log.d(TAG, "Saved training image: ${imageFile.absolutePath}")
            return@withContext imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving training image", e)
            return@withContext null
        }
    }
    
    /**
     * Create a synthetic training image with specific text
     * This is useful for generating training data with known text
     * @param text The text to render in the image
     * @param label Optional label for categorizing the image
     * @return The path to the saved image or null if creation failed
     */
    suspend fun createSyntheticTrainingImage(
        text: String,
        label: String = "synthetic"
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Create a bitmap with the text
            val bitmap = createTextBitmap(text)
            
            // Save the image with its ground truth
            return@withContext saveTrainingImage(bitmap, text, label)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating synthetic training image", e)
            return@withContext null
        }
    }
    
    /**
     * Create a bitmap with rendered text
     * @param text The text to render
     * @return A bitmap containing the rendered text
     */
    private fun createTextBitmap(text: String): Bitmap {
        // Calculate size based on text length
        val width = text.length * 20 + 40
        val height = 100
        
        // Create bitmap and canvas
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Fill background
        canvas.drawColor(Color.WHITE)
        
        // Set up text paint
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 40f
            isAntiAlias = true
        }
        
        // Draw text centered
        val x = 20f
        val y = height / 2f + paint.textSize / 3f
        canvas.drawText(text, x, y, paint)
        
        return bitmap
    }
    
    /**
     * Extract regions from an image for training
     * This is useful for extracting specific parts of coupons (store name, expiry date, etc.)
     * @param bitmap The source image
     * @param regions List of regions to extract (x, y, width, height, label)
     * @param groundTruth Map of region labels to their ground truth text
     * @return List of paths to saved region images
     */
    suspend fun extractAndSaveRegions(
        bitmap: Bitmap,
        regions: List<Region>,
        groundTruth: Map<String, String>
    ): List<String> = withContext(Dispatchers.IO) {
        val savedPaths = mutableListOf<String>()
        
        for (region in regions) {
            try {
                // Extract the region
                val regionBitmap = Bitmap.createBitmap(
                    bitmap,
                    region.x,
                    region.y,
                    region.width,
                    region.height
                )
                
                // Get ground truth for this region
                val text = groundTruth[region.label] ?: continue
                
                // Save the region
                val path = saveTrainingImage(regionBitmap, text, region.label)
                path?.let { savedPaths.add(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting region: ${region.label}", e)
            }
        }
        
        return@withContext savedPaths
    }
    
    /**
     * Export all collected training data as a zip file
     * @return The URI of the exported zip file or null if export failed
     */
    suspend fun exportTrainingData(): Uri? = withContext(Dispatchers.IO) {
        try {
            // Create a zip file
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "tesseract_training_data_$timestamp.zip"
            )
            
            // Zip the training data directory
            ZipUtil.zipDirectory(trainingDataDir, zipFile)
            
            Log.d(TAG, "Exported training data: ${zipFile.absolutePath}")
            return@withContext Uri.fromFile(zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error exporting training data", e)
            return@withContext null
        }
    }
    
    /**
     * Clear all collected training data
     */
    fun clearTrainingData() {
        try {
            trainingDataDir.deleteRecursively()
            trainingDataDir.mkdirs()
            Log.d(TAG, "Cleared training data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing training data", e)
        }
    }
    
    /**
     * Get statistics about collected training data
     * @return A map of labels to counts
     */
    fun getTrainingDataStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        
        try {
            // Count files in each label directory
            trainingDataDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name != "." && dir.name != "..") {
                    val count = dir.listFiles()?.size ?: 0
                    stats[dir.name] = count
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting training data stats", e)
        }
        
        return stats
    }
    
    /**
     * Data class representing a region in an image
     */
    data class Region(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val label: String
    )
}

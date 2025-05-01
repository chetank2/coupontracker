package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Helper class for Tesseract OCR integration
 */
class TesseractOCRHelper(private val context: Context) {
    private val TAG = "TesseractOCRHelper"
    private var tessBaseAPI: TessBaseAPI? = null
    private var isInitialized = false
    
    /**
     * Initialize Tesseract with the required training data
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized && tessBaseAPI != null) {
                return@withContext true
            }
            
            Log.d(TAG, "Initializing Tesseract OCR")
            
            // Create a new TessBaseAPI instance
            tessBaseAPI = TessBaseAPI()
            
            // Check if training data exists, if not, copy from assets
            val dataPath = File(context.getExternalFilesDir(null), "tesseract")
            if (!dataPath.exists()) {
                dataPath.mkdirs()
            }
            
            val tessDataFolder = File(dataPath, "tessdata")
            if (!tessDataFolder.exists()) {
                tessDataFolder.mkdirs()
            }
            
            // Check if eng.traineddata exists
            val engTrainedData = File(tessDataFolder, "eng.traineddata")
            if (!engTrainedData.exists()) {
                // Copy from assets
                try {
                    val inputStream = context.assets.open("tessdata/eng.traineddata")
                    val outputStream = FileOutputStream(engTrainedData)
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    inputStream.close()
                    outputStream.flush()
                    outputStream.close()
                    Log.d(TAG, "Copied eng.traineddata to $engTrainedData")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy eng.traineddata", e)
                    return@withContext false
                }
            }
            
            // Initialize Tesseract with English language
            val success = tessBaseAPI?.init(dataPath.absolutePath, "eng")
            isInitialized = success == true
            
            if (isInitialized) {
                // Configure Tesseract for better results
                tessBaseAPI?.setVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.,;:!?@#$%&*()-_+=[]{}|<>/\\\"'`~^")
                tessBaseAPI?.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                Log.d(TAG, "Tesseract initialized successfully")
            } else {
                Log.e(TAG, "Failed to initialize Tesseract")
            }
            
            return@withContext isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Tesseract", e)
            return@withContext false
        }
    }
    
    /**
     * Process an image and extract text using Tesseract OCR
     */
    suspend fun processImageFromBitmap(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                val initialized = initialize()
                if (!initialized) {
                    Log.e(TAG, "Tesseract not initialized, cannot process image")
                    return@withContext ""
                }
            }
            
            Log.d(TAG, "Processing bitmap with Tesseract OCR")
            
            // Set the bitmap to process
            tessBaseAPI?.setImage(bitmap)
            
            // Get the recognized text
            val recognizedText = tessBaseAPI?.utF8Text() ?: ""
            
            Log.d(TAG, "Tesseract recognized text (length: ${recognizedText.length})")
            
            return@withContext recognizedText
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image with Tesseract", e)
            return@withContext ""
        }
    }
    
    /**
     * Process an image from URI using Tesseract OCR
     */
    suspend fun processImageFromUri(context: Context, imageUri: Uri): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing image from URI with Tesseract OCR")
            
            // Load bitmap from URI
            val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from URI")
                return@withContext ""
            }
            
            return@withContext processImageFromBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image from URI with Tesseract", e)
            return@withContext ""
        }
    }
    
    /**
     * Extract coupon information from text
     */
    suspend fun extractCouponInfo(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            val text = processImageFromBitmap(bitmap)
            if (text.isBlank()) {
                Log.w(TAG, "Tesseract returned empty text")
                return@withContext CouponInfo()
            }
            
            // Use the existing TextExtractor to extract coupon info
            val textExtractor = TextExtractor()
            return@withContext textExtractor.extractCouponInfo(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting coupon info with Tesseract", e)
            return@withContext CouponInfo()
        }
    }
    
    /**
     * Test if Tesseract is available and working
     */
    suspend fun testAvailability(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to initialize Tesseract
            val initialized = initialize()
            if (!initialized) {
                Log.e(TAG, "Tesseract initialization failed")
                return@withContext false
            }
            
            // Create a simple test bitmap
            val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            
            // Try to process it (this will likely return empty text, but that's fine for testing)
            processImageFromBitmap(testBitmap)
            
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract availability test failed", e)
            return@withContext false
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            tessBaseAPI?.end()
            tessBaseAPI = null
            isInitialized = false
            Log.d(TAG, "Tesseract resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Tesseract resources", e)
        }
    }
}

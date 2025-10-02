package com.example.coupontracker.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tesseract-based OCR engine implementation
 * Fully offline, no network dependencies
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : OcrEngine {
    
    companion object {
        private const val TAG = "TesseractOcrEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANG = "eng"
        
        // Shared lock for thread-safe initialization
        private val initLock = Any()
    }
    
    private var tessBaseAPI: TessBaseAPI? = null
    private val tessDataDir = File(context.filesDir, TESSDATA_DIR)
    @Volatile
    private var isInitialized = false
    
    init {
        // Synchronize initialization across all threads
        synchronized(initLock) {
            if (!isInitialized) {
                initializeTesseract()
            }
        }
    }
    
    @Synchronized
    private fun initializeTesseract() {
        // Double-check inside synchronized block
        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }
        
        try {
            Log.d(TAG, "🔧 Initializing Tesseract OCR [Thread: ${Thread.currentThread().name}]...")
            
            // Copy tessdata from assets if not already present
            val tessDataFile = File(tessDataDir, "$LANG.traineddata")
            if (!tessDataFile.exists()) {
                Log.d(TAG, "Copying tessdata from assets...")
                tessDataDir.mkdirs()
                
                context.assets.open("$TESSDATA_DIR/$LANG.traineddata").use { input ->
                    FileOutputStream(tessDataFile).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }
                Log.d(TAG, "✓ Tessdata copied: ${tessDataFile.length()} bytes")
            } else {
                Log.d(TAG, "Tessdata already exists: ${tessDataFile.length()} bytes")
            }
            
            // Verify file is readable
            if (!tessDataFile.canRead()) {
                throw IllegalStateException("Tessdata file is not readable: ${tessDataFile.absolutePath}")
            }
            
            // Initialize Tesseract
            val dataPath = context.filesDir.absolutePath
            Log.d(TAG, "Initializing with dataPath: $dataPath, lang: $LANG")
            
            tessBaseAPI = TessBaseAPI().apply {
                val success = init(dataPath, LANG)
                if (!success) {
                    val errorMsg = "Tesseract init() returned false. " +
                        "Path: $dataPath, Lang: $LANG, " +
                        "File exists: ${tessDataFile.exists()}, " +
                        "File size: ${tessDataFile.length()}, " +
                        "File readable: ${tessDataFile.canRead()}"
                    Log.e(TAG, errorMsg)
                    Log.w(TAG, "⚠️ Tesseract native init failed - this is a known issue with tess-two library")
                    Log.w(TAG, "   Will fall back to ML Kit Text Recognition (offline, more reliable)")
                    throw IllegalStateException(errorMsg)
                }
                
                // Set page segmentation mode for better coupon recognition
                // Try simpler mode first (PSM_AUTO instead of PSM_AUTO_OSD)
                pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                
                Log.d(TAG, "✅ Tesseract initialized successfully [PSM: PSM_AUTO]")
            }
            
            isInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize Tesseract - will use ML Kit as fallback", e)
            tessBaseAPI?.end()
            tessBaseAPI = null
            isInitialized = false
            // DON'T re-throw - let the app continue with ML Kit fallback
        }
    }
    
    override suspend fun recognize(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        val api = tessBaseAPI ?: run {
            Log.w(TAG, "Tesseract not initialized, attempting re-init...")
            initializeTesseract()
            tessBaseAPI ?: throw IllegalStateException("Tesseract OCR not available")
        }
        
        return@withContext try {
            api.setImage(bitmap)
            val text = api.utF8Text ?: ""
            Log.d(TAG, "Extracted ${text.length} chars")
            text
        } catch (e: Exception) {
            Log.e(TAG, "OCR extraction failed", e)
            ""
        }
    }
    
    override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> = withContext(Dispatchers.Default) {
        // TODO: Implement bounding box extraction using TessBaseAPI ResultIterator
        // For now, return empty list - this is not critical for initial implementation
        Log.w(TAG, "Bounding box extraction not yet implemented")
        return@withContext emptyList()
    }
    
    override fun isReady(): Boolean = isInitialized && tessBaseAPI != null
    
    override fun release() {
        tessBaseAPI?.end()
        tessBaseAPI = null
        isInitialized = false
        Log.d(TAG, "Tesseract resources released")
    }
}


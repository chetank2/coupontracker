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
    }
    
    private var tessBaseAPI: TessBaseAPI? = null
    private val tessDataDir = File(context.filesDir, TESSDATA_DIR)
    private var isInitialized = false
    
    init {
        initializeTesseract()
    }
    
    private fun initializeTesseract() {
        try {
            Log.d(TAG, "Initializing Tesseract OCR...")
            
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
            }
            
            // Initialize Tesseract
            tessBaseAPI = TessBaseAPI().apply {
                val success = init(context.filesDir.absolutePath, LANG)
                if (!success) {
                    throw IllegalStateException("Tesseract init() returned false")
                }
                
                // Set page segmentation mode for better coupon recognition
                pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD
                
                Log.d(TAG, "✓ Tesseract initialized successfully")
            }
            
            isInitialized = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Tesseract", e)
            tessBaseAPI?.recycle()
            tessBaseAPI = null
            isInitialized = false
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
        tessBaseAPI?.recycle()
        tessBaseAPI = null
        isInitialized = false
        Log.d(TAG, "Tesseract resources released")
    }
}


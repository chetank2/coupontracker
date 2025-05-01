package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Helper class for Tesseract OCR integration
 */
class TesseractOCRHelper(private val context: Context) {
    private val TAG = "TesseractOCRHelper"

    // Tesseract API instance
    private var tessBaseAPI: TessBaseAPI? = null
    private var isInitialized = AtomicBoolean(false)

    // Cache for Tesseract instances to avoid repeated initialization
    private val apiCache = LruCache<String, TessBaseAPI>(3) // Cache up to 3 language configurations

    // Performance settings
    enum class PageSegMode(val mode: Int) {
        AUTO(TessBaseAPI.PageSegMode.PSM_AUTO),
        SINGLE_BLOCK(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK),
        SINGLE_LINE(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE),
        SINGLE_WORD(TessBaseAPI.PageSegMode.PSM_SINGLE_WORD),
        SPARSE_TEXT(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
    }

    enum class OcrEngineMode(val mode: Int) {
        DEFAULT(TessBaseAPI.OEM_DEFAULT),
        TESSERACT_ONLY(TessBaseAPI.OEM_TESSERACT_ONLY),
        LSTM_ONLY(TessBaseAPI.OEM_LSTM_ONLY),
        TESSERACT_LSTM_COMBINED(TessBaseAPI.OEM_TESSERACT_LSTM_COMBINED)
    }

    // Default settings
    private var pageSegMode = PageSegMode.AUTO
    private var ocrEngineMode = OcrEngineMode.DEFAULT
    private var whitelist = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz.,;:!?@#$%&*()-_+=[]{}|<>/\\\"'`~^"

    // Image preprocessing settings
    private var enablePreprocessing = true
    private var preprocessingType = PreprocessingType.ADAPTIVE

    enum class PreprocessingType {
        NONE,
        GRAYSCALE,
        BINARIZATION,
        ADAPTIVE
    }

    /**
     * Initialize Tesseract with the required training data
     * @param language The language to initialize (default: "eng")
     * @param engineMode The OCR engine mode to use
     * @return true if initialization was successful, false otherwise
     */
    suspend fun initialize(
        language: String = "eng",
        engineMode: OcrEngineMode = OcrEngineMode.DEFAULT
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check if we already have an initialized instance
            if (isInitialized.get() && tessBaseAPI != null) {
                return@withContext true
            }

            // Check if we have a cached instance for this language
            val cacheKey = "${language}_${engineMode.name}"
            val cachedApi = apiCache.get(cacheKey)
            if (cachedApi != null) {
                Log.d(TAG, "Using cached Tesseract instance for $language")
                tessBaseAPI = cachedApi
                isInitialized.set(true)
                return@withContext true
            }

            Log.d(TAG, "Initializing Tesseract OCR for language: $language")

            // Create a new TessBaseAPI instance
            val api = TessBaseAPI()

            // Check if training data exists, if not, copy from assets
            val dataPath = File(context.getExternalFilesDir(null), "tesseract")
            if (!dataPath.exists()) {
                dataPath.mkdirs()
            }

            val tessDataFolder = File(dataPath, "tessdata")
            if (!tessDataFolder.exists()) {
                tessDataFolder.mkdirs()
            }

            // Check if language training data exists
            val trainedDataFile = File(tessDataFolder, "$language.traineddata")
            if (!trainedDataFile.exists()) {
                // Copy from assets
                try {
                    val inputStream = context.assets.open("tessdata/$language.traineddata")
                    val outputStream = FileOutputStream(trainedDataFile)
                    val buffer = ByteArray(1024)
                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                    }
                    inputStream.close()
                    outputStream.flush()
                    outputStream.close()
                    Log.d(TAG, "Copied $language.traineddata to $trainedDataFile")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to copy $language.traineddata", e)
                    return@withContext false
                }
            }

            // Initialize Tesseract with the specified language
            val success = api.init(dataPath.absolutePath, language, engineMode.mode)

            if (success) {
                // Configure Tesseract for better results
                api.setVariable("tessedit_char_whitelist", whitelist)
                api.setPageSegMode(pageSegMode.mode)

                // Cache the initialized instance
                apiCache.put(cacheKey, api)

                // Set as current instance
                tessBaseAPI = api
                isInitialized.set(true)

                Log.d(TAG, "Tesseract initialized successfully for $language")
            } else {
                Log.e(TAG, "Failed to initialize Tesseract for $language")
            }

            return@withContext isInitialized.get()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing Tesseract", e)
            return@withContext false
        }
    }

    /**
     * Configure Tesseract performance settings
     * @param segMode The page segmentation mode
     * @param charWhitelist The character whitelist
     * @param enablePreprocessing Whether to enable image preprocessing
     * @param preprocessingType The type of preprocessing to apply
     */
    fun configure(
        segMode: PageSegMode = PageSegMode.AUTO,
        charWhitelist: String? = null,
        enablePreprocessing: Boolean = true,
        preprocessingType: PreprocessingType = PreprocessingType.ADAPTIVE
    ) {
        this.pageSegMode = segMode
        charWhitelist?.let { this.whitelist = it }
        this.enablePreprocessing = enablePreprocessing
        this.preprocessingType = preprocessingType

        // Apply settings to current instance if initialized
        if (isInitialized.get() && tessBaseAPI != null) {
            tessBaseAPI?.setVariable("tessedit_char_whitelist", this.whitelist)
            tessBaseAPI?.setPageSegMode(this.pageSegMode.mode)
        }
    }

    /**
     * Preprocess image for better OCR results
     * @param bitmap The bitmap to preprocess
     * @return The preprocessed bitmap
     */
    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        if (!enablePreprocessing || preprocessingType == PreprocessingType.NONE) {
            return bitmap
        }

        val width = bitmap.width
        val height = bitmap.height

        // Create a mutable copy of the bitmap
        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(outputBitmap)
        val paint = Paint()

        when (preprocessingType) {
            PreprocessingType.GRAYSCALE -> {
                // Convert to grayscale
                val colorMatrix = ColorMatrix()
                colorMatrix.setSaturation(0f) // 0 means grayscale
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            PreprocessingType.BINARIZATION -> {
                // Convert to black and white (binary)
                val colorMatrix = ColorMatrix(floatArrayOf(
                    1.5f, 1.5f, 1.5f, 0f, -255f, // Red
                    1.5f, 1.5f, 1.5f, 0f, -255f, // Green
                    1.5f, 1.5f, 1.5f, 0f, -255f, // Blue
                    0f, 0f, 0f, 1f, 0f           // Alpha
                ))
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            PreprocessingType.ADAPTIVE -> {
                // Adaptive preprocessing based on image characteristics
                val colorMatrix = ColorMatrix()

                // Increase contrast
                colorMatrix.set(floatArrayOf(
                    1.2f, 0f, 0f, 0f, -10f, // Red
                    0f, 1.2f, 0f, 0f, -10f, // Green
                    0f, 0f, 1.2f, 0f, -10f, // Blue
                    0f, 0f, 0f, 1f, 0f      // Alpha
                ))
                paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            else -> {
                // No preprocessing
            }
        }

        // Draw the bitmap with the applied filter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return outputBitmap
    }

    /**
     * Process an image and extract text using Tesseract OCR
     * @param bitmap The bitmap to process
     * @param language The language to use for OCR (default: "eng")
     * @return The recognized text
     */
    suspend fun processImageFromBitmap(
        bitmap: Bitmap,
        language: String = "eng"
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized.get()) {
                val initialized = initialize(language)
                if (!initialized) {
                    Log.e(TAG, "Tesseract not initialized, cannot process image")
                    return@withContext ""
                }
            }

            Log.d(TAG, "Processing bitmap with Tesseract OCR")

            // Start timing
            val startTime = System.currentTimeMillis()

            // Preprocess the image if enabled
            val processedBitmap = if (enablePreprocessing) preprocessImage(bitmap) else bitmap

            // Set the bitmap to process
            tessBaseAPI?.setImage(processedBitmap)

            // Get the recognized text
            val recognizedText = tessBaseAPI?.utF8Text() ?: ""

            // Calculate processing time
            val processingTime = System.currentTimeMillis() - startTime

            Log.d(TAG, "Tesseract recognized text (length: ${recognizedText.length}) in $processingTime ms")

            return@withContext recognizedText
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image with Tesseract", e)
            return@withContext ""
        }
    }

    /**
     * Process an image with optimized settings for faster results
     * This method sacrifices some accuracy for speed
     * @param bitmap The bitmap to process
     * @return The recognized text
     */
    suspend fun processImageFast(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            // Save current settings
            val originalSegMode = pageSegMode
            val originalPreprocessingType = preprocessingType

            // Configure for speed
            configure(
                segMode = PageSegMode.SINGLE_BLOCK,
                enablePreprocessing = true,
                preprocessingType = PreprocessingType.GRAYSCALE
            )

            // Process the image
            val result = processImageFromBitmap(bitmap)

            // Restore original settings
            configure(
                segMode = originalSegMode,
                enablePreprocessing = enablePreprocessing,
                preprocessingType = originalPreprocessingType
            )

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error in fast processing", e)
            return@withContext ""
        }
    }

    /**
     * Process an image with optimized settings for higher accuracy
     * This method sacrifices speed for accuracy
     * @param bitmap The bitmap to process
     * @return The recognized text
     */
    suspend fun processImageAccurate(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        try {
            // Save current settings
            val originalSegMode = pageSegMode
            val originalPreprocessingType = preprocessingType

            // Configure for accuracy
            configure(
                segMode = PageSegMode.AUTO,
                enablePreprocessing = true,
                preprocessingType = PreprocessingType.ADAPTIVE
            )

            // Process the image
            val result = processImageFromBitmap(bitmap)

            // Restore original settings
            configure(
                segMode = originalSegMode,
                enablePreprocessing = enablePreprocessing,
                preprocessingType = originalPreprocessingType
            )

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error in accurate processing", e)
            return@withContext ""
        }
    }

    /**
     * Process an image from URI using Tesseract OCR
     * @param context The context
     * @param imageUri The URI of the image to process
     * @param language The language to use for OCR (default: "eng")
     * @return The recognized text
     */
    suspend fun processImageFromUri(
        context: Context,
        imageUri: Uri,
        language: String = "eng"
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing image from URI with Tesseract OCR")

            // Load bitmap from URI
            val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load bitmap from URI")
                return@withContext ""
            }

            return@withContext processImageFromBitmap(bitmap, language)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image from URI with Tesseract", e)
            return@withContext ""
        }
    }

    /**
     * Extract coupon information from an image
     * @param bitmap The bitmap to process
     * @param language The language to use for OCR (default: "eng")
     * @param useAccurateMode Whether to use accurate mode (slower but more accurate)
     * @return The extracted coupon information
     */
    suspend fun extractCouponInfo(
        bitmap: Bitmap,
        language: String = "eng",
        useAccurateMode: Boolean = true
    ): CouponInfo = withContext(Dispatchers.IO) {
        try {
            // Process the image with appropriate mode
            val text = if (useAccurateMode) {
                processImageAccurate(bitmap)
            } else {
                processImageFromBitmap(bitmap, language)
            }

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
     * Test if Tesseract is available and working for a specific language
     * @param language The language to test (default: "eng")
     * @return true if Tesseract is available and working, false otherwise
     */
    suspend fun testAvailability(language: String = "eng"): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to initialize Tesseract
            val initialized = initialize(language)
            if (!initialized) {
                Log.e(TAG, "Tesseract initialization failed for language: $language")
                return@withContext false
            }

            // Create a simple test bitmap
            val testBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

            // Try to process it (this will likely return empty text, but that's fine for testing)
            processImageFromBitmap(testBitmap, language)

            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract availability test failed for language: $language", e)
            return@withContext false
        }
    }

    /**
     * Get a list of available languages
     * @return A list of available language codes
     */
    fun getAvailableLanguages(): List<String> {
        val languages = mutableListOf<String>()

        try {
            // Check assets directory for language files
            val assetManager = context.assets
            val tessDataFiles = assetManager.list("tessdata") ?: emptyArray()

            // Filter for .traineddata files and extract language codes
            languages.addAll(
                tessDataFiles
                    .filter { it.endsWith(".traineddata") }
                    .map { it.removeSuffix(".traineddata") }
            )

            // Also check external storage for any additional language files
            val dataPath = File(context.getExternalFilesDir(null), "tesseract/tessdata")
            if (dataPath.exists() && dataPath.isDirectory) {
                val externalFiles = dataPath.listFiles() ?: emptyArray()
                languages.addAll(
                    externalFiles
                        .filter { it.name.endsWith(".traineddata") }
                        .map { it.name.removeSuffix(".traineddata") }
                        .filter { !languages.contains(it) } // Avoid duplicates
                )
            }

            Log.d(TAG, "Available languages: $languages")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available languages", e)
        }

        return languages
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        try {
            // End all cached instances
            for (i in 0 until apiCache.size()) {
                val key = apiCache.snapshot().keys.elementAtOrNull(i)
                key?.let {
                    val api = apiCache.get(it)
                    api?.end()
                }
            }

            // Clear the cache
            apiCache.evictAll()

            // End the current instance
            tessBaseAPI?.end()
            tessBaseAPI = null
            isInitialized.set(false)

            Log.d(TAG, "Tesseract resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up Tesseract resources", e)
        }
    }
}

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

    // Custom model settings
    private var useCustomModel = false
    private var customModelLangCode = "coupon"

    // Tesseract trainer for custom models
    private val tesseractTrainer = TesseractTrainer(context)

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
     * @param useCustomModel Whether to use a custom trained model if available
     * @return true if initialization was successful, false otherwise
     */
    suspend fun initialize(
        language: String = "eng",
        engineMode: OcrEngineMode = OcrEngineMode.DEFAULT,
        useCustomModel: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        // Set custom model flag
        this@TesseractOCRHelper.useCustomModel = useCustomModel

        // If using custom model, check if it's available
        val actualLanguage = if (useCustomModel && tesseractTrainer.isCustomModelAvailable(customModelLangCode)) {
            Log.d(TAG, "Using custom model: $customModelLangCode")
            customModelLangCode
        } else {
            if (useCustomModel) {
                Log.d(TAG, "Custom model not available, falling back to: $language")
            }
            language
        }
        try {
            // Check if we already have an initialized instance
            if (isInitialized.get() && tessBaseAPI != null) {
                return@withContext true
            }

            // Check if we have a cached instance for this language
            val cacheKey = "${actualLanguage}_${engineMode.name}"
            val cachedApi = apiCache.get(cacheKey)
            if (cachedApi != null) {
                Log.d(TAG, "Using cached Tesseract instance for $actualLanguage")
                tessBaseAPI = cachedApi
                isInitialized.set(true)
                return@withContext true
            }

            Log.d(TAG, "Initializing Tesseract OCR for language: $actualLanguage")

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
            val trainedDataFile = File(tessDataFolder, "$actualLanguage.traineddata")
            if (!trainedDataFile.exists()) {
                // If it's a custom model, check if it's available from the trainer
                if (useCustomModel && actualLanguage == customModelLangCode) {
                    val customModelPath = tesseractTrainer.getCustomModelPath(customModelLangCode)
                    if (customModelPath != null) {
                        // Copy the custom model to the tessdata folder
                        File(customModelPath).copyTo(trainedDataFile, overwrite = true)
                        Log.d(TAG, "Copied custom model to $trainedDataFile")
                    } else {
                        Log.e(TAG, "Custom model not found, falling back to standard language")
                        return@withContext initialize(language, engineMode, false)
                    }
                } else {
                    // Copy from assets
                    try {
                        val inputStream = context.assets.open("tessdata/$actualLanguage.traineddata")
                        val outputStream = FileOutputStream(trainedDataFile)
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1) {
                            outputStream.write(buffer, 0, read)
                        }
                        inputStream.close()
                        outputStream.flush()
                        outputStream.close()
                        Log.d(TAG, "Copied $actualLanguage.traineddata to $trainedDataFile")
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to copy $actualLanguage.traineddata", e)
                        if (actualLanguage != "eng") {
                            Log.d(TAG, "Falling back to English")
                            return@withContext initialize("eng", engineMode, false)
                        }
                        return@withContext false
                    }
                }
            }

            // Initialize Tesseract with the specified language
            val success = api.init(dataPath.absolutePath, actualLanguage, engineMode.mode)

            if (success) {
                // Configure Tesseract for better results
                api.setVariable("tessedit_char_whitelist", whitelist)
                api.setPageSegMode(pageSegMode.mode)

                // Cache the initialized instance
                apiCache.put(cacheKey, api)

                // Set as current instance
                tessBaseAPI = api
                isInitialized.set(true)

                // Configure for coupon-specific recognition if using custom model
                if (useCustomModel && actualLanguage == customModelLangCode) {
                    // Apply coupon-specific configurations
                    configureCouponSpecificSettings(api)
                }

                Log.d(TAG, "Tesseract initialized successfully for $actualLanguage")
            } else {
                Log.e(TAG, "Failed to initialize Tesseract for $actualLanguage")
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
     * @param useCustomModel Whether to use a custom trained model if available
     */
    fun configure(
        segMode: PageSegMode = PageSegMode.AUTO,
        charWhitelist: String? = null,
        enablePreprocessing: Boolean = true,
        preprocessingType: PreprocessingType = PreprocessingType.ADAPTIVE,
        useCustomModel: Boolean = false
    ) {
        this.pageSegMode = segMode
        charWhitelist?.let { this.whitelist = it }
        this.enablePreprocessing = enablePreprocessing
        this.preprocessingType = preprocessingType
        this.useCustomModel = useCustomModel

        // Apply settings to current instance if initialized
        if (isInitialized.get() && tessBaseAPI != null) {
            tessBaseAPI?.setVariable("tessedit_char_whitelist", this.whitelist)
            tessBaseAPI?.setPageSegMode(this.pageSegMode.mode)

            // If using custom model, apply coupon-specific settings
            if (useCustomModel && tesseractTrainer.isCustomModelAvailable(customModelLangCode)) {
                configureCouponSpecificSettings(tessBaseAPI)
            }
        }
    }

    /**
     * Configure Tesseract with coupon-specific settings
     * @param api The TessBaseAPI instance to configure
     */
    private fun configureCouponSpecificSettings(api: TessBaseAPI?) {
        api?.let {
            // Set variables specific to coupon recognition

            // Improve number recognition (important for prices, dates, etc.)
            it.setVariable("classify_bln_numeric_mode", "1")

            // Improve recognition of text on colored backgrounds
            it.setVariable("textord_heavy_nr", "1")

            // Treat the image as a single text line (useful for coupon codes)
            it.setVariable("textord_single_height_mode", "1")

            // Adjust for typical coupon fonts
            it.setVariable("edges_max_children_per_outline", "40")

            // Improve recognition of bold text (common in coupon headers)
            it.setVariable("textord_min_linesize", "2.5")

            // Expand character set for special symbols in coupons
            val couponWhitelist = "$whitelist%$"
            it.setVariable("tessedit_char_whitelist", couponWhitelist)

            Log.d(TAG, "Applied coupon-specific Tesseract settings")
        }
    }

    /**
     * Set the custom model language code
     * @param langCode The language code for the custom model
     */
    fun setCustomModelLangCode(langCode: String) {
        this.customModelLangCode = langCode
    }

    /**
     * Check if a custom model is available
     * @return True if a custom model is available, false otherwise
     */
    fun isCustomModelAvailable(): Boolean {
        return tesseractTrainer.isCustomModelAvailable(customModelLangCode)
    }

    /**
     * Get the custom model language code
     * @return The language code for the custom model
     */
    fun getCustomModelLangCode(): String {
        return customModelLangCode
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
     * @param useCustomModel Whether to use a custom trained model if available
     * @return The recognized text
     */
    suspend fun processImageFromBitmap(
        bitmap: Bitmap,
        language: String = "eng",
        useCustomModel: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized.get()) {
                val initialized = initialize(language, OcrEngineMode.DEFAULT, useCustomModel)
                if (!initialized) {
                    Log.e(TAG, "Tesseract not initialized, cannot process image")
                    return@withContext ""
                }
            }

            // If we're using a different model than what's currently loaded
            if (this@TesseractOCRHelper.useCustomModel != useCustomModel) {
                // Re-initialize with the correct model
                cleanup()
                val initialized = initialize(language, OcrEngineMode.DEFAULT, useCustomModel)
                if (!initialized) {
                    Log.e(TAG, "Failed to switch Tesseract model, cannot process image")
                    return@withContext ""
                }
            }

            Log.d(TAG, "Processing bitmap with Tesseract OCR" +
                  (if (useCustomModel) " using custom model" else ""))

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
     * @param useCustomModel Whether to use a custom trained model if available
     * @return The recognized text
     */
    suspend fun processImageFast(
        bitmap: Bitmap,
        useCustomModel: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            // Save current settings
            val originalSegMode = pageSegMode
            val originalPreprocessingType = preprocessingType
            val originalUseCustomModel = this@TesseractOCRHelper.useCustomModel

            // Configure for speed
            configure(
                segMode = PageSegMode.SINGLE_BLOCK,
                enablePreprocessing = true,
                preprocessingType = PreprocessingType.GRAYSCALE,
                useCustomModel = useCustomModel
            )

            // Process the image
            val result = processImageFromBitmap(bitmap, "eng", useCustomModel)

            // Restore original settings
            configure(
                segMode = originalSegMode,
                enablePreprocessing = enablePreprocessing,
                preprocessingType = originalPreprocessingType,
                useCustomModel = originalUseCustomModel
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
     * @param useCustomModel Whether to use a custom trained model if available
     * @return The recognized text
     */
    suspend fun processImageAccurate(
        bitmap: Bitmap,
        useCustomModel: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        try {
            // Save current settings
            val originalSegMode = pageSegMode
            val originalPreprocessingType = preprocessingType
            val originalUseCustomModel = this@TesseractOCRHelper.useCustomModel

            // Configure for accuracy
            configure(
                segMode = PageSegMode.AUTO,
                enablePreprocessing = true,
                preprocessingType = PreprocessingType.ADAPTIVE,
                useCustomModel = useCustomModel
            )

            // Process the image
            val result = processImageFromBitmap(bitmap, "eng", useCustomModel)

            // Restore original settings
            configure(
                segMode = originalSegMode,
                enablePreprocessing = enablePreprocessing,
                preprocessingType = originalPreprocessingType,
                useCustomModel = originalUseCustomModel
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
     * @param useCustomModel Whether to use a custom trained model if available
     * @return The extracted coupon information
     */
    suspend fun extractCouponInfo(
        bitmap: Bitmap,
        language: String = "eng",
        useAccurateMode: Boolean = true,
        useCustomModel: Boolean = false
    ): CouponInfo = withContext(Dispatchers.IO) {
        try {
            // Process the image with appropriate mode
            val text = if (useAccurateMode) {
                if (useCustomModel) {
                    // Use custom model with accurate settings
                    processImageAccurate(bitmap, useCustomModel)
                } else {
                    processImageAccurate(bitmap)
                }
            } else {
                processImageFromBitmap(bitmap, language, useCustomModel)
            }

            if (text.isBlank()) {
                Log.w(TAG, "Tesseract returned empty text")
                return@withContext CouponInfo()
            }

            // Use the existing TextExtractor to extract coupon info
            val textExtractor = TextExtractor()
            val couponInfo = textExtractor.extractCouponInfo(text)

            // If using custom model, try to extract specific coupon regions
            if (useCustomModel && isCustomModelAvailable()) {
                try {
                    // Extract specific regions if the general extraction didn't work well
                    enhanceCouponInfoWithRegionExtraction(bitmap, couponInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in region-based extraction", e)
                }
            }

            return@withContext couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting coupon info with Tesseract", e)
            return@withContext CouponInfo()
        }
    }

    /**
     * Enhance coupon info by extracting specific regions
     * This is used with custom models to improve extraction of specific fields
     * @param bitmap The source bitmap
     * @param couponInfo The coupon info to enhance
     */
    private suspend fun enhanceCouponInfoWithRegionExtraction(
        bitmap: Bitmap,
        couponInfo: CouponInfo
    ) = withContext(Dispatchers.IO) {
        // Only proceed if we need to fill in missing information
        if (!couponInfo.storeName.isBlank() &&
            !couponInfo.description.isBlank() &&
            couponInfo.cashbackAmount != null &&
            !couponInfo.redeemCode.isNullOrBlank()) {
            return@withContext
        }

        // Create a region extractor
        val regionExtractor = RegionBasedExtractor(null, TextExtractor())

        // Try to extract store name if missing
        if (couponInfo.storeName.isBlank()) {
            val storeNameRegion = regionExtractor.detectStoreNameRegion(bitmap)
            if (storeNameRegion != null) {
                val regionBitmap = Bitmap.createBitmap(
                    bitmap,
                    storeNameRegion.left,
                    storeNameRegion.top,
                    storeNameRegion.width(),
                    storeNameRegion.height()
                )
                val text = processImageFromBitmap(regionBitmap, "eng", true)
                if (text.isNotBlank()) {
                    couponInfo.storeName = text.trim()
                }
            }
        }

        // Try to extract redeem code if missing
        if (couponInfo.redeemCode.isNullOrBlank()) {
            val codeRegion = regionExtractor.detectCodeRegion(bitmap)
            if (codeRegion != null) {
                val regionBitmap = Bitmap.createBitmap(
                    bitmap,
                    codeRegion.left,
                    codeRegion.top,
                    codeRegion.width(),
                    codeRegion.height()
                )
                // Use special settings for code recognition
                configure(PageSegMode.SINGLE_LINE, null, true, PreprocessingType.BINARIZATION, true)
                val text = processImageFromBitmap(regionBitmap, "eng", true)
                if (text.isNotBlank()) {
                    couponInfo.redeemCode = text.trim()
                }
                // Restore default settings
                configure(PageSegMode.AUTO, null, true, PreprocessingType.ADAPTIVE, true)
            }
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

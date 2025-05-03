package com.example.coupontracker.util

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import com.example.coupontracker.ui.screen.ApiType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Utility class to process images and extract coupon information using multiple OCR engines
 */
class ImageProcessor(
    private val context: Context,
    private val googleCloudVisionApiKey: String? = null,
    private val mistralApiKey: String? = null
) {
    private val TAG = "ImageProcessor"
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val textExtractor = TextExtractor()

    // Initialize services based on available API keys
    private var googleVisionHelper: EnhancedGoogleVisionHelper? = googleCloudVisionApiKey?.let {
        if (it.isBlank()) {
            Log.w(TAG, "Google Cloud Vision API key is blank, not initializing service")
            null
        } else {
            Log.d(TAG, "Initializing Google Cloud Vision service with key: ${it.take(5)}...")
            EnhancedGoogleVisionHelper(it, context)
        }
    }

    private var mistralOcrService: MistralOcrService? = mistralApiKey?.let {
        if (it.isBlank()) {
            Log.w(TAG, "Mistral API key is blank, not initializing service")
            null
        } else {
            Log.d(TAG, "Initializing Mistral OCR service with key: ${it.take(5)}...")
            MistralOcrService(it)
        }
    }

    // Combined OCR service using both Google Vision and Mistral AI
    private var combinedOcrService: CombinedOCRService? =
        if (googleVisionHelper != null && mistralOcrService != null) {
            Log.d(TAG, "Initializing Combined OCR service with Google Vision and Mistral AI")
            CombinedOCRService(googleVisionHelper!!, mistralOcrService!!)
        } else {
            Log.w(TAG, "Unable to initialize Combined OCR service, missing required services")
            null
        }

    // Super OCR service using all available technologies
    private var superOcrService: SuperOCRService? = SuperOCRService(
        context,
        googleCloudVisionApiKey,
        mistralApiKey
    )

    // Tesseract OCR helper
    private var tesseractOCRHelper: TesseractOCRHelper = TesseractOCRHelper(context)
    private var tesseractLanguageManager: TesseractLanguageManager = TesseractLanguageManager(context)

    // Track the currently selected API
    private var selectedApiType: ApiType = ApiType.GOOGLE_CLOUD_VISION

    // Track API availability
    private var googleVisionAvailable = false
    private var mistralApiAvailable = false
    private var combinedServiceAvailable = false
    private var superServiceAvailable = false

    // Secure preferences manager
    private val securePreferencesManager = SecurePreferencesManager(context)

    // SharedPreferences listener
    private val sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                SecurePreferencesManager.KEY_SELECTED_API -> {
                    val savedApiType = securePreferencesManager.getString(
                        SecurePreferencesManager.KEY_SELECTED_API,
                        ApiType.GOOGLE_CLOUD_VISION.name
                    )
                    selectedApiType = try {
                        ApiType.valueOf(savedApiType ?: ApiType.GOOGLE_CLOUD_VISION.name)
                    } catch (e: Exception) {
                        ApiType.GOOGLE_CLOUD_VISION
                    }
                    Log.d(TAG, "API selection changed to: ${selectedApiType.name}")
                }
                SecurePreferencesManager.KEY_GOOGLE_CLOUD_VISION_API_KEY -> {
                    val newApiKey = securePreferencesManager.getString(
                        SecurePreferencesManager.KEY_GOOGLE_CLOUD_VISION_API_KEY
                    )
                    refreshGoogleVisionHelper(newApiKey)
                    updateCombinedService()
                    updateSuperOcrService()
                }
                SecurePreferencesManager.KEY_MISTRAL_API_KEY -> {
                    val newApiKey = securePreferencesManager.getString(
                        SecurePreferencesManager.KEY_MISTRAL_API_KEY
                    )
                    refreshMistralService(newApiKey)
                    updateCombinedService()
                    updateSuperOcrService()
                }
            }
        }

    init {
        // Initialize secure preferences manager
        securePreferencesManager.initialize()

        // Check for device security
        if (securePreferencesManager.isDeviceRooted()) {
            Log.w(TAG, "WARNING: Device appears to be rooted, API keys may be at risk")
        }

        if (!securePreferencesManager.checkAppIntegrity()) {
            Log.w(TAG, "WARNING: App integrity check failed, app may be tampered with")
        }

        // Get saved API type
        val savedApiType = securePreferencesManager.getString(
            SecurePreferencesManager.KEY_SELECTED_API,
            ApiType.GOOGLE_CLOUD_VISION.name
        )

        selectedApiType = try {
            ApiType.valueOf(savedApiType ?: ApiType.GOOGLE_CLOUD_VISION.name)
        } catch (e: Exception) {
            ApiType.GOOGLE_CLOUD_VISION
        }

        // Register for preference changes
        securePreferencesManager.registerOnSharedPreferenceChangeListener(sharedPreferencesListener)

        // Check if key rotation is needed
        val daysUntilRotation = securePreferencesManager.getDaysUntilKeyRotation()
        if (daysUntilRotation < 7) {
            Log.i(TAG, "API keys should be rotated soon (in $daysUntilRotation days)")
        }

        Log.d(TAG, "ImageProcessor initialized. Selected API: ${selectedApiType.name}")
        Log.d(TAG, "Google Cloud Vision API: ${if (googleVisionHelper != null) "available" else "unavailable"}")
        Log.d(TAG, "Mistral API: ${if (mistralOcrService != null) "available" else "unavailable"}")
        Log.d(TAG, "Combined OCR Service: ${if (combinedOcrService != null) "available" else "unavailable"}")

        // Test services in a background thread
        MainScope().launch {
            testApiServices()
        }
    }

    /**
     * Test all available API services to check their actual availability
     */
    private suspend fun testApiServices() {
        try {
            // Test Google Vision API
            googleVisionAvailable = googleVisionHelper != null

            // Test Mistral API if available
            val localMistralService = mistralOcrService
            if (localMistralService != null) {
                Log.d(TAG, "Testing Mistral API connection...")
                mistralApiAvailable = try {
                    localMistralService.testApiConnection()
                } catch (e: Exception) {
                    Log.e(TAG, "Mistral API test failed", e)
                    false
                }
                Log.d(TAG, "Mistral API available: $mistralApiAvailable")
            } else {
                mistralApiAvailable = false
            }

            // Test combined service if both APIs are available
            val localCombinedService = combinedOcrService
            if (localCombinedService != null && googleVisionAvailable) {
                if (mistralApiAvailable) {
                    Log.d(TAG, "Testing Combined OCR Service...")
                    try {
                        localCombinedService.testMistralApiConnection()
                        combinedServiceAvailable = true
                        Log.d(TAG, "Combined OCR Service available")
                    } catch (e: Exception) {
                        Log.e(TAG, "Combined OCR Service test failed", e)
                        combinedServiceAvailable = false
                    }
                } else {
                    Log.w(TAG, "Combined OCR Service unavailable - Mistral API not working")
                    combinedServiceAvailable = false
                }
            } else {
                combinedServiceAvailable = false
            }

            // Test Super OCR service
            try {
                superOcrService?.testApiAvailability()
                superServiceAvailable = true
                Log.d(TAG, "Super OCR Service tested successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Super OCR Service test failed", e)
                superServiceAvailable = false
            }

            Log.d(TAG, "API Service availability: " +
                  "Google Vision: $googleVisionAvailable, " +
                  "Mistral: $mistralApiAvailable, " +
                  "Combined: $combinedServiceAvailable, " +
                  "Super: $superServiceAvailable")
        } catch (e: Exception) {
            Log.e(TAG, "Error testing API services", e)
        }
    }

    /**
     * Process an image URI and extract coupon information based on the selected API priority
     * @param imageUri The URI of the image to process
     * @return CouponInfo object containing extracted information
     */
    suspend fun processImage(imageUri: Uri): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing image: $imageUri")
            val bitmap = getBitmapFromUri(imageUri)
            Log.d(TAG, "Successfully loaded bitmap from URI")

            return@withContext processImage(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }

    /**
     * Process a bitmap image and extract coupon information
     * @param bitmap The bitmap to process
     * @return The extracted coupon information
     */
    suspend fun processImage(bitmap: Bitmap): CouponInfo = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Processing bitmap image")

            // Process according to selected API priority
            val result = when (selectedApiType) {
                ApiType.SUPER -> {
                    // Use the Super OCR service that combines all technologies
                    if (superOcrService != null) {
                        try {
                            Log.d(TAG, "Using Super OCR Service (All Technologies)")
                            val result = trySuperOcr(bitmap)
                            if (result != null) {
                                result
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Super OCR Service, falling back to Combined OCR", e)
                            null
                        }
                    } else {
                        Log.w(TAG, "Super OCR Service not available, check API key configuration")
                        null
                    }

                    // Fall back to Combined OCR if Super OCR fails
                    val combinedResult = if (combinedOcrService != null) {
                        try {
                            Log.d(TAG, "Falling back to Combined OCR Service")
                            val result = tryCombinedOcr(bitmap)
                            if (result != null) {
                                result
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Combined OCR Service, falling back to individual services", e)
                            null
                        }
                    } else {
                        null
                    }

                    // If we got a result from either Super OCR or Combined OCR, return it
                    // Otherwise, continue with fallback to other methods
                    combinedResult ?: tryMlKit(bitmap)

                    // Continue with fallback to other methods...
                }

                ApiType.COMBINED -> {
                    // Use the combined OCR service directly if available
                    if (combinedOcrService != null) {
                        try {
                            Log.d(TAG, "Using Combined OCR Service (Google Vision + Mistral validation)")
                            val result = tryCombinedOcr(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Combined OCR Service, falling back to Google Cloud Vision", e)
                        }
                    } else {
                        Log.w(TAG, "Combined OCR Service not available, check API key configuration")
                    }

                    // Try Google Cloud Vision next
                    if (googleVisionHelper != null) {
                        try {
                            Log.d(TAG, "Using Google Cloud Vision API for text extraction")
                            val result = tryGoogleCloudVision(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Google Cloud Vision API, trying Mistral", e)
                        }
                    }

                    // Try Mistral API next
                    if (mistralOcrService != null) {
                        try {
                            Log.d(TAG, "Using Mistral OCR API for text extraction")
                            val result = tryMistralApi(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Mistral OCR API, falling back to ML Kit", e)
                        }
                    }

                    // Finally, use ML Kit
                    return@withContext tryMlKit(bitmap)
                }

                ApiType.GOOGLE_CLOUD_VISION -> {
                    // Try combined service first if available (Google Vision + Mistral validation)
                    if (combinedOcrService != null) {
                        try {
                            Log.d(TAG, "Using Combined OCR Service with validation")
                            val result = tryCombinedOcr(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Combined OCR Service, falling back to individual services", e)
                        }
                    }

                    // Try Google Cloud Vision next
                    if (googleVisionHelper != null) {
                        try {
                            Log.d(TAG, "Using Google Cloud Vision API for text extraction")
                            val result = tryGoogleCloudVision(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Google Cloud Vision API, trying next option", e)
                        }
                    } else {
                        Log.d(TAG, "Google Cloud Vision service not available, trying next option")
                    }

                    // Try Mistral API next
                    if (mistralOcrService != null) {
                        try {
                            Log.d(TAG, "Using Mistral OCR API for text extraction")
                            val result = tryMistralApi(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Mistral OCR API, falling back to ML Kit", e)
                        }
                    } else {
                        Log.d(TAG, "Mistral OCR service not available, falling back to ML Kit")
                    }

                    // Finally, use ML Kit
                    return@withContext tryMlKit(bitmap)
                }

                ApiType.MISTRAL -> {
                    // Try combined service first if available (this is always useful)
                    if (combinedOcrService != null) {
                        try {
                            Log.d(TAG, "Using Combined OCR Service with validation")
                            val result = tryCombinedOcr(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Combined OCR Service, falling back to individual services", e)
                        }
                    }

                    // Try Mistral API next
                    if (mistralOcrService != null) {
                        try {
                            Log.d(TAG, "Using Mistral OCR API for text extraction")
                            val result = tryMistralApi(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Mistral OCR API, trying next option", e)
                        }
                    } else {
                        Log.d(TAG, "Mistral OCR service not available, trying next option")
                    }

                    // Try Google Cloud Vision next
                    if (googleVisionHelper != null) {
                        try {
                            Log.d(TAG, "Using Google Cloud Vision API for text extraction")
                            val result = tryGoogleCloudVision(bitmap)
                            if (result != null) {
                                return@withContext result
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error using Google Cloud Vision API, falling back to ML Kit", e)
                        }
                    } else {
                        Log.d(TAG, "Google Cloud Vision service not available, falling back to ML Kit")
                    }

                    // Finally, use ML Kit
                    return@withContext tryMlKit(bitmap)
                }

                ApiType.ML_KIT -> {
                    // Use ML Kit directly
                    Log.d(TAG, "Using ML Kit directly as selected OCR method")
                    return@withContext tryMlKit(bitmap)
                }

                ApiType.TESSERACT -> {
                    // Use Tesseract OCR directly
                    Log.d(TAG, "Using Tesseract OCR directly as selected OCR method")
                    val result = tryTesseract(bitmap)
                    if (result != null) {
                        result
                    } else {
                        // Fall back to ML Kit if Tesseract fails
                        Log.d(TAG, "Tesseract OCR failed, falling back to ML Kit")
                        tryMlKit(bitmap)
                    }
                }
            }

            // Return the result from the when expression
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process image", e)
            throw IOException("Failed to process image: ${e.message}", e)
        }
    }

    /**
     * Try processing with Super OCR Service (All Technologies)
     */
    private suspend fun trySuperOcr(bitmap: Bitmap): CouponInfo? {
        return try {
            val service = superOcrService ?: return null

            Log.d(TAG, "Extracting info with Super OCR Service")
            val couponInfo = service.extractCouponInfo(bitmap)

            // Check if we got meaningful results
            if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                Log.w(TAG, "Super OCR Service returned empty results")
                return null
            }

            Log.d(TAG, "Successfully extracted coupon info using Super OCR Service: $couponInfo")
            couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Super OCR Service", e)
            null
        }
    }

    /**
     * Try processing with Combined OCR Service (Google Vision + Mistral validation)
     */
    private suspend fun tryCombinedOcr(bitmap: Bitmap): CouponInfo? {
        return try {
            val service = combinedOcrService ?: return null

            Log.d(TAG, "Extracting info with Combined OCR Service")
            val couponInfo = service.extractCouponInfoWithValidation(bitmap)

            // Check if we got meaningful results
            if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                Log.w(TAG, "Combined OCR Service returned empty results")
                return null
            }

            Log.d(TAG, "Successfully extracted coupon info using Combined OCR Service: $couponInfo")
            couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Combined OCR Service", e)
            null
        }
    }

    /**
     * Try processing with Google Cloud Vision API
     */
    private suspend fun tryGoogleCloudVision(bitmap: Bitmap): CouponInfo? {
        return try {
            val helper = googleVisionHelper
            if (helper == null) {
                Log.e(TAG, "Google Cloud Vision helper is null, check API key configuration")
                return null
            }

            Log.d(TAG, "Extracting text with Google Cloud Vision")
            val text = helper.extractText(bitmap)

            if (text.isBlank()) {
                Log.w(TAG, "Google Cloud Vision returned empty text")
                return null
            }

            Log.d(TAG, "Google Cloud Vision text result (length: ${text.length})")
            Log.d(TAG, "Text sample: ${text.take(200)}...")

            val couponInfo = textExtractor.extractCouponInfo(text)

            // Check if we got meaningful results
            if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                Log.w(TAG, "Google Cloud Vision returned empty structured results")
                return null
            }

            Log.d(TAG, "Successfully extracted coupon info using Google Cloud Vision: $couponInfo")
            couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Google Cloud Vision", e)
            null
        }
    }

    /**
     * Try processing with Mistral API
     */
    private suspend fun tryMistralApi(bitmap: Bitmap): CouponInfo? {
        return try {
            val service = mistralOcrService ?: return null

            Log.d(TAG, "Extracting info with Mistral API")
            val couponInfo = service.extractCouponInfo(bitmap)

            // Check if we got meaningful results
            if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                Log.w(TAG, "Mistral API returned empty results")
                return null
            }

            Log.d(TAG, "Successfully extracted coupon info using Mistral API: $couponInfo")
            couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Mistral API", e)
            null
        }
    }

    /**
     * Try processing with Tesseract OCR
     */
    private suspend fun tryTesseract(bitmap: Bitmap): CouponInfo? {
        return try {
            // Get the selected language
            val selectedLanguage = tesseractLanguageManager.getSelectedLanguage()
            val languageName = tesseractLanguageManager.getLanguageDisplayName(selectedLanguage)

            Log.d(TAG, "Extracting info with Tesseract OCR using language: $languageName")

            // Try with accurate mode first
            var couponInfo = tesseractOCRHelper.extractCouponInfo(bitmap, selectedLanguage, true)

            // Check if we got meaningful results
            if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {

                Log.w(TAG, "Tesseract OCR accurate mode returned empty results, trying fast mode")

                // Try with fast mode as fallback
                couponInfo = tesseractOCRHelper.extractCouponInfo(bitmap, selectedLanguage, false)

                // Check again
                if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                    couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {

                    // If selected language is not English, try English as fallback
                    if (selectedLanguage != "eng") {
                        Log.w(TAG, "Tesseract OCR with $languageName returned empty results, trying English")
                        couponInfo = tesseractOCRHelper.extractCouponInfo(bitmap, "eng", true)
                    }

                    // Final check
                    if (couponInfo.storeName.isBlank() && couponInfo.description.isBlank() &&
                        couponInfo.cashbackAmount == null && couponInfo.redeemCode.isNullOrBlank()) {
                        Log.w(TAG, "Tesseract OCR returned empty results with all attempts")
                        return null
                    }
                }
            }

            Log.d(TAG, "Successfully extracted coupon info using Tesseract OCR: $couponInfo")
            couponInfo
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Tesseract OCR", e)
            null
        }
    }

    /**
     * Try processing with ML Kit (guaranteed fallback)
     */
    private suspend fun tryMlKit(bitmap: Bitmap): CouponInfo {
        Log.d(TAG, "Using ML Kit for text extraction")
        val text = recognizeText(bitmap)
        Log.d(TAG, "ML Kit recognized text: ${text.take(100)}...")

        val couponInfo = textExtractor.extractCouponInfo(text)
        Log.d(TAG, "Successfully extracted coupon info using ML Kit: $couponInfo")
        return couponInfo
    }

    /**
     * Get a bitmap from a URI using the appropriate method based on Android version
     * @param uri The URI to get the bitmap from
     * @return The bitmap
     */
    private fun getBitmapFromUri(uri: Uri): Bitmap {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For Android 9 (Pie) and above
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                // For older versions
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting bitmap from URI", e)
            throw IOException("Failed to load image: ${e.message}", e)
        }
    }

    /**
     * Recognize text in a bitmap using ML Kit
     * @param bitmap The bitmap to process
     * @return The recognized text
     */
    private suspend fun recognizeText(bitmap: Bitmap): String = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    Log.d(TAG, "ML Kit text recognition successful, text length: ${fullText.length}")
                    continuation.resume(fullText)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit text recognition failed", e)
                    continuation.resumeWithException(e)
                }

            continuation.invokeOnCancellation {
                Log.d(TAG, "Text recognition task cancelled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in text recognition", e)
            continuation.resumeWithException(e)
        }
    }

    /**
     * Update combined service if both required services are available
     */
    private fun updateCombinedService() {
        Log.d(TAG, "Updating combined OCR service")
        Log.d(TAG, "Google Vision available: ${googleVisionHelper != null}")
        Log.d(TAG, "Mistral API available: ${mistralOcrService != null}")

        combinedOcrService = if (googleVisionHelper != null && mistralOcrService != null) {
            Log.d(TAG, "Updating Combined OCR service with current API services")
            CombinedOCRService(googleVisionHelper!!, mistralOcrService!!)
        } else {
            Log.w(TAG, "Unable to update Combined OCR service, missing required services")
            null
        }

        Log.d(TAG, "Combined OCR Service initialized: ${combinedOcrService != null}")

        // Test services in a background thread
        MainScope().launch {
            testApiServices()
        }
    }

    /**
     * Refresh Google Vision Helper with new API key
     */
    private fun refreshGoogleVisionHelper(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Google Cloud Vision API key is blank, disabling service")
            googleVisionHelper = null
        } else {
            Log.d(TAG, "Refreshing Google Cloud Vision service with key: ${apiKey.take(5)}...")
            googleVisionHelper = EnhancedGoogleVisionHelper(apiKey, context)
        }
        updateCombinedService()
    }

    /**
     * Refresh Mistral OCR Service with new API key
     */
    private fun refreshMistralService(apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Mistral API key is blank, disabling service")
            mistralOcrService = null
        } else {
            Log.d(TAG, "Refreshing Mistral OCR service with key: ${apiKey.take(5)}...")
            mistralOcrService = MistralOcrService(apiKey)
        }
        updateCombinedService()
    }

    /**
     * Update Super OCR Service with current API keys
     */
    private fun updateSuperOcrService() {
        Log.d(TAG, "Updating Super OCR service")

        superOcrService = SuperOCRService(
            context,
            googleCloudVisionApiKey,
            mistralApiKey
        )

        // Test service in a background thread
        MainScope().launch {
            try {
                superOcrService?.testApiAvailability()
                superServiceAvailable = true
                Log.d(TAG, "Super OCR Service initialized and tested")
            } catch (e: Exception) {
                Log.e(TAG, "Error testing Super OCR Service", e)
                superServiceAvailable = false
            }
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        securePreferencesManager.unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        tesseractOCRHelper.cleanup()
        Log.d(TAG, "ImageProcessor cleanup completed")
    }

    /**
     * Process a single coupon bitmap
     */
    suspend fun processSingleCoupon(bitmap: Bitmap, origImageUri: Uri?, preferredApiType: ApiType?): CouponInfo {
        val apiType = preferredApiType ?: this.selectedApiType
        Log.d(TAG, "Processing single coupon with API type: $apiType")

        return when (apiType) {
            ApiType.SUPER -> {
                try {
                    // Use the Super OCR service if available
                    if (superOcrService != null) {
                        Log.d(TAG, "Using Super OCR Service for single coupon")
                        return superOcrService!!.extractCouponInfo(bitmap)
                    } else {
                        Log.w(TAG, "Super OCR Service not available, falling back to Combined")
                        // Fall back to Combined if Super is not available
                        if (combinedOcrService != null) {
                            return combinedOcrService!!.extractCouponInfoWithValidation(bitmap)
                        } else {
                            // Fall back to Google Vision if Combined is not available
                            Log.w(TAG, "Combined OCR Service not available, falling back to Google Vision")
                            return processSingleCoupon(bitmap, origImageUri, ApiType.GOOGLE_CLOUD_VISION)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing with Super OCR Service", e)
                    // Fall back to Google Vision
                    return processSingleCoupon(bitmap, origImageUri, ApiType.GOOGLE_CLOUD_VISION)
                }
            }

            ApiType.GOOGLE_CLOUD_VISION -> {
                try {
                    // First extract raw text with Google Vision
                    val rawText = googleVisionHelper?.extractText(bitmap) ?: ""

                    // Try template-based extraction first
                    val templateExtractor = CouponTemplateExtractor()
                    val template = templateExtractor.identifyTemplate(bitmap, rawText)

                    if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                        Log.d(TAG, "Using template-based extraction with identified template")
                        templateExtractor.extractFromTemplate(bitmap, rawText, template)
                    } else {
                        // Fall back to region-based extraction
                        Log.d(TAG, "Template not identified, using region-based extractor")
                        val regionExtractor = RegionBasedExtractor(googleVisionHelper)
                        regionExtractor.extractCouponInfo(bitmap, rawText)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Advanced extraction failed, falling back to standard", e)
                    extractWithGoogleVision(bitmap, origImageUri)
                }
            }
            ApiType.MISTRAL -> {
                try {
                    // Extract text with Mistral
                    val rawText = mistralOcrService?.extractTextFromImage(bitmap) ?: ""

                    if (rawText.isBlank()) {
                        Log.w(TAG, "Mistral returned empty text, falling back to Google Vision")
                        val googleText = googleVisionHelper?.extractText(bitmap) ?: ""
                        val regionExtractor = RegionBasedExtractor(googleVisionHelper)
                        regionExtractor.extractCouponInfo(bitmap, googleText)
                    } else {
                        // Try template extraction first
                        val templateExtractor = CouponTemplateExtractor()
                        val template = templateExtractor.identifyTemplate(bitmap, rawText)

                        if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                            Log.d(TAG, "Using template-based extraction with Mistral text")
                            templateExtractor.extractFromTemplate(bitmap, rawText, template)
                        } else {
                            // Fall back to region-based extraction
                            Log.d(TAG, "No template identified with Mistral text, using region-based extraction")
                            val regionExtractor = RegionBasedExtractor(googleVisionHelper, TextExtractor())
                            regionExtractor.extractCouponInfo(bitmap, rawText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing with Mistral", e)
                    // Fall back to region-based with Google Vision
                    val regionExtractor = RegionBasedExtractor(googleVisionHelper)
                    regionExtractor.extractCouponInfo(bitmap)
                }
            }
            ApiType.COMBINED -> {
                try {
                    Log.d(TAG, "Using combined OCR service")
                    val localCombinedService = combinedOcrService
                    if (localCombinedService != null) {
                        // First get the raw text using combined service
                        val rawText = localCombinedService.extractTextWithValidation(bitmap)

                        // Check if this is a known template
                        val templateExtractor = CouponTemplateExtractor()
                        val template = templateExtractor.identifyTemplate(bitmap, rawText)

                        val result = if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                            // Use template-based extraction for known templates
                            Log.d(TAG, "Using template-based extraction with combined OCR")
                            templateExtractor.extractFromTemplate(bitmap, rawText, template)
                        } else {
                            // For unknown templates, use the combined service extraction
                            Log.d(TAG, "Using combined OCR service extraction")
                            localCombinedService.extractCouponInfoWithValidation(bitmap)
                        }

                        // Always apply region-based extraction to refine the results
                        val regionExtractor = RegionBasedExtractor(googleVisionHelper, TextExtractor())
                        val regionResult = regionExtractor.extractCouponInfo(bitmap, rawText)

                        // Merge results, preferring the most reliable data
                        CouponInfo(
                            // For store name, prefer template or region-based over combined
                            storeName = when {
                                template != CouponTemplateExtractor.CouponTemplate.Unknown -> result.storeName
                                regionResult.storeName != "Unknown Store" -> regionResult.storeName
                                else -> result.storeName
                            },
                            // For description, prefer non-empty results
                            description = result.description.takeIf { it.isNotBlank() }
                                ?: regionResult.description,
                            // For dates, take the non-null one
                            expiryDate = result.expiryDate ?: regionResult.expiryDate,
                            // For amounts and discount type, use consistent pairing
                            cashbackAmount = when {
                                template != CouponTemplateExtractor.CouponTemplate.Unknown -> result.cashbackAmount
                                else -> result.cashbackAmount ?: regionResult.cashbackAmount
                            },
                            // Ensure discountType matches cashbackAmount
                            discountType = when {
                                template != CouponTemplateExtractor.CouponTemplate.Unknown -> result.discountType
                                result.cashbackAmount != null -> result.discountType
                                else -> regionResult.discountType
                            },
                            // For redeem codes, prefer the longer one if both exist
                            redeemCode = if (result.redeemCode != null && regionResult.redeemCode != null) {
                                if (result.redeemCode.length >= regionResult.redeemCode.length)
                                    result.redeemCode else regionResult.redeemCode
                            } else result.redeemCode ?: regionResult.redeemCode,
                            // Take other fields from the most reliable source
                            category = result.category ?: regionResult.category,
                            rating = result.rating ?: regionResult.rating,
                            status = result.status ?: regionResult.status
                        )
                    } else {
                        Log.w(TAG, "Combined OCR service is null, using template and region-based extraction")
                        // Get raw text from Google Vision
                        val rawText = googleVisionHelper?.extractText(bitmap) ?: ""

                        // Try template-based extraction first
                        val templateExtractor = CouponTemplateExtractor()
                        val template = templateExtractor.identifyTemplate(bitmap, rawText)

                        if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                            templateExtractor.extractFromTemplate(bitmap, rawText, template)
                        } else {
                            val regionExtractor = RegionBasedExtractor(googleVisionHelper)
                            regionExtractor.extractCouponInfo(bitmap, rawText)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing with combined OCR", e)
                    // Fall back to template extraction with Google Vision
                    val rawText = googleVisionHelper?.extractText(bitmap) ?: ""
                    val templateExtractor = CouponTemplateExtractor()
                    templateExtractor.extractFromTemplate(bitmap, rawText)
                }
            }
            ApiType.ML_KIT -> {
                val textExtractor = TextExtractor()

                try {
                    val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    val result = processMLKitTask(textRecognizer.process(inputImage))
                    val text = result.text

                    if (text.isNotBlank()) {
                        // First try template-based extraction
                        val templateExtractor = CouponTemplateExtractor()
                        val template = templateExtractor.identifyTemplate(bitmap, text)

                        if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                            Log.d(TAG, "Using template-based extraction with device OCR")
                            templateExtractor.extractFromTemplate(bitmap, text, template)
                        } else {
                            // Fall back to region-based extraction
                            Log.d(TAG, "Using region-based extraction with device OCR")
                            val regionExtractor = RegionBasedExtractor(googleVisionHelper, textExtractor)
                            regionExtractor.extractCouponInfo(bitmap, text)
                        }
                    } else {
                        Log.w(TAG, "Device OCR returned empty text, falling back to Google Vision")
                        val rawText = googleVisionHelper?.extractText(bitmap) ?: ""
                        val templateExtractor = CouponTemplateExtractor()
                        templateExtractor.extractFromTemplate(bitmap, rawText)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error using device OCR, falling back to Google Vision", e)
                    val rawText = googleVisionHelper?.extractText(bitmap) ?: ""
                    val templateExtractor = CouponTemplateExtractor()
                    templateExtractor.extractFromTemplate(bitmap, rawText)
                }
            }

            ApiType.TESSERACT -> {
                try {
                    Log.d(TAG, "Using Tesseract OCR for single coupon")
                    val text = tesseractOCRHelper.processImageFromBitmap(bitmap)

                    if (text.isNotBlank()) {
                        // First try template-based extraction
                        val templateExtractor = CouponTemplateExtractor()
                        val template = templateExtractor.identifyTemplate(bitmap, text)

                        if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                            Log.d(TAG, "Using template-based extraction with Tesseract OCR")
                            templateExtractor.extractFromTemplate(bitmap, text, template)
                        } else {
                            // Extract coupon info from text
                            Log.d(TAG, "Using text extraction with Tesseract OCR")
                            val textExtractor = TextExtractor()
                            textExtractor.extractCouponInfo(text)
                        }
                    } else {
                        Log.w(TAG, "Tesseract OCR returned empty text, falling back to ML Kit")
                        return processSingleCoupon(bitmap, origImageUri, ApiType.ML_KIT)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error using Tesseract OCR, falling back to ML Kit", e)
                    return processSingleCoupon(bitmap, origImageUri, ApiType.ML_KIT)
                }
            }
        }
    }

    /**
     * Extract coupon information using Google Vision API
     */
    private suspend fun extractWithGoogleVision(bitmap: Bitmap, origImageUri: Uri?): CouponInfo {
        try {
            Log.d(TAG, "Extracting with Google Vision API")

            // Try using the helper if available
            val helper = googleVisionHelper
            if (helper != null) {
                val extractedText = helper.extractText(bitmap)
                if (extractedText.isNotBlank()) {
                    return textExtractor.extractCouponInfo(extractedText)
                }
            }

            // If we get here, either the helper was null or extraction failed
            // Fall back to ML Kit
            Log.w(TAG, "Google Vision extraction failed, falling back to ML Kit")
            return extractWithMlKit(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Google Vision extraction", e)
            return extractWithMlKit(bitmap)
        }
    }

    /**
     * Extract coupon information using Mistral API
     */
    private suspend fun extractWithMistral(bitmap: Bitmap): CouponInfo {
        try {
            Log.d(TAG, "Extracting with Mistral API")

            // Try using the service if available
            val service = mistralOcrService
            if (service != null) {
                return service.extractCouponInfo(bitmap)
            }

            // If we get here, the service was null
            // Fall back to Google Vision
            Log.w(TAG, "Mistral service unavailable, falling back to Google Vision")
            return extractWithGoogleVision(bitmap, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in Mistral extraction", e)
            return extractWithGoogleVision(bitmap, null)
        }
    }

    /**
     * Extract coupon information using ML Kit
     */
    private suspend fun extractWithMlKit(bitmap: Bitmap): CouponInfo {
        try {
            Log.d(TAG, "Extracting with ML Kit")

            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val visionText = processMLKitTask(textRecognizer.process(inputImage))

            val extractedText = visionText.text
            return textExtractor.extractCouponInfo(extractedText)
        } catch (e: Exception) {
            Log.e(TAG, "Error in ML Kit extraction", e)
            // Create a minimal fallback coupon info
            return CouponInfo(
                storeName = "Unknown Store",
                description = "Error extracting coupon data",
                expiryDate = null,
                cashbackAmount = null,
                redeemCode = null
            )
        }
    }

    /**
     * Process ML Kit Task and convert it to a suspending coroutine
     */
    private suspend fun <T> processMLKitTask(task: com.google.android.gms.tasks.Task<T>): T {
        return suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { result ->
                continuation.resume(result)
            }.addOnFailureListener { exception ->
                continuation.resumeWithException(exception)
            }

            continuation.invokeOnCancellation {
                // No need to cancel ML Kit task, it will be garbage collected
            }
        }
    }
}
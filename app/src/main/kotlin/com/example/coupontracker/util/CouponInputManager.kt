package com.example.coupontracker.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.example.coupontracker.data.model.Coupon
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Manager class for handling various coupon input methods
 */
class CouponInputManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CouponInputManager"
        private const val TEMP_FILE_PREFIX = "coupon_"
        private const val TEMP_FILE_SUFFIX_IMAGE = ".jpg"
        private const val TEMP_FILE_SUFFIX_PDF = ".pdf"
        private const val SCREENSHOTS_FOLDER = "Screenshots"
    }
    
    private val imageProcessor = ImageProcessor(context)
    private val contentResolver: ContentResolver = context.contentResolver
    private var screenshotObserver: ContentObserver? = null
    
    // Barcode scanner options
    private val barcodeOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417
        )
        .build()
    
    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(barcodeOptions)
    
    /**
     * Process an image URI and extract coupon information
     * @param imageUri The URI of the image to process
     * @return The extracted coupon information
     */
    suspend fun processCouponFromImageUri(imageUri: Uri): Coupon {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon from image URI: $imageUri")
                
                // Check if this is a PDF
                if (isPdfFile(imageUri)) {
                    return@withContext processPdfUri(imageUri)
                }
                
                // Process as a regular image
                val bitmap = loadBitmapFromUri(imageUri)
                return@withContext processCouponFromBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon from image URI", e)
                throw e
            }
        }
    }
    
    /**
     * Process a bitmap and extract coupon information
     * @param bitmap The bitmap to process
     * @return The extracted coupon information
     */
    suspend fun processCouponFromBitmap(bitmap: Bitmap): Coupon {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon from bitmap")
                
                // Try to scan for barcodes first
                val barcodeResult = scanForBarcodes(bitmap)
                if (barcodeResult != null) {
                    Log.d(TAG, "Barcode detected: ${barcodeResult.rawValue}")
                    
                    // If it's a URL, try to process it
                    if (barcodeResult.valueType == Barcode.TYPE_URL) {
                        val url = barcodeResult.url?.url
                        if (url != null) {
                            try {
                                return@withContext processCouponFromUrl(url)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to process URL from barcode, continuing with image processing", e)
                            }
                        }
                    }
                    
                    // If it's a text value, it might be a coupon code
                    if (barcodeResult.valueType == Barcode.TYPE_TEXT) {
                        val code = barcodeResult.rawValue
                        if (!code.isNullOrBlank()) {
                            // Create a basic coupon with just the code
                            return@withContext Coupon(
                                id = 0,
                                storeName = "Unknown Store",
                                description = "Scanned from QR code",
                                cashbackAmount = 0.0,
                                expiryDate = Date(),
                                redeemCode = code,
                                createdDate = Date(),
                                status = "Active"
                            )
                        }
                    }
                }
                
                // Process with OCR
                val couponInfo = imageProcessor.processImage(bitmap)
                return@withContext couponInfo
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon from bitmap", e)
                throw e
            }
        }
    }
    
    /**
     * Process a PDF file and extract coupon information from the first page
     * @param pdfUri The URI of the PDF file
     * @return The extracted coupon information
     */
    suspend fun processPdfUri(pdfUri: Uri): Coupon {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon from PDF: $pdfUri")
                
                val parcelFileDescriptor = contentResolver.openFileDescriptor(pdfUri, "r")
                    ?: throw IOException("Cannot open PDF file")
                
                val pdfRenderer = PdfRenderer(parcelFileDescriptor)
                
                // Process the first page
                if (pdfRenderer.pageCount > 0) {
                    val page = pdfRenderer.openPage(0)
                    
                    // Create bitmap of appropriate size
                    val bitmap = Bitmap.createBitmap(
                        page.width, page.height, Bitmap.Config.ARGB_8888
                    )
                    
                    // Render the page to the bitmap
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    
                    // Close the page
                    page.close()
                    
                    // Process the bitmap
                    val coupon = processCouponFromBitmap(bitmap)
                    
                    // Close the renderer
                    pdfRenderer.close()
                    parcelFileDescriptor.close()
                    
                    return@withContext coupon
                } else {
                    pdfRenderer.close()
                    parcelFileDescriptor.close()
                    throw IOException("PDF has no pages")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon from PDF", e)
                throw e
            }
        }
    }
    
    /**
     * Process a URL and extract coupon information
     * @param urlString The URL to process
     * @return The extracted coupon information
     */
    suspend fun processCouponFromUrl(urlString: String): Coupon {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon from URL: $urlString")
                
                // Download the content from the URL
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 15000
                connection.connect()
                
                val contentType = connection.contentType ?: ""
                
                when {
                    contentType.startsWith("image/") -> {
                        // Process as image
                        val inputStream = connection.inputStream
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        inputStream.close()
                        connection.disconnect()
                        
                        return@withContext processCouponFromBitmap(bitmap)
                    }
                    contentType.startsWith("application/pdf") -> {
                        // Download PDF to temporary file
                        val inputStream = connection.inputStream
                        val tempFile = createTempFile(TEMP_FILE_SUFFIX_PDF)
                        
                        FileOutputStream(tempFile).use { output ->
                            inputStream.copyTo(output)
                        }
                        
                        inputStream.close()
                        connection.disconnect()
                        
                        // Process the PDF
                        val pdfUri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            tempFile
                        )
                        
                        return@withContext processPdfUri(pdfUri)
                    }
                    else -> {
                        // Try to extract coupon code from URL
                        val code = extractCouponCodeFromUrl(urlString)
                        if (code != null) {
                            return@withContext Coupon(
                                id = 0,
                                storeName = extractDomainFromUrl(urlString),
                                description = "Coupon from URL",
                                cashbackAmount = 0.0,
                                expiryDate = Date(),
                                redeemCode = code,
                                createdDate = Date(),
                                status = "Active"
                            )
                        }
                        
                        throw IOException("Unsupported content type: $contentType")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon from URL", e)
                throw e
            }
        }
    }
    
    /**
     * Process text input (like a coupon code)
     * @param text The text to process
     * @return The extracted coupon information
     */
    suspend fun processCouponFromText(text: String): Coupon {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon from text: $text")
                
                // Check if it's a URL
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    return@withContext processCouponFromUrl(text)
                }
                
                // Otherwise, treat as a coupon code
                return@withContext Coupon(
                    id = 0,
                    storeName = "Unknown Store",
                    description = "Manual entry",
                    cashbackAmount = 0.0,
                    expiryDate = Date(),
                    redeemCode = text,
                    createdDate = Date(),
                    status = "Active"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon from text", e)
                throw e
            }
        }
    }
    
    /**
     * Process multiple images in batch mode
     * @param imageUris List of image URIs to process
     * @return List of extracted coupon information
     */
    suspend fun processCouponsInBatch(imageUris: List<Uri>): List<Coupon> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<Coupon>()
            
            for (uri in imageUris) {
                try {
                    val coupon = processCouponFromImageUri(uri)
                    results.add(coupon)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing image in batch: $uri", e)
                    // Continue with next image
                }
            }
            
            return@withContext results
        }
    }
    
    /**
     * Start monitoring for new screenshots
     * @param onScreenshotTaken Callback when a screenshot is taken
     */
    fun startScreenshotMonitoring(onScreenshotTaken: (Uri) -> Unit) {
        // Stop any existing observer
        stopScreenshotMonitoring()
        
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                
                uri?.let {
                    // Check if this is a screenshot
                    val projection = arrayOf(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.RELATIVE_PATH
                    )
                    
                    contentResolver.query(
                        it,
                        projection,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val pathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                            if (pathIndex >= 0) {
                                val path = cursor.getString(pathIndex)
                                if (path.contains(SCREENSHOTS_FOLDER, ignoreCase = true)) {
                                    // This is a screenshot
                                    onScreenshotTaken(it)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        contentResolver.registerContentObserver(
            contentUri,
            true,
            screenshotObserver!!
        )
        
        Log.d(TAG, "Started screenshot monitoring")
    }
    
    /**
     * Stop monitoring for screenshots
     */
    fun stopScreenshotMonitoring() {
        screenshotObserver?.let {
            contentResolver.unregisterContentObserver(it)
            screenshotObserver = null
            Log.d(TAG, "Stopped screenshot monitoring")
        }
    }
    
    /**
     * Handle an intent that might contain a coupon
     * @param intent The intent to handle
     * @return The URI of the coupon image/PDF, or null if not found
     */
    fun handleIntent(intent: Intent): Uri? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                when {
                    intent.type?.startsWith("image/") == true -> {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    intent.type == "application/pdf" -> {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    intent.type == "text/plain" -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        if (!text.isNullOrBlank()) {
                            // If it's a URL, try to download it
                            if (text.startsWith("http://") || text.startsWith("https://")) {
                                null // Process URL directly in the ViewModel
                            } else {
                                null // Process text directly in the ViewModel
                            }
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    uris?.firstOrNull()
                } else {
                    null
                }
            }
            else -> null
        }
    }
    
    /**
     * Get the text from an intent
     * @param intent The intent to extract text from
     * @return The text or null if not found
     */
    fun getTextFromIntent(intent: Intent): String? {
        return if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
    }
    
    /**
     * Get multiple image URIs from an intent
     * @param intent The intent to extract URIs from
     * @return List of URIs or empty list if not found
     */
    fun getMultipleImagesFromIntent(intent: Intent): List<Uri> {
        return if (intent.action == Intent.ACTION_SEND_MULTIPLE && 
                  intent.type?.startsWith("image/") == true) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        } else {
            emptyList()
        }
    }
    
    /**
     * Scan a bitmap for barcodes
     * @param bitmap The bitmap to scan
     * @return The first barcode found, or null if none
     */
    private suspend fun scanForBarcodes(bitmap: Bitmap): Barcode? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                
                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        if (barcodes.isNotEmpty()) {
                            continuation.resume(barcodes[0])
                        } else {
                            continuation.resume(null)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Barcode scanning failed", e)
                        continuation.resume(null)
                    }
                
                continuation.invokeOnCancellation {
                    // Clean up resources if needed
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning for barcodes", e)
                continuation.resume(null)
            }
        }
    }
    
    /**
     * Load a bitmap from a URI
     * @param uri The URI to load
     * @return The loaded bitmap
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap {
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open input stream for URI: $uri")
        
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        
        if (bitmap == null) {
            throw IOException("Could not decode bitmap from URI: $uri")
        }
        
        return bitmap
    }
    
    /**
     * Check if a URI points to a PDF file
     * @param uri The URI to check
     * @return True if it's a PDF, false otherwise
     */
    private fun isPdfFile(uri: Uri): Boolean {
        val mimeType = getMimeType(uri)
        return mimeType == "application/pdf"
    }
    
    /**
     * Get the MIME type of a URI
     * @param uri The URI to check
     * @return The MIME type or null if unknown
     */
    private fun getMimeType(uri: Uri): String? {
        return if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            contentResolver.getType(uri)
        } else {
            val fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension.lowercase(Locale.ROOT))
        }
    }
    
    /**
     * Create a temporary file
     * @param suffix The file suffix
     * @return The created file
     */
    private fun createTempFile(suffix: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = context.cacheDir
        return File.createTempFile(
            TEMP_FILE_PREFIX + timeStamp,
            suffix,
            storageDir
        )
    }
    
    /**
     * Extract a coupon code from a URL
     * @param url The URL to extract from
     * @return The coupon code or null if not found
     */
    private fun extractCouponCodeFromUrl(url: String): String? {
        // Common URL patterns for coupon codes
        val patterns = listOf(
            Regex("[?&]code=([^&]+)"),
            Regex("[?&]coupon=([^&]+)"),
            Regex("[?&]voucher=([^&]+)"),
            Regex("[?&]promo=([^&]+)"),
            Regex("/coupon/([^/]+)"),
            Regex("/voucher/([^/]+)")
        )
        
        for (pattern in patterns) {
            val matchResult = pattern.find(url)
            if (matchResult != null && matchResult.groupValues.size > 1) {
                return matchResult.groupValues[1]
            }
        }
        
        return null
    }
    
    /**
     * Extract domain name from a URL
     * @param url The URL to extract from
     * @return The domain name
     */
    private fun extractDomainFromUrl(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val domain = uri.host ?: return "Unknown Store"
            
            // Remove www. prefix if present
            if (domain.startsWith("www.")) {
                domain.substring(4)
            } else {
                domain
            }
        } catch (e: Exception) {
            "Unknown Store"
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopScreenshotMonitoring()
        barcodeScanner.close()
        imageProcessor.cleanup()
    }
}

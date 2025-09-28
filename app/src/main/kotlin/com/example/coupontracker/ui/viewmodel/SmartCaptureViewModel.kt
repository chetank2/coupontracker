package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope

import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.ImageOptimizer
import com.example.coupontracker.util.ImageProcessor
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Data class representing the UI state for smart capture
 */
data class SmartCaptureUiState(
    val isProcessing: Boolean = false,
    val detectedMode: CaptureMode? = null,
    val capturedImageUri: Uri? = null,
    val detectedBarcode: Barcode? = null,
    val detectedMultipleCoupons: Boolean = false,
    val couponInfo: CouponInfo? = null,
    val errorMessage: String? = null
)

/**
 * Enum representing different capture modes
 */
enum class CaptureMode {
    SINGLE_COUPON,
    MULTIPLE_COUPONS,
    QR_CODE,
    MANUAL
}

/**
 * ViewModel for the smart capture functionality
 */
@HiltViewModel
class SmartCaptureViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SmartCaptureUiState())
    val uiState: StateFlow<SmartCaptureUiState> = _uiState.asStateFlow()

    private val imageProcessor = ImageProcessor(context)
    private val imageOptimizer = ImageOptimizer(context)

    // Barcode scanner options
    private val barcodeOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_PDF417
        )
        .build()

    // Barcode scanner
    private val barcodeScanner: BarcodeScanner = BarcodeScanning.getClient(barcodeOptions)

    companion object {
        private const val TAG = "SmartCaptureViewModel"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = SmartCaptureUiState()
    }

    /**
     * Process an image for smart detection
     */
    fun processImage(imageProxy: ImageProxy, executor: Executor) {
        val mediaImage = imageProxy.image ?: return

        _uiState.value = _uiState.value.copy(isProcessing = true)

        // Track capture started
        viewModelScope.launch {
            try {
                // Analytics tracking removed for simplicity
                Log.d(TAG, "Capture started")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging", e)
            }
        }

        val startTime = System.currentTimeMillis()

        try {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            // First, check for QR codes
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    if (barcodes.isNotEmpty()) {
                        // QR code detected
                        _uiState.value = _uiState.value.copy(
                            detectedMode = CaptureMode.QR_CODE,
                            detectedBarcode = barcodes[0]
                        )
                        Log.d(TAG, "QR code detected: ${barcodes[0].rawValue}")

                        // Track QR code detection
                        viewModelScope.launch {
                            try {
                                // Analytics tracking removed for simplicity
                                Log.d(TAG, "QR code detected with value type: ${barcodes[0].valueType}")

                                // Track processing time
                                val processingTime = System.currentTimeMillis() - startTime
                                Log.d(TAG, "QR code detection took $processingTime ms")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error logging QR code detection", e)
                            }
                        }
                    } else {
                        // No QR code, assume it's a single coupon for simplicity
                        _uiState.value = _uiState.value.copy(
                            detectedMode = CaptureMode.SINGLE_COUPON,
                            isProcessing = false
                        )

                        // Track single coupon detection
                        viewModelScope.launch {
                            try {
                                // Analytics tracking removed for simplicity
                                Log.d(TAG, "Single coupon detected")

                                // Track processing time
                                val processingTime = System.currentTimeMillis() - startTime
                                Log.d(TAG, "Single coupon detection took $processingTime ms")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error logging single coupon", e)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Barcode scanning failed", e)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        errorMessage = "Failed to process image: ${e.message}"
                    )

                    // Track failure
                    viewModelScope.launch {
                        try {
                            // Analytics tracking removed for simplicity
                            Log.e(TAG, "Capture failed: ${e.message}")
                        } catch (ex: Exception) {
                            Log.e(TAG, "Error logging failure", ex)
                        }
                    }
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image", e)
            _uiState.value = _uiState.value.copy(
                isProcessing = false,
                errorMessage = "Error processing image: ${e.message}"
            )
            imageProxy.close()

            // Track failure
            viewModelScope.launch {
                try {
                    // Analytics tracking removed for simplicity
                    Log.e(TAG, "Capture failed: ${e.message}")
                } catch (ex: Exception) {
                    Log.e(TAG, "Error logging failure", ex)
                }
            }
        }
    }

    /**
     * Capture an image and save it to a file
     */
    fun captureImage(
        imageCapture: ImageCapture,
        executor: Executor,
        onImageSaved: (Uri) -> Unit,
        onError: (ImageCaptureException) -> Unit
    ) {
        // Create a timestamped file
        val photoFile = File(
            context.cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg"
        )

        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        // Capture the image
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    _uiState.value = _uiState.value.copy(capturedImageUri = savedUri)
                    onImageSaved(savedUri)

                    // Process the captured image
                    processImageUri(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        errorMessage = "Failed to capture image: ${exception.message}"
                    )
                    onError(exception)
                }
            }
        )
    }

    /**
     * Process an image URI
     */
    fun processImageUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isProcessing = true)

                // Optimize the image before processing
                Log.d(TAG, "Optimizing image: $uri")
                val optimizedUri = withContext(Dispatchers.IO) {
                    imageOptimizer.optimizeImage(uri)
                }
                Log.d(TAG, "Image optimized: $optimizedUri")

                // Process the optimized image to extract coupon information
                val processingStartTime = System.currentTimeMillis()
                val couponInfo = withContext(Dispatchers.IO) {
                    imageProcessor.processImage(optimizedUri) // URI-based processing includes metadata extraction
                }
                val processingTime = System.currentTimeMillis() - processingStartTime

                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    couponInfo = couponInfo
                )

                // Track successful processing
                try {
                    // Analytics tracking removed for simplicity
                    Log.d(TAG, "Capture completed: " +
                            "has_store_name=${couponInfo.storeName.isNotBlank()}, " +
                            "has_description=${couponInfo.description.isNotBlank()}, " +
                            "has_code=${couponInfo.redeemCode != null}")

                    // Track processing time
                    Log.d(TAG, "Image processing took $processingTime ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Error logging processing", e)
                }

                Log.d(TAG, "Processed image URI: $optimizedUri, extracted info: $couponInfo")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process image URI", e)
                _uiState.value = _uiState.value.copy(
                    isProcessing = false,
                    errorMessage = "Failed to process image: ${e.message}"
                )
            }
        }
    }

    /**
     * Set the detected mode manually
     */
    fun setDetectedMode(mode: CaptureMode) {
        _uiState.value = _uiState.value.copy(detectedMode = mode)

        // Track mode selection
        viewModelScope.launch {
            try {
                // Analytics tracking removed for simplicity
                Log.d(TAG, "Mode selected: ${mode.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error logging mode selection", e)
            }
        }
    }

    /**
     * Clear the error message
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        barcodeScanner.close()
        viewModelScope.launch(Dispatchers.IO) {
            imageOptimizer.clearCache()
        }
    }
}

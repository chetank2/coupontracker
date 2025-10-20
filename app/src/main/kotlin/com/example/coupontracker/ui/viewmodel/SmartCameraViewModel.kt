package com.example.coupontracker.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.camera.CaptureReadiness
import com.example.coupontracker.camera.CropResult
import com.example.coupontracker.camera.DetectedTextBlock
import com.example.coupontracker.camera.LiveTextDetectionAnalyzer
import com.example.coupontracker.camera.SmartCropProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * ViewModel for smart camera capture with live text detection
 * 
 * Features:
 * - Real-time text detection overlay
 * - Smart crop suggestions
 * - Capture readiness feedback
 * - Automatic image optimization
 */
@HiltViewModel
class SmartCameraViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smartCropProcessor: SmartCropProcessor
) : ViewModel() {
    
    companion object {
        private const val TAG = "SmartCameraVM"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }
    
    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()
    
    private val _detectedText = MutableStateFlow<List<DetectedTextBlock>>(emptyList())
    val detectedText: StateFlow<List<DetectedTextBlock>> = _detectedText.asStateFlow()
    
    private val _captureReadiness = MutableStateFlow(CaptureReadiness.NOT_READY)
    val captureReadiness: StateFlow<CaptureReadiness> = _captureReadiness.asStateFlow()
    
    var liveTextAnalyzer: LiveTextDetectionAnalyzer? = null
        private set
    
    /**
     * Initialize live text detection analyzer
     */
    fun initializeAnalyzer(): LiveTextDetectionAnalyzer {
        val analyzer = LiveTextDetectionAnalyzer()
        liveTextAnalyzer = analyzer
        
        // Observe analyzer state
        viewModelScope.launch {
            analyzer.detectedText.collect { blocks ->
                _detectedText.value = blocks
            }
        }
        
        viewModelScope.launch {
            analyzer.captureReadiness.collect { readiness ->
                _captureReadiness.value = readiness
            }
        }
        
        return analyzer
    }
    
    /**
     * Capture photo with optional smart crop
     */
    fun capturePhoto(
        imageCapture: ImageCapture,
        shouldSmartCrop: Boolean,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        _uiState.value = CameraUiState.Capturing
        
        // Create output file
        val photoFile = File(
            context.cacheDir,
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(Date()) + ".jpg"
        )
        
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = Uri.fromFile(photoFile)
                    
                    if (shouldSmartCrop && _detectedText.value.isNotEmpty()) {
                        processWithSmartCrop(uri, onSuccess, onError)
                    } else {
                        _uiState.value = CameraUiState.Success(uri)
                        onSuccess(uri)
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed", exception)
                    _uiState.value = CameraUiState.Error(exception.message ?: "Capture failed")
                    onError(exception.message ?: "Capture failed")
                }
            }
        )
    }
    
    /**
     * Apply smart crop to captured image
     */
    private fun processWithSmartCrop(
        uri: Uri,
        onSuccess: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                _uiState.value = CameraUiState.Processing
                
                // Load bitmap
                val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                } ?: throw IllegalStateException("Failed to load image")
                
                // Apply smart crop
                val cropResult = smartCropProcessor.cropToTextRegion(
                    bitmap,
                    _detectedText.value
                )
                
                when (cropResult) {
                    is CropResult.Success -> {
                        // Save cropped image
                        val croppedFile = File(
                            context.cacheDir,
                            "cropped_${System.currentTimeMillis()}.jpg"
                        )
                        
                        croppedFile.outputStream().use { out ->
                            cropResult.bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        
                        val croppedUri = Uri.fromFile(croppedFile)
                        _uiState.value = CameraUiState.Success(croppedUri)
                        onSuccess(croppedUri)
                        
                        Log.d(TAG, "Smart crop applied: ${cropResult.originalSize} -> ${cropResult.croppedSize}")
                    }
                    else -> {
                        // Use original if crop failed
                        _uiState.value = CameraUiState.Success(uri)
                        onSuccess(uri)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Smart crop failed", e)
                _uiState.value = CameraUiState.Error(e.message ?: "Processing failed")
                onError(e.message ?: "Processing failed")
            }
        }
    }
    
    /**
     * Reset UI state
     */
    fun resetState() {
        _uiState.value = CameraUiState.Idle
    }
    
    /**
     * Clean up resources
     */
    override fun onCleared() {
        super.onCleared()
        liveTextAnalyzer?.close()
    }
}

/**
 * UI states for camera capture flow
 */
sealed class CameraUiState {
    object Idle : CameraUiState()
    object Capturing : CameraUiState()
    object Processing : CameraUiState()
    data class Success(val uri: Uri) : CameraUiState()
    data class Error(val message: String) : CameraUiState()
}


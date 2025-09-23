package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
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
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * ViewModel for the unified camera screen
 */
@HiltViewModel
class UnifiedCameraViewModel @Inject constructor(
    application: Application,
    @ApplicationContext private val context: Context
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(UnifiedCameraUiState())
    val uiState: StateFlow<UnifiedCameraUiState> = _uiState.asStateFlow()
    
    companion object {
        private const val TAG = "UnifiedCameraViewModel"
    }
    
    /**
     * Toggle between single and batch mode
     */
    fun toggleBatchMode() {
        _uiState.value = _uiState.value.copy(
            isBatchMode = !_uiState.value.isBatchMode
        )
    }
    
    /**
     * Capture an image
     * @param imageCapture ImageCapture use case
     * @param executor Executor for the capture callback
     */
    fun captureImage(imageCapture: ImageCapture, executor: Executor) {
        // Create output file
        val photoFile = createImageFile()
        
        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        
        // Capture the image
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    Log.d(TAG, "Image captured: $savedUri")
                    
                    if (_uiState.value.isBatchMode) {
                        // Add to batch images
                        addToBatchImages(savedUri)
                    } else {
                        // Set as single captured image
                        setCapturedImage(savedUri)
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                }
            }
        )
    }
    
    /**
     * Set the captured image URI
     * @param uri URI of the captured image
     */
    fun setCapturedImage(uri: Uri) {
        _uiState.value = _uiState.value.copy(
            capturedImageUri = uri
        )
    }
    
    /**
     * Add an image to the batch
     * @param uri URI of the image to add
     */
    fun addToBatchImages(uri: Uri) {
        val currentImages = _uiState.value.capturedBatchImages.toMutableList()
        currentImages.add(uri)
        _uiState.value = _uiState.value.copy(
            capturedBatchImages = currentImages
        )
    }
    
    /**
     * Process all batch images
     */
    fun processBatchImages() {
        viewModelScope.launch {
            val images = _uiState.value.capturedBatchImages
            if (images.isNotEmpty()) {
                // Store the URIs for batch processing
                val sharedPrefs = context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
                val gson = Gson()
                val uriStrings = images.map { it.toString() }
                val uriJson = gson.toJson(uriStrings)
                sharedPrefs.edit().putString("shared_image_uris", uriJson).apply()
            }
        }
    }
    
    /**
     * Create a temporary image file
     * @return The created file
     */
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            imageFileName,
            ".jpg",
            storageDir
        )
    }
    
    /**
     * Reset the UI state
     */
    fun resetState() {
        _uiState.value = UnifiedCameraUiState()
    }
}

/**
 * UI state for the unified camera screen
 */
data class UnifiedCameraUiState(
    val isBatchMode: Boolean = false,
    val capturedImageUri: Uri? = null,
    val capturedBatchImages: List<Uri> = emptyList()
)

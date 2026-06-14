package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ModelImportUiState(
    val isModelInstalled: Boolean = false,
    val modelInfo: ModelManifest? = null,
    val modelSizeMB: Int = 0,
    val isImporting: Boolean = false,
    val importProgress: Int = 0,
    val importMessage: String = "",
    val importError: String? = null,
    val selfTestRunning: Boolean = false,
    val selfTestResult: SelfTestResult? = null
)

@HiltViewModel
class ModelImportViewModel @Inject constructor(
    application: Application,
    private val modelImportManager: ModelImportManager,
    private val modelSelfTest: ModelSelfTest,
    private val modelDownloadManager: com.example.coupontracker.llm.ModelDownloadManager,
    private val secureDownloader: com.example.coupontracker.network.SecureModelDownloader,
    private val securePreferencesManager: com.example.coupontracker.util.SecurePreferencesManager
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(ModelImportUiState())
    val uiState: StateFlow<ModelImportUiState> = _uiState.asStateFlow()
    
    init {
        checkInstalledModel()
    }
    
    fun checkInstalledModel() {
        val installed = modelImportManager.isModelInstalled()
        val info = if (installed) modelImportManager.getInstalledModelInfo() else null
        val sizeMB = if (installed) modelImportManager.getInstalledModelSizeMB() else 0
        if (installed && sizeMB > 0) {
            securePreferencesManager.setLlmModelSizeMB(sizeMB.toFloat())
        }
        
        _uiState.value = _uiState.value.copy(
            isModelInstalled = installed,
            modelInfo = info,
            modelSizeMB = sizeMB
        )
    }
    
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isImporting = true,
                importProgress = 0,
                importMessage = "Starting import...",
                importError = null
            )
            
            val result = modelImportManager.importModel(uri) { progress ->
                when (progress) {
                    is ImportResult.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            importProgress = progress.percent,
                            importMessage = progress.message
                        )
                    }
                    else -> {}
                }
            }
            
            when (result) {
                is ImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        isModelInstalled = true,
                        modelInfo = result.manifest,
                        modelSizeMB = result.sizeMB,
                        importProgress = 100,
                        importMessage = "Import complete"
                    )

                    result.manifest?.let { manifest ->
                        securePreferencesManager.setLlmModelVersion(manifest.version)
                        securePreferencesManager.setLlmModelSizeMB(result.sizeMB.toFloat())
                    }
                    
                    // Auto-run self-test after successful import
                    runSelfTest()
                }
                is ImportResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importError = result.reason
                    )
                }
                else -> {}
            }
        }
    }
    
    fun runSelfTest() {
        viewModelScope.launch(Dispatchers.IO) {
            // Update UI state on main thread
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selfTestRunning = true,
                    selfTestResult = null
                )
            }
            
            // Run heavy model loading on IO thread (prevents ANR)
            val result = modelSelfTest.runSelfTest()
            
            // Update UI state on main thread
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(
                    selfTestRunning = false,
                    selfTestResult = result
                )
            }
        }
    }
    
    fun deleteModel() {
        viewModelScope.launch {
            modelImportManager.deleteModel()
            securePreferencesManager.setLlmModelVersion("")
            securePreferencesManager.setLlmModelSizeMB(0f)
            securePreferencesManager.setLlmModelDownloaded(false)
            _uiState.value = _uiState.value.copy(
                isModelInstalled = false,
                modelInfo = null,
                modelSizeMB = 0,
                selfTestResult = null
            )
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(importError = null)
    }
    
    /**
     * Download REAL MiniCPM model with VISION support (mmproj)
     * Downloads 5.8GB total: 4.7GB main model + 1.1GB vision projector
     * 
     * PRIVACY GUARANTEE:
     * - Only downloads model files (GET requests to HuggingFace)
     * - HTTPS only (enforced by network security config)
     * - No user data ever uploaded
     * - All inference is offline after download
     */
    fun downloadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = true,
                        importProgress = 0,
                        importMessage = "Preparing download (Qwen2.5-1.5B, 940 MB)...",
                        importError = null
                    )
                }
                
                // Download Qwen2.5-1.5B model (improved JSON output, text-only)
                val result = modelDownloadManager.downloadQwen25Model { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            importProgress = progress.progressPercent,
                            importMessage = progress.statusMessage
                        )
                    }
                }
                
                when (result) {
                    is com.example.coupontracker.llm.DownloadResult.Success -> {
                        withContext(Dispatchers.Main) {
                            securePreferencesManager.setLlmModelVersion(result.version)
                            securePreferencesManager.setLlmModelSizeMB(result.modelSizeMB.toFloat())
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                isModelInstalled = true,
                                modelSizeMB = result.modelSizeMB.toInt(),
                                importProgress = 100,
                                importMessage = "Download complete - Vision enabled!"
                            )
                            
                            // Check for updated model info
                            checkInstalledModel()
                            
                            // Auto-run self-test
                            runSelfTest()
                        }
                    }
                    is com.example.coupontracker.llm.DownloadResult.Error -> {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                importError = result.message
                            )
                        }
                    }
                }
                
            } catch (e: Exception) {
                android.util.Log.e("ModelImportViewModel", "Download failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importError = "Download failed: ${e.message}"
                    )
                }
            }
        }
    }
}

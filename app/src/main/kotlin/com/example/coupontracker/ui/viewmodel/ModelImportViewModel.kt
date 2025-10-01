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
    private val modelDownloadManager: com.example.coupontracker.llm.ModelDownloadManager
) : AndroidViewModel(application) {
    
    private val _uiState = MutableStateFlow(ModelImportUiState())
    val uiState: StateFlow<ModelImportUiState> = _uiState.asStateFlow()
    
    init {
        checkInstalledModel()
    }
    
    fun checkInstalledModel() {
        val installed = modelImportManager.isModelInstalled()
        val info = if (installed) modelImportManager.getInstalledModelInfo() else null
        
        _uiState.value = _uiState.value.copy(
            isModelInstalled = installed,
            modelInfo = info,
            modelSizeMB = info?.files?.sumOf { it.size }?.let { (it / 1_000_000).toInt() } ?: 0
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
     * Download REAL MiniCPM model from Hugging Face
     * Downloads 2-3GB GGUF file with resume support
     */
    fun downloadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Initialize downloader
                val downloader = com.example.coupontracker.model.ResumableModelDownloader(getApplication())
                val modelConfig = com.example.coupontracker.model.RealModelConfig
                
                // Check storage space first
                val requiredSpace = modelConfig.REQUIRED_FREE_SPACE
                if (!downloader.checkStorageSpace(requiredSpace)) {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            isImporting = false,
                            importError = "Insufficient storage. Need ${requiredSpace / 1_000_000_000}GB free space."
                        )
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = true,
                        importProgress = 0,
                        importMessage = "Preparing download...",
                        importError = null
                    )
                }
                
                // Download main model file
                val modelDir = com.example.coupontracker.model.ModelPaths.modelDir(getApplication())
                modelDir.mkdirs()
                
                val mainModel = modelConfig.MAIN_MODEL
                val destFile = java.io.File(modelDir, mainModel.filename)
                
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        importMessage = "Downloading ${mainModel.filename} (~4.7GB)..."
                    )
                }
                
                // Download with resume support
                val result = downloader.downloadFile(
                    url = modelConfig.getDownloadUrl(mainModel),
                    destFile = destFile,
                    expectedSize = mainModel.expectedSize,
                    expectedSha256 = mainModel.sha256.takeIf { it != "COMPUTE_ON_FIRST_DOWNLOAD" },
                    onProgress = { progress ->
                        viewModelScope.launch(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                importProgress = progress.progressPercent,
                                importMessage = progress.statusMessage
                            )
                        }
                    }
                )
                
                when (result) {
                    is com.example.coupontracker.model.ResumableModelDownloader.DownloadResult.Success -> {
                        // Create .verified marker
                        val verifiedFile = java.io.File(modelDir, ".verified")
                        verifiedFile.writeText(result.sha256)
                        
                        // Update preferences
                        val securePrefs = com.example.coupontracker.util.SecurePreferencesManager(getApplication())
                        securePrefs.setLlmModelDownloaded(true)
                        securePrefs.setLlmModelVersion(modelConfig.MODEL_VERSION)
                        securePrefs.setLlmModelSizeMB((result.file.length() / 1_000_000f))
                        securePrefs.setLlmModelChecksum(result.sha256)
                        
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                isModelInstalled = true,
                                modelSizeMB = (result.file.length() / 1_000_000).toInt(),
                                importProgress = 100,
                                importMessage = "Download complete!"
                            )
                            
                            // Check for updated model info
                            checkInstalledModel()
                            
                            // Auto-run self-test
                            runSelfTest()
                        }
                    }
                    is com.example.coupontracker.model.ResumableModelDownloader.DownloadResult.Failed -> {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                importError = result.reason
                            )
                        }
                    }
                    else -> {
                        // Progress updates handled in callback
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


package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.R
import com.example.coupontracker.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ModelSetupTarget {
    QWEN,
    GEMMA_VISION
}

data class ModelImportUiState(
    val isModelInstalled: Boolean = false,
    val isGemmaVisionInstalled: Boolean = false,
    val modelInfo: ModelManifest? = null,
    val gemmaVisionInfo: ModelManifest? = null,
    val modelSizeMB: Int = 0,
    val gemmaVisionSizeMB: Int = 0,
    val isImporting: Boolean = false,
    val activeSetupTarget: ModelSetupTarget? = null,
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
        val gemmaInstalled = modelImportManager.isGemmaVisionInstalled()
        val info = if (installed) modelImportManager.getInstalledModelInfo() else null
        val gemmaInfo = if (gemmaInstalled) modelImportManager.getGemmaVisionManifest() else null
        val sizeMB = if (installed) modelImportManager.getInstalledModelSizeMB() else 0
        val gemmaSizeMB = if (gemmaInstalled) modelImportManager.getGemmaVisionSizeMB() else 0
        if (installed && sizeMB > 0) {
            securePreferencesManager.setLlmModelSizeMB(sizeMB.toFloat())
        }
        
        _uiState.value = _uiState.value.copy(
            isModelInstalled = installed,
            isGemmaVisionInstalled = gemmaInstalled,
            modelInfo = info,
            gemmaVisionInfo = gemmaInfo,
            modelSizeMB = sizeMB,
            gemmaVisionSizeMB = gemmaSizeMB
        )
    }
    
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isImporting = true,
                activeSetupTarget = ModelSetupTarget.QWEN,
                importProgress = 0,
                importMessage = "Starting import...",
                importError = null
            )
            
            val result = modelImportManager.importModel(uri) { progress ->
                _uiState.value = _uiState.value.copy(
                    importProgress = progress.percent,
                    importMessage = progress.message
                )
            }
            
            when (result) {
                is ImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        activeSetupTarget = ModelSetupTarget.QWEN,
                        isModelInstalled = true,
                        modelInfo = result.manifest,
                        modelSizeMB = result.sizeMB,
                        importProgress = 100,
                        importMessage = "Import complete"
                    )

                    securePreferencesManager.setLlmModelVersion(result.manifest.version)
                    securePreferencesManager.setLlmModelSizeMB(result.sizeMB.toFloat())
                    
                    // Auto-run self-test after successful import
                    runSelfTest()
                }
                is ImportResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        activeSetupTarget = ModelSetupTarget.QWEN,
                        importError = result.reason
                    )
                }
                else -> {}
            }
        }
    }

    fun importGemmaVisionModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isImporting = true,
                activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                importProgress = 0,
                importMessage = "Starting Gemma Vision import...",
                importError = null
            )

            val result = modelImportManager.importGemmaVisionModel(uri) { progress ->
                _uiState.value = _uiState.value.copy(
                    importProgress = progress.percent,
                    importMessage = progress.message
                )
            }

            when (result) {
                is ImportResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                        isGemmaVisionInstalled = true,
                        gemmaVisionInfo = result.manifest,
                        gemmaVisionSizeMB = result.sizeMB,
                        importProgress = 100,
                        importMessage = "Gemma Vision import complete"
                    )
                }
                is ImportResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
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
                isGemmaVisionInstalled = modelImportManager.isGemmaVisionInstalled(),
                modelInfo = null,
                gemmaVisionInfo = modelImportManager.getGemmaVisionManifest(),
                modelSizeMB = 0,
                gemmaVisionSizeMB = modelImportManager.getGemmaVisionSizeMB(),
                selfTestResult = null
            )
        }
    }

    fun deleteGemmaVisionModel() {
        viewModelScope.launch {
            modelImportManager.deleteGemmaVisionModel()
            _uiState.value = _uiState.value.copy(
                isGemmaVisionInstalled = false,
                gemmaVisionInfo = null,
                gemmaVisionSizeMB = 0,
                activeSetupTarget = null,
                importError = null
            )
        }
    }

    fun downloadGemmaVisionModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = true,
                        activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                        importProgress = 0,
                        importMessage = "Starting Gemma Vision download...",
                        importError = null
                    )
                }

                val result = modelDownloadManager.downloadGemmaVisionModel { progress ->
                    viewModelScope.launch(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            importProgress = progress.progressPercent.coerceAtLeast(0),
                            importMessage = progress.statusMessage
                        )
                    }
                }

                when (result) {
                    is com.example.coupontracker.llm.DownloadResult.Success -> {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                                isGemmaVisionInstalled = true,
                                gemmaVisionInfo = modelImportManager.getGemmaVisionManifest(),
                                gemmaVisionSizeMB = result.modelSizeMB.toInt(),
                                importProgress = 100,
                                importMessage = "Gemma Vision download complete"
                            )
                            checkInstalledModel()
                        }
                    }
                    is com.example.coupontracker.llm.DownloadResult.Error -> {
                        withContext(Dispatchers.Main) {
                            _uiState.value = _uiState.value.copy(
                                isImporting = false,
                                activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                                importError = result.message
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ModelImportViewModel", "Gemma Vision download failed", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        activeSetupTarget = ModelSetupTarget.GEMMA_VISION,
                        importError = "Gemma Vision download failed: ${e.message}"
                    )
                }
            }
        }
    }
    
    fun clearError() {
        _uiState.value = _uiState.value.copy(importError = null)
    }
    
    /**
     * Download the Qwen2.5 text model used by the explicit Clean action.
     * Capture remains OCR-first; this model is only used after the user asks to clean a saved coupon.
     */
    fun downloadModel() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isImporting = true,
                        activeSetupTarget = ModelSetupTarget.QWEN,
                        importProgress = 0,
                        importMessage = getApplication<Application>().getString(
                            R.string.model_setup_preparing,
                            ModelCatalog.COUPON_READER_VARIANT,
                            ModelCatalog.COUPON_READER_SIZE
                        ),
                        importError = null
                    )
                }
                
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
                                activeSetupTarget = ModelSetupTarget.QWEN,
                                isModelInstalled = true,
                                modelSizeMB = result.modelSizeMB.toInt(),
                                importProgress = 100,
                                importMessage = "Download complete"
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
                                activeSetupTarget = ModelSetupTarget.QWEN,
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
                        activeSetupTarget = ModelSetupTarget.QWEN,
                        importError = "Download failed: ${e.message}"
                    )
                }
            }
        }
    }
}

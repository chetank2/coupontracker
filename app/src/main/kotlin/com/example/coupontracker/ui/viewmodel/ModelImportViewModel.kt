package com.example.coupontracker.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val modelSelfTest: ModelSelfTest
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selfTestRunning = true,
                selfTestResult = null
            )
            
            val result = modelSelfTest.runSelfTest()
            
            _uiState.value = _uiState.value.copy(
                selfTestRunning = false,
                selfTestResult = result
            )
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
}


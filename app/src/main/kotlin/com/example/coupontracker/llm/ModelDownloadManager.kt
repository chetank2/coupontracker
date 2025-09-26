package com.example.coupontracker.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Manages downloading and verification of MiniCPM-Llama3-V2.5 model files
 */
class ModelDownloadManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // Model download configuration
        private const val MODEL_BASE_URL = "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5-int4/resolve/main"
        private const val MODEL_ZIP_NAME = "minicpm_llama3_v25_android.zip"
        private const val MODEL_VERSION = "v2.5-q4-android"
        
        // Expected model files
        private val REQUIRED_FILES = listOf(
            "minicpm_llm_q4f16_1.so",
            "mlc-chat-config.json", 
            "tokenizer.json",
            "params_shard_0.bin",
            "params_shard_1.bin"
        )
        
        // Download configuration
        private const val DOWNLOAD_TIMEOUT_MS = 300000 // 5 minutes
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL = 1024 * 100 // Update every 100KB
    }
    
    private val securePrefs = SecurePreferencesManager(context)
    private val modelDir = File(context.filesDir, "models")
    
    init {
        // Ensure model directory exists
        modelDir.mkdirs()
    }
    
    /**
     * Check if WiFi is available (for WiFi-only downloads)
     */
    private fun isWifiAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    
    /**
     * Check if download should proceed based on network conditions
     */
    private fun shouldProceedWithDownload(): Boolean {
        val wifiOnly = securePrefs.getLlmDownloadWifiOnly()
        
        return if (wifiOnly) {
            isWifiAvailable()
        } else {
            // Any network connection is fine
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.activeNetwork != null
        }
    }
    
    /**
     * Download model with progress tracking
     */
    suspend fun downloadModel(progressCallback: (DownloadProgress) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting model download...")
            
            // Check network conditions
            if (!shouldProceedWithDownload()) {
                val message = if (securePrefs.getLlmDownloadWifiOnly()) {
                    "WiFi connection required for download"
                } else {
                    "No network connection available"
                }
                return@withContext DownloadResult.Error(message)
            }
            
            // Create temporary download file
            val tempFile = File(context.cacheDir, "temp_$MODEL_ZIP_NAME")
            
            // Download model zip
            val downloadSuccess = downloadFile(
                url = "$MODEL_BASE_URL/$MODEL_ZIP_NAME",
                outputFile = tempFile,
                progressCallback = progressCallback
            )
            
            if (!downloadSuccess) {
                tempFile.delete()
                return@withContext DownloadResult.Error("Download failed")
            }
            
            // Verify downloaded file
            progressCallback(DownloadProgress(100, "Verifying download..."))
            val checksumValid = verifyFileChecksum(tempFile)
            
            if (!checksumValid) {
                tempFile.delete()
                return@withContext DownloadResult.Error("Download verification failed")
            }
            
            // Extract model files
            progressCallback(DownloadProgress(100, "Extracting model..."))
            val extractSuccess = extractModelFiles(tempFile)
            
            // Clean up temp file
            tempFile.delete()
            
            if (!extractSuccess) {
                return@withContext DownloadResult.Error("Model extraction failed")
            }
            
            // Verify all required files are present
            val allFilesPresent = verifyModelFiles()
            
            if (!allFilesPresent) {
                return@withContext DownloadResult.Error("Model files incomplete")
            }
            
            // Update preferences
            val modelSizeMB = getModelSizeMB()
            securePrefs.setLlmModelDownloaded(true)
            securePrefs.setLlmModelVersion(MODEL_VERSION)
            securePrefs.setLlmModelSizeMB(modelSizeMB)
            securePrefs.setLlmModelChecksum(calculateModelChecksum())
            
            Log.d(TAG, "Model download completed successfully")
            progressCallback(DownloadProgress(100, "Download complete"))
            
            DownloadResult.Success(modelSizeMB)
            
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            DownloadResult.Error("Download failed: ${e.message}")
        }
    }
    
    /**
     * Download a file with progress tracking
     */
    private suspend fun downloadFile(
        url: String,
        outputFile: File,
        progressCallback: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Download failed with response code: ${connection.responseCode}")
                return@withContext false
            }
            
            val fileSize = connection.contentLength
            var totalBytesRead = 0L
            var lastProgressUpdate = 0L
            
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Update progress periodically
                        if (totalBytesRead - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL) {
                            val progressPercent = if (fileSize > 0) {
                                ((totalBytesRead * 100) / fileSize).toInt()
                            } else {
                                -1 // Unknown progress
                            }
                            
                            progressCallback(DownloadProgress(
                                progressPercent,
                                "Downloading... ${formatBytes(totalBytesRead)}" +
                                if (fileSize > 0) " / ${formatBytes(fileSize.toLong())}" else ""
                            ))
                            
                            lastProgressUpdate = totalBytesRead
                        }
                    }
                }
            }
            
            Log.d(TAG, "Download completed: ${formatBytes(totalBytesRead)}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "File download failed", e)
            false
        }
    }
    
    /**
     * Verify file checksum (placeholder - would use actual expected checksum)
     */
    private fun verifyFileChecksum(file: File): Boolean {
        return try {
            val checksum = calculateFileChecksum(file)
            Log.d(TAG, "Downloaded file checksum: $checksum")
            
            // In production, this would compare against known good checksum
            // For now, just verify file exists and has reasonable size
            file.exists() && file.length() > 1024 * 1024 // At least 1MB
            
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification failed", e)
            false
        }
    }
    
    /**
     * Extract model files from downloaded zip
     */
    private fun extractModelFiles(zipFile: File): Boolean {
        return try {
            ZipInputStream(zipFile.inputStream()).use { zipStream ->
                var entry: ZipEntry?
                
                while (zipStream.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    
                    // Skip directories and unwanted files
                    if (entry!!.isDirectory || !isRequiredFile(entryName)) {
                        continue
                    }
                    
                    val outputFile = File(modelDir, entryName)
                    outputFile.parentFile?.mkdirs()
                    
                    FileOutputStream(outputFile).use { output ->
                        zipStream.copyTo(output)
                    }
                    
                    Log.d(TAG, "Extracted: $entryName (${formatBytes(outputFile.length())})")
                }
            }
            
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Model extraction failed", e)
            false
        }
    }
    
    /**
     * Check if filename is a required model file
     */
    private fun isRequiredFile(filename: String): Boolean {
        return REQUIRED_FILES.any { required ->
            filename.endsWith(required) || filename.contains(required.substringBefore("."))
        }
    }
    
    /**
     * Verify all required model files are present
     */
    private fun verifyModelFiles(): Boolean {
        val presentFiles = modelDir.listFiles()?.map { it.name } ?: emptyList()
        
        val missingFiles = REQUIRED_FILES.filter { required ->
            !presentFiles.any { present -> present.contains(required.substringBefore(".")) }
        }
        
        if (missingFiles.isNotEmpty()) {
            Log.w(TAG, "Missing model files: $missingFiles")
            return false
        }
        
        Log.d(TAG, "All required model files present")
        return true
    }
    
    /**
     * Calculate total model size in MB
     */
    private fun getModelSizeMB(): Float {
        val totalBytes = modelDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        
        return totalBytes / (1024f * 1024f)
    }
    
    /**
     * Calculate checksum for all model files
     */
    private fun calculateModelChecksum(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        
        modelDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                digest.update(calculateFileChecksum(file).toByteArray())
            }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Calculate checksum for a single file
     */
    private fun calculateFileChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Delete downloaded model files
     */
    fun deleteModel(): Boolean {
        return try {
            val deleted = modelDir.deleteRecursively()
            
            if (deleted) {
                // Update preferences
                securePrefs.setLlmModelDownloaded(false)
                securePrefs.setLlmModelVersion("")
                securePrefs.setLlmModelSizeMB(0f)
                securePrefs.setLlmModelChecksum("")
                
                Log.d(TAG, "Model files deleted successfully")
            }
            
            deleted
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete model files", e)
            false
        }
    }
    
    /**
     * Get download status and model information
     */
    fun getModelStatus(): ModelStatus {
        val isDownloaded = securePrefs.getLlmModelDownloaded()
        val modelVersion = securePrefs.getLlmModelVersion()
        val sizeMB = if (isDownloaded) securePrefs.getLlmModelSizeMB() else 0f
        val filesPresent = if (isDownloaded) verifyModelFiles() else false
        
        return ModelStatus(
            isDownloaded = isDownloaded,
            filesPresent = filesPresent,
            version = modelVersion ?: "Unknown",
            sizeMB = sizeMB,
            downloadUrl = "$MODEL_BASE_URL/$MODEL_ZIP_NAME",
            requiredFiles = REQUIRED_FILES
        )
    }
    
    /**
     * Format bytes to human readable string
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
            else -> "$bytes B"
        }
    }
}

/**
 * Download progress data class
 */
data class DownloadProgress(
    val progressPercent: Int, // -1 for unknown progress
    val statusMessage: String
)

/**
 * Download result sealed class
 */
sealed class DownloadResult {
    data class Success(val modelSizeMB: Float) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
}

/**
 * Model status data class
 */
data class ModelStatus(
    val isDownloaded: Boolean,
    val filesPresent: Boolean,
    val version: String,
    val sizeMB: Float,
    val downloadUrl: String,
    val requiredFiles: List<String>
)

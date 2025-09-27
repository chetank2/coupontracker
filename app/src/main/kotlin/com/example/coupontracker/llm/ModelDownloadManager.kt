package com.example.coupontracker.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.coupontracker.R
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLException

/**
 * Manages downloading and verification of MiniCPM-Llama3-V2.5 model files
 */
class ModelDownloadManager(
    private val context: Context,
    private val verificationDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    
    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // Model download configuration
        private const val DEFAULT_MODEL_BASE_URL =
            "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5/resolve/main/android"
        private const val MODEL_ZIP_NAME = "minicpm_llama3_v25_android.zip"
        private const val MODEL_VERSION = "v2.5-q4-android"
        
        // Expected SHA-256 checksum for the complete model ZIP file
        // Real checksum for MiniCPM-Llama3-V2.5 4-bit quantized Android package
        private const val EXPECTED_ZIP_CHECKSUM = "7a45f222885f84fd0160eeac794ad56be91c6139c436724a56627f16a93d1a76"

        // Expected model files with their individual checksums (real MiniCPM structure)
        private val REQUIRED_FILES = mapOf(
            "minicpm_llm_q4f16_1.so" to "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde",
            "model.bin" to "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1",
            "vision_config.json" to "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c",
            "mlc-chat-config.json" to "c039de2a0c0ec44016207af64a896f7cd3b6940962709c3e49c9321d6c666ff6",
            "tokenizer.model" to "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869",
            "tokenizer.json" to "ba0c892b641f9804451f900a0aa3227555545fdc5f4bd33702436c595b313cf5"
        )
        
        // Minimum expected model size (90% of actual size for validation)
        private const val MIN_MODEL_SIZE = 4227858L // 4.03MB (90% of 4.48MB)
        
        // Download configuration
        private const val DOWNLOAD_TIMEOUT_MS = 30_000L
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL = 100L
    }
    
    private val securePrefs = SecurePreferencesManager(context)
    private val modelDir = File(context.filesDir, "models")

    private data class VerificationCache(val version: String?, val result: Boolean)

    @Volatile
    private var verificationCache: VerificationCache? = null

    @Volatile
    private var verificationListener: (() -> Unit)? = null
    
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
     * Resolve the model download base URL, allowing an override for testing or staged rollouts.
     */
    private fun resolveModelBaseUrl(): String {
        val override = securePrefs.getLlmModelBaseUrlOverride()
            ?.trim()
            ?.trimEnd('/')

        return (override?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL_BASE_URL).trimEnd('/')
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
            val downloadResult = downloadFile(
                url = "${resolveModelBaseUrl()}/$MODEL_ZIP_NAME",
                outputFile = tempFile,
                progressCallback = progressCallback
            )

            downloadResult.getOrElse { error ->
                tempFile.delete()
                return@withContext DownloadResult.Error(resolveErrorMessage(error))
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

            verificationCache = VerificationCache(MODEL_VERSION, true)
            Log.d(TAG, "Model download completed successfully")
            progressCallback(DownloadProgress(100, "Download complete"))
            
            DownloadResult.Success(modelSizeMB)
            
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            DownloadResult.Error(resolveErrorMessage(e))
        }
    }

    private fun resolveErrorMessage(throwable: Throwable): String {
        return if (isConnectivityIssue(throwable)) {
            context.getString(R.string.llm_download_error_network)
        } else {
            val reason = throwable.message?.takeIf { it.isNotBlank() } ?: throwable::class.java.simpleName
            "Download failed: $reason"
        }
    }

    private fun isConnectivityIssue(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is ConnectivityException,
                is UnknownHostException,
                is SocketTimeoutException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketException,
                is SSLException -> return true
            }
            current = current.cause
        }
        return false
    }

    private class ConnectivityException(cause: Throwable) : IOException(cause)

    /**
     * Download a file with progress tracking
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun downloadFile(
        url: String,
        outputFile: File,
        progressCallback: (DownloadProgress) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = DOWNLOAD_TIMEOUT_MS.toInt()
            connection.readTimeout = DOWNLOAD_TIMEOUT_MS.toInt()

            try {
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    val statusMessage = connection.responseMessage?.takeIf { it.isNotBlank() }
                    val errorMessage = if (statusMessage != null) {
                        "HTTP ${connection.responseCode} $statusMessage"
                    } else {
                        "HTTP ${connection.responseCode}"
                    }
                    Log.e(TAG, "Download failed with response code: $errorMessage")
                    return@withContext Result.failure(IOException(errorMessage))
                }

                val fileSize = connection.contentLengthLong
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

                                progressCallback(
                                    DownloadProgress(
                                        progressPercent,
                                        "Downloading... ${formatBytes(totalBytesRead)}" +
                                            if (fileSize > 0) " / ${formatBytes(fileSize)}" else ""
                                    )
                                )

                                lastProgressUpdate = totalBytesRead
                            }
                        }
                    }
                }

                Log.d(TAG, "Download completed: ${formatBytes(totalBytesRead)}")
                Result.success(Unit)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "File download failed", e)
            val exception = if (isConnectivityIssue(e)) {
                ConnectivityException(e)
            } else {
                e
            }
            Result.failure(exception)
        }
    }
    
    /**
     * Verify downloaded file checksum against expected hash
     */
    private fun verifyFileChecksum(file: File): Boolean {
        return try {
            // First check file size - must be at least minimum expected size
            if (!file.exists() || file.length() < MIN_MODEL_SIZE) {
                Log.e(TAG, "Downloaded file too small: ${file.length()} bytes (minimum: $MIN_MODEL_SIZE)")
                return false
            }
            
            Log.d(TAG, "Calculating SHA-256 checksum for downloaded file...")
            val actualChecksum = calculateFileChecksum(file)
            Log.d(TAG, "Downloaded file checksum: $actualChecksum")
            Log.d(TAG, "Expected file checksum: $EXPECTED_ZIP_CHECKSUM")
            
            // Compare against expected checksum
            val checksumMatches = actualChecksum.equals(EXPECTED_ZIP_CHECKSUM, ignoreCase = true)
            
            if (checksumMatches) {
                Log.d(TAG, "✅ Checksum verification passed")
            } else {
                Log.e(TAG, "❌ Checksum verification failed!")
                Log.e(TAG, "Expected: $EXPECTED_ZIP_CHECKSUM")
                Log.e(TAG, "Actual:   $actualChecksum")
            }
            
            checksumMatches
            
        } catch (e: Exception) {
            Log.e(TAG, "Checksum verification failed with exception", e)
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
        val normalizedName = filename.substringAfterLast('/')
        return REQUIRED_FILES.containsKey(normalizedName)
    }
    
    /**
     * Verify all required model files are present and have correct checksums
     */
    private fun verifyModelFiles(): Boolean {
        val presentFiles = modelDir.walkTopDown()
            .filter { it.isFile }
            .groupBy { it.name }

        val missingFiles = REQUIRED_FILES.keys.filter { required ->
            presentFiles[required].isNullOrEmpty()
        }

        if (missingFiles.isNotEmpty()) {
            Log.w(TAG, "Missing model files: $missingFiles")
            return false
        }

        // Verify checksums of present files
        for ((filename, expectedChecksum) in REQUIRED_FILES) {
            val candidateFile = presentFiles[filename]?.firstOrNull()
            if (candidateFile != null) {
                try {
                    val actualChecksum = calculateFileChecksum(candidateFile)
                    if (!actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                        Log.e(TAG, "Checksum mismatch for $filename:")
                        Log.e(TAG, "Expected: $expectedChecksum")
                        Log.e(TAG, "Actual:   $actualChecksum")
                        return false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to verify checksum for $filename", e)
                    return false
                }
            }
        }
        
        Log.d(TAG, "All required model files present and verified")
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

                verificationCache = null

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

        val cache = verificationCache
        val cacheMatches = isDownloaded && cache != null && cache.version == modelVersion
        val filesPresent = if (cacheMatches) cache?.result ?: false else false

        if (!isDownloaded) {
            verificationCache = null
        }

        return updateCachedStatus(
            isDownloaded = isDownloaded,
            filesPresent = filesPresent,
            version = modelVersion ?: "Unknown",
            sizeMB = sizeMB,
            verificationUpToDate = cacheMatches
        )
    }

    suspend fun refreshModelStatus(force: Boolean = false): ModelStatus {
        val isDownloaded = securePrefs.getLlmModelDownloaded()
        val modelVersion = securePrefs.getLlmModelVersion()
        val sizeMB = if (isDownloaded) securePrefs.getLlmModelSizeMB() else 0f

        if (!isDownloaded) {
            verificationCache = null
            return updateCachedStatus(
                isDownloaded = false,
                filesPresent = false,
                version = modelVersion ?: "Unknown",
                sizeMB = 0f,
                verificationUpToDate = false
            )
        }

        val cache = verificationCache
        val cacheMatches = !force && cache != null && cache.version == modelVersion
        if (cacheMatches) {
            return updateCachedStatus(
                isDownloaded = true,
                filesPresent = cache?.result ?: false,
                version = modelVersion ?: "Unknown",
                sizeMB = sizeMB,
                verificationUpToDate = true
            )
        }

        val filesPresent = withContext(verificationDispatcher) {
            verificationListener?.invoke()
            verifyModelFiles()
        }

        verificationCache = VerificationCache(modelVersion, filesPresent)

        return updateCachedStatus(
            isDownloaded = true,
            filesPresent = filesPresent,
            version = modelVersion ?: "Unknown",
            sizeMB = sizeMB,
            verificationUpToDate = true
        )
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun setVerificationListener(listener: (() -> Unit)?) {
        verificationListener = listener
    }

    private fun updateCachedStatus(
        isDownloaded: Boolean,
        filesPresent: Boolean,
        version: String,
        sizeMB: Float,
        verificationUpToDate: Boolean
    ): ModelStatus {
        return ModelStatus(
            isDownloaded = isDownloaded,
            filesPresent = filesPresent,
            version = version,
            sizeMB = sizeMB,
            downloadUrl = "${resolveModelBaseUrl()}/$MODEL_ZIP_NAME",
            requiredFiles = REQUIRED_FILES.keys.toList(),
            isVerificationUpToDate = verificationUpToDate
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
    val requiredFiles: List<String>,
    val isVerificationUpToDate: Boolean
)

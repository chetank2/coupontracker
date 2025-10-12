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
    private val securePrefs: SecurePreferencesManager,
    private val verificationDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    
    companion object {
        private const val TAG = "ModelDownloadManager"
        
        // ===== QWEN2.5 MODEL (NEW DEFAULT) =====
        private const val QWEN25_BASE_URL = "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main"
        private const val QWEN25_MODEL_FILE = "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf"
        private const val QWEN25_MODEL_SIZE = 986_048_768L  // 940 MB (actual bartowski repo file size)
        private const val QWEN25_VERSION = "2.5-1.5b-q4-instruct"
        
        // ===== QWEN2 MODEL (LEGACY) =====
        private const val QWEN2_BASE_URL = "https://huggingface.co/Qwen/Qwen2-1.5B-Instruct-GGUF/resolve/main"
        private const val QWEN2_MODEL_FILE = "qwen2-1_5b-instruct-q4_k_m.gguf"
        private const val QWEN2_MODEL_SIZE = 976_506_880L  // 931 MB
        private const val QWEN2_VERSION = "1.5b-q4-instruct"
        
        // ===== MINICPM MODEL (LEGACY) =====
        // Model download configuration (GGUF repository with mmproj support)
        private const val DEFAULT_MODEL_BASE_URL =
            "https://huggingface.co/openbmb/MiniCPM-Llama3-V-2_5-gguf/resolve/main"
        
        // Model files
        private const val MODEL_FILE = "ggml-model-Q4_K_M.gguf"
        private const val MMPROJ_FILE = "mmproj-model-f16.gguf"
        private const val MODEL_VERSION = "v2.5-q4-android"
        
        // Legacy support
        private const val MODEL_ZIP_NAME = "minicpm_llama3_v25_android.zip"
        private const val EXPECTED_ZIP_CHECKSUM = "bfc31f09be000e56c88d0a9f6360d342f401b36abc63f3c144a64e97224fb8f9"

        // Expected model files with their individual checksums
        private val REQUIRED_FILES = mapOf(
        "minicpm_llm_q4f16_1.so" to "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde",
        "model.bin" to "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1",
        "vision_config.json" to "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c",
        "mlc-chat-config.json" to "c039de2a0c0ec44016207af64a896f7cd3b6940962709c3e49c9321d6c666ff6",
        "tokenizer.model" to "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869"
    )
        
        // GGUF model files (NEW for vision support)
        private val REQUIRED_GGUF_FILES = mapOf(
            MODEL_FILE to 4_700_000_000L,  // ~4.7 GB
            MMPROJ_FILE to 1_100_000_000L  // ~1.1 GB (vision projector)
        )
        
        // Minimum expected model size (90% of actual size for validation)
        private const val MIN_MODEL_SIZE = 4231152L // 4.03MB (90% of 4.7MB)

        // Download configuration
        private const val DOWNLOAD_TIMEOUT_MS = 900_000L  // 15 minutes (1.12 GB at ~1.3 MB/s)
        private const val BUFFER_SIZE = 8192
        private const val PROGRESS_UPDATE_INTERVAL = 100L

        fun getNativeLibraryCandidates(context: Context): List<File> {
            val appContext = context.applicationContext ?: context
            val modelsDir = appContext?.filesDir?.let { File(it, "models") } ?: return emptyList()
            if (!modelsDir.exists()) {
                return emptyList()
            }

            val allSoFiles = modelsDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
                .toList()

            if (allSoFiles.isEmpty()) {
                return emptyList()
            }

            val preferredOrder = listOf("libmlc_llm_android.so", "minicpm_llm_q4f16_1.so")
            val prioritized = preferredOrder.mapNotNull { name ->
                allSoFiles.firstOrNull { it.name == name }
            }

            val remaining = allSoFiles.filterNot { prioritized.contains(it) }
            return (prioritized + remaining).distinct()
        }
    }
    
    private val modelDir = com.example.coupontracker.model.ModelPaths.modelDir(context)

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
     * Resolve the Qwen2.5 base URL, allowing remote configuration or staged rollouts to change mirrors.
     */
    private fun resolveQwen25BaseUrl(): String {
        val override = securePrefs.getQwen25ModelBaseUrlOverride()
            ?.trim()
            ?.trimEnd('/')

        return (override?.takeIf { it.isNotEmpty() } ?: QWEN25_BASE_URL).trimEnd('/')
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
            
            // Create .verified marker (required by LlmRuntimeManager)
            val verifiedMarker = File(modelDir, ".verified")
            verifiedMarker.writeText("Model verified: $MODEL_VERSION\nTimestamp: ${System.currentTimeMillis()}")
            Log.d(TAG, "Created .verified marker: ${verifiedMarker.absolutePath}")
            
            // Update preferences
            val modelSizeMB = getModelSizeMB()
            securePrefs.setLlmModelDownloaded(true)
            securePrefs.setLlmModelVersion(MODEL_VERSION)
            securePrefs.setLlmModelSizeMB(modelSizeMB)
            securePrefs.setLlmModelChecksum(calculateModelChecksum())

            verificationCache = VerificationCache(MODEL_VERSION, true)
            Log.d(TAG, "Model download completed successfully")
            progressCallback(DownloadProgress(100, "Download complete"))
            
            DownloadResult.Success(modelSizeMB, MODEL_VERSION)
            
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            DownloadResult.Error(resolveErrorMessage(e))
        }
    }
    
    /**
     * Download GGUF models (main model + mmproj for vision)
     * NEW: Supports downloading both base model and vision projector
     */
    suspend fun downloadGgufModels(progressCallback: (DownloadProgress) -> Unit): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting GGUF model download (with vision support)...")
            
            // Check network conditions
            if (!shouldProceedWithDownload()) {
                val message = if (securePrefs.getLlmDownloadWifiOnly()) {
                    "WiFi connection required for download"
                } else {
                    "No network connection available"
                }
                return@withContext DownloadResult.Error(message)
            }
            
            val baseUrl = resolveModelBaseUrl()
            var totalDownloaded = 0L
            val totalSize = REQUIRED_GGUF_FILES.values.sum()
            
            // Download main model (4.7 GB)
            progressCallback(DownloadProgress(0, "Downloading main model (4.7 GB)..."))
            val mainModelFile = File(modelDir, MODEL_FILE)
            
            val mainResult = downloadFile(
                url = "$baseUrl/$MODEL_FILE",
                outputFile = mainModelFile,
                progressCallback = { progress ->
                    val overallProgress = ((progress.progressPercent / 100.0 * REQUIRED_GGUF_FILES[MODEL_FILE]!!) / totalSize * 100).toInt()
                    progressCallback(DownloadProgress(
                        overallProgress,
                        "Downloading main model... ${progress.progressPercent}%"
                    ))
                }
            )
            
            mainResult.getOrElse { error ->
                mainModelFile.delete()
                return@withContext DownloadResult.Error("Main model download failed: ${resolveErrorMessage(error)}")
            }
            
            totalDownloaded += mainModelFile.length()
            Log.d(TAG, "Main model downloaded: ${mainModelFile.length() / 1_000_000} MB")
            
            // Download mmproj (1.1 GB)
            val mmprojProgress = (totalDownloaded.toFloat() / totalSize * 100).toInt()
            progressCallback(DownloadProgress(mmprojProgress, "Downloading vision projector (1.1 GB)..."))
            val mmprojFile = File(modelDir, MMPROJ_FILE)
            
            val mmprojResult = downloadFile(
                url = "$baseUrl/$MMPROJ_FILE",
                outputFile = mmprojFile,
                progressCallback = { progress ->
                    val baseProgress = (totalDownloaded.toFloat() / totalSize * 100).toInt()
                    val currentProgress = ((progress.progressPercent / 100.0 * REQUIRED_GGUF_FILES[MMPROJ_FILE]!!) / totalSize * 100).toInt()
                    val overallProgress = baseProgress + currentProgress
                    progressCallback(DownloadProgress(
                        overallProgress.coerceAtMost(99),
                        "Downloading vision projector... ${progress.progressPercent}%"
                    ))
                }
            )
            
            mmprojResult.getOrElse { error ->
                mmprojFile.delete()
                return@withContext DownloadResult.Error("Vision projector download failed: ${resolveErrorMessage(error)}")
            }
            
            totalDownloaded += mmprojFile.length()
            Log.d(TAG, "Vision projector downloaded: ${mmprojFile.length() / 1_000_000} MB")
            
            // Verify file sizes
            progressCallback(DownloadProgress(99, "Verifying downloads..."))
            
            if (mainModelFile.length() < REQUIRED_GGUF_FILES[MODEL_FILE]!! * 0.95) {
                mainModelFile.delete()
                mmprojFile.delete()
                return@withContext DownloadResult.Error("Main model file size incorrect")
            }
            
            if (mmprojFile.length() < REQUIRED_GGUF_FILES[MMPROJ_FILE]!! * 0.95) {
                mmprojFile.delete()
                return@withContext DownloadResult.Error("Vision projector file size incorrect")
            }
            
            // Create .verified marker
            val verifiedMarker = File(modelDir, ".verified")
            verifiedMarker.writeText("GGUF Model with Vision verified: $MODEL_VERSION\nTimestamp: ${System.currentTimeMillis()}\nFiles: $MODEL_FILE, $MMPROJ_FILE")
            Log.d(TAG, "Created .verified marker with vision support")
            
            // Update preferences
            val totalSizeMB = (totalDownloaded / (1024f * 1024f))
            securePrefs.setLlmModelDownloaded(true)
            securePrefs.setLlmModelVersion("$MODEL_VERSION-vision")
            securePrefs.setLlmModelSizeMB(totalSizeMB)
            
            verificationCache = VerificationCache("$MODEL_VERSION-vision", true)
            Log.d(TAG, "✅ GGUF model download completed: ${"%.1f".format(totalSizeMB)} MB (with vision support)")
            progressCallback(DownloadProgress(100, "Download complete - Vision enabled!"))
            
            // No manifest file available for this download path; return static version
            DownloadResult.Success(totalSizeMB, "$MODEL_VERSION-vision")
            
        } catch (e: Exception) {
            Log.e(TAG, "GGUF model download failed", e)
            DownloadResult.Error(resolveErrorMessage(e))
        }
    }

    /**
     * Download Qwen2.5 model (improved JSON output, text-only)
     * NEW: Replaces Qwen2 for better instruction-following
     */
    suspend fun downloadQwen25Model(
        modelId: String = com.example.coupontracker.model.ModelPaths.MODEL_ID_QWEN25,
        progressCallback: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting Qwen2.5 model download...")
            
            // Check network conditions
            if (!shouldProceedWithDownload()) {
                val message = if (securePrefs.getLlmDownloadWifiOnly()) {
                    "WiFi connection required for download"
                } else {
                    "No network connection available"
                }
                return@withContext DownloadResult.Error(message)
            }
            
            // Get model directory
            val modelDir = com.example.coupontracker.model.ModelPaths.modelDir(context, modelId)
            modelDir.mkdirs()
            
            // Download model file
            progressCallback(DownloadProgress(0, "Downloading Qwen2.5-1.5B model (940 MB)..."))
            val modelFile = File(modelDir, QWEN25_MODEL_FILE)
            
            val downloadResult = downloadFile(
                url = "${resolveQwen25BaseUrl()}/$QWEN25_MODEL_FILE",
                outputFile = modelFile,
                progressCallback = { progress ->
                    progressCallback(DownloadProgress(
                        progress.progressPercent,
                        "Downloading... ${progress.statusMessage}"
                    ))
                }
            )
            
            downloadResult.getOrElse { error ->
                modelFile.delete()
                return@withContext DownloadResult.Error("Download failed: ${resolveErrorMessage(error)}")
            }
            
            // Verify file size
            progressCallback(DownloadProgress(99, "Verifying download..."))
            
            if (modelFile.length() < QWEN25_MODEL_SIZE * 0.95) {
                modelFile.delete()
                return@withContext DownloadResult.Error("Model file size incorrect. Expected ~940 MB, got ${modelFile.length() / 1_000_000} MB")
            }
            
            Log.d(TAG, "Model downloaded: ${modelFile.length() / 1_000_000} MB")
            
            // ⭐ NEW: Deploy JSON grammar file for structured output enforcement
            progressCallback(DownloadProgress(99, "Deploying grammar file..."))
            deployGrammarFile(modelDir)
            
            // Create .verified marker
            val verifiedMarker = File(modelDir, ".verified")
            verifiedMarker.writeText("Qwen2.5 Model verified: $QWEN25_VERSION\nTimestamp: ${System.currentTimeMillis()}\nFile: $QWEN25_MODEL_FILE\nGrammar: coupon_schema.gbnf")
            Log.d(TAG, "Created .verified marker for Qwen2.5")
            
            // Update preferences
            val sizeMB = (modelFile.length() / (1024f * 1024f))
            securePrefs.setLlmModelDownloaded(true)
            securePrefs.setLlmModelVersion(QWEN25_VERSION)
            securePrefs.setLlmModelSizeMB(sizeMB)
            
            verificationCache = VerificationCache(QWEN25_VERSION, true)
            Log.d(TAG, "✅ Qwen2.5 model download completed: ${"%.1f".format(sizeMB)} MB")
            progressCallback(DownloadProgress(100, "Download complete!"))
            
            // No manifest file shipped; version is from constant
            DownloadResult.Success(sizeMB, QWEN25_VERSION)
            
        } catch (e: Exception) {
            Log.e(TAG, "Qwen2.5 model download failed", e)
            DownloadResult.Error(resolveErrorMessage(e))
        }
    }

    /**
     * Deploy JSON grammar file from assets to model directory
     * This enables strict JSON output formatting by constraining LLM token generation
     */
    private fun deployGrammarFile(modelDir: File) {
        try {
            val grammarFile = File(modelDir, "coupon_schema.gbnf")
            
            // Copy grammar from assets to model directory
            context.assets.open("coupon_schema.gbnf").use { input ->
                grammarFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.d(TAG, "✅ Deployed grammar file: ${grammarFile.absolutePath} (${grammarFile.length()} bytes)")
            Log.d(TAG, "   🎯 JSON grammar enforcement will be enabled during inference")
            
        } catch (e: Exception) {
            // Non-fatal: grammar is optional, model will work without it
            Log.w(TAG, "⚠️  Failed to deploy grammar file (will use standard sampling): ${e.message}")
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

            ensureNativeLibraryAlias()

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

    private fun ensureNativeLibraryAlias() {
        val existingAlias = modelDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "libmlc_llm_android.so" }

        if (existingAlias != null) {
            return
        }

        val primaryLibrary = modelDir.walkTopDown()
            .firstOrNull { it.isFile && it.name == "minicpm_llm_q4f16_1.so" }

        if (primaryLibrary != null) {
            val aliasFile = File(primaryLibrary.parentFile ?: modelDir, "libmlc_llm_android.so")
            runCatching {
                primaryLibrary.copyTo(aliasFile, overwrite = true)
                Log.d(TAG, "Created native library alias at ${aliasFile.absolutePath}")
            }.onFailure { error ->
                Log.w(TAG, "Failed to create native library alias", error)
            }
        }
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
    data class Success(val modelSizeMB: Float, val version: String) : DownloadResult()
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

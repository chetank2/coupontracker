package com.example.coupontracker.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.coupontracker.util.SecurePreferencesManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ModelManifest(
    val name: String,
    val version: String,
    val platform: String,
    val quantization: String,
    @SerializedName("requires_runtime_version")
    val requiresRuntimeVersion: String? = null,
    val files: List<FileEntry>
)

data class FileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
    val required: Boolean = true
)

sealed class ImportResult {
    data class Success(val manifest: ModelManifest, val sizeMB: Int) : ImportResult()
    data class Failed(val reason: String, val error: Throwable? = null) : ImportResult()
    data class Progress(val percent: Int, val message: String) : ImportResult()
}

@Singleton
class ModelImportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val securePreferences: SecurePreferencesManager
) {
    companion object {
        private const val TAG = "ModelImportManager"
        private const val VERIFIED_MARKER = ".verified"
        private const val STAGING_SUFFIX = ".staging"
    }
    
    private val gson = Gson()
    
    /**
     * Import model from user-selected ZIP file via SAF
     * @param uri Content URI from file picker
     * @param onProgress Progress callback
     * @return ImportResult
     */
    suspend fun importModel(
        uri: Uri,
        onProgress: (ImportResult.Progress) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting model import from: $uri")
            onProgress(ImportResult.Progress(5, "Checking storage space..."))
            
            val modelRoot = ModelPaths.root(context)
            val modelDir = ModelPaths.modelDir(context)
            val stagingDir = File(modelRoot, "${ModelPaths.DEFAULT_MODEL_ID}$STAGING_SUFFIX")

            val modelId = ModelPaths.DEFAULT_MODEL_ID
            val expectedModelSize = ModelPaths.getExpectedSize(modelId)
            val safetyMargin = if (ModelPaths.isGgufModel(modelId)) 350_000_000L else 600_000_000L
            val requiredSpace = expectedModelSize + safetyMargin
            val availableSpace = modelRoot.usableSpace
            if (availableSpace < requiredSpace) {
                return@withContext ImportResult.Failed(
                    "Insufficient storage: need ${formatStorage(requiredSpace)}, " +
                    "have ${formatStorage(availableSpace)}"
                )
            }
            
            onProgress(ImportResult.Progress(10, "Preparing..."))
            
            // Clean staging directory
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            stagingDir.mkdirs()
            
            onProgress(ImportResult.Progress(15, "Extracting model files..."))
            
            // Extract ZIP with zip-slip protection
            extractZipSecurely(uri, stagingDir, onProgress)
            
            onProgress(ImportResult.Progress(70, "Verifying model structure..."))
            
            // Load manifest (optional but recommended)
            val manifestFile = File(stagingDir, "manifest.json")
            val manifest = if (manifestFile.exists()) {
                try {
                    gson.fromJson(manifestFile.readText(), ModelManifest::class.java)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse manifest.json", e)
                    null
                }
            } else {
                Log.w(TAG, "No manifest.json found, will verify required files only")
                null
            }
            
            onProgress(ImportResult.Progress(75, "Verifying required files..."))
            
            // Detect format and get appropriate required files
            val requiredFiles = ModelPaths.getRequiredFiles(modelId)
            val isGguf = ModelPaths.isGgufModel(modelId)
            
            Log.d(TAG, "Detected model format: ${if (isGguf) "GGUF" else "Legacy MLC"}")
            
            // Verify all required files exist and meet size thresholds
            for (requiredPath in requiredFiles) {
                if (requiredPath == VERIFIED_MARKER) {
                    continue
                }

                val file = File(stagingDir, requiredPath)
                
                if (!file.exists()) {
                    cleanupStaging(stagingDir)
                    return@withContext ImportResult.Failed("Missing required file: $requiredPath")
                }
                
                val actualSize = file.length()
                if (actualSize == 0L) {
                    cleanupStaging(stagingDir)
                    return@withContext ImportResult.Failed("Empty file: $requiredPath")
                }
                
                // Check minimum size thresholds
                val minSize = ModelPaths.getMinFileSize(modelId, requiredPath)
                if (actualSize < minSize) {
                    cleanupStaging(stagingDir)
                    return@withContext ImportResult.Failed(
                        "$requiredPath is too small (${actualSize / 1_000_000} MB). " +
                        "Expected at least ${minSize / 1_000_000} MB. " +
                        "This appears to be a placeholder file."
                    )
                }
                
                Log.d(TAG, "✓ $requiredPath: ${actualSize / 1_000_000} MB")
            }
            
            // Verify SHA256 checksums if manifest provided
            if (manifest != null) {
                onProgress(ImportResult.Progress(80, "Verifying checksums..."))
                
                val totalFiles = manifest.files.filter { it.required }.size
                var filesVerified = 0
                
                for (entry in manifest.files.filter { it.required }) {
                    val file = File(stagingDir, entry.path)
                    
                    if (!file.exists()) {
                        Log.w(TAG, "Manifest references missing file: ${entry.path}")
                        continue
                    }
                    
                    onProgress(ImportResult.Progress(
                        80 + (filesVerified * 15 / totalFiles),
                        "Verifying ${entry.path}..."
                    ))
                    
                    val actualSha256 = calculateSha256(file) { percent ->
                        // Sub-progress for large files
                        val overallProgress = 80 + (filesVerified * 15 / totalFiles) + 
                                            (percent * 15 / (totalFiles * 100))
                        onProgress(ImportResult.Progress(
                            overallProgress,
                            "Verifying ${entry.path}... $percent%"
                        ))
                    }
                    
                    if (actualSha256 != entry.sha256.lowercase()) {
                        cleanupStaging(stagingDir)
                        return@withContext ImportResult.Failed(
                            "Checksum mismatch for ${entry.path}.\n" +
                            "Expected: ${entry.sha256}\n" +
                            "Got: $actualSha256\n" +
                            "File may be corrupted or tampered with."
                        )
                    }
                    
                    filesVerified++
                    Log.d(TAG, "✓ ${entry.path}: checksum verified")
                }
            }
            
            onProgress(ImportResult.Progress(95, "Finalizing installation..."))
            
            // Atomic install: delete old, rename staging to final
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            if (!stagingDir.renameTo(modelDir)) {
                cleanupStaging(stagingDir)
                return@withContext ImportResult.Failed("Failed to finalize model installation")
            }
            
            // Write verified marker
            val verifiedFile = File(modelDir, VERIFIED_MARKER)
            verifiedFile.writeText("${System.currentTimeMillis()}\n${manifest?.version ?: "unknown"}")
            
            // Calculate total size
            val totalSizeMB = (calculateDirectorySize(modelDir) / 1_000_000).toInt()
            
            // Update SecurePreferences for compatibility with existing code
            securePreferences.setLlmModelDownloaded(true)
            securePreferences.setLlmModelVersion(manifest?.version ?: "2.5.0-q4")
            securePreferences.setLlmModelSizeMB(totalSizeMB.toFloat())
            
            if (manifest != null) {
                // Store first weight file checksum as representative
                val weightChecksum = manifest.files.find { it.path.contains("model.bin") }?.sha256 ?: ""
                securePreferences.setLlmModelChecksum(weightChecksum)
            }
            
            onProgress(ImportResult.Progress(100, "Import complete"))
            
            Log.d(TAG, "✓ Model import successful: ${manifest?.name ?: "minicpm"} " +
                      "v${manifest?.version ?: "unknown"} ($totalSizeMB MB)")
            
            ImportResult.Success(
                manifest ?: createDefaultManifest(),
                totalSizeMB
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Model import failed", e)
            ImportResult.Failed("Import failed: ${e.message}", e)
        }
    }
    
    /**
     * Extract ZIP with zip-slip protection
     */
    private fun extractZipSecurely(
        zipUri: Uri,
        destDir: File,
        onProgress: (ImportResult.Progress) -> Unit
    ) {
        context.contentResolver.openInputStream(zipUri)?.use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream, 65536)).use { zipStream ->
                var entry = zipStream.nextEntry
                var fileCount = 0
                
                while (entry != null) {
                    val entryName = entry.name
                    val file = File(destDir, entryName)
                    
                    // Critical: zip-slip protection
                    val canonicalDestPath = destDir.canonicalPath + File.separator
                    val canonicalFilePath = file.canonicalPath
                    
                    if (!canonicalFilePath.startsWith(canonicalDestPath)) {
                        throw SecurityException("Zip entry escapes target directory: $entryName")
                    }
                    
                    // Reject symlinks (API 24+ compatible)
                    if (file.exists() && isSymbolicLinkCompat(file)) {
                        throw SecurityException("Symlink detected in zip: $entryName")
                    }
                    
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        
                        FileOutputStream(file).use { outputStream ->
                            val buffer = ByteArray(32768)
                            var bytesRead: Int
                            while (zipStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                            }
                        }
                        
                        fileCount++
                        
                        if (fileCount % 3 == 0) {
                            onProgress(ImportResult.Progress(
                                15 + (fileCount * 5).coerceAtMost(50),
                                "Extracted $fileCount files..."
                            ))
                        }
                    }
                    
                    zipStream.closeEntry()
                    entry = zipStream.nextEntry
                }
                
                Log.d(TAG, "Extracted $fileCount files from zip")
            }
        } ?: throw IOException("Cannot open input stream for URI: $zipUri")
    }
    
    /**
     * Calculate SHA256 with progress reporting for large files
     */
    private fun calculateSha256(
        file: File,
        onProgress: (Int) -> Unit = {}
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val fileSize = file.length()
        var bytesProcessed = 0L
        var lastReportedPercent = 0
        
        file.inputStream().buffered(65536).use { input ->
            val buffer = ByteArray(8 * 1024 * 1024) // 8 MB chunks
            var bytesRead: Int
            
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                bytesProcessed += bytesRead
                
                val percent = (bytesProcessed * 100 / fileSize).toInt()
                if (percent > lastReportedPercent && percent <= 100) {
                    lastReportedPercent = percent
                    onProgress(percent)
                }
            }
        }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    private fun calculateDirectorySize(directory: File): Long {
        var size = 0L
        directory.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
    
    private fun cleanupStaging(stagingDir: File) {
        try {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            Log.d(TAG, "Staging directory cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup staging directory", e)
        }
    }
    
    private fun createDefaultManifest(): ModelManifest {
        val modelId = ModelPaths.DEFAULT_MODEL_ID
        val modelDir = ModelPaths.modelDir(context, modelId)
        val files = ModelPaths.getRequiredFiles(modelId).map { path ->
            val file = File(modelDir, path)
            FileEntry(
                path = path,
                size = file.takeIf { it.exists() && it.isFile }?.length() ?: 0L,
                sha256 = "",
                required = true
            )
        }
        return ModelManifest(
            name = ModelPaths.getModelName(modelId),
            version = securePreferences.getLlmModelVersion()?.takeIf { it.isNotBlank() } ?: modelId,
            platform = "android",
            quantization = "Q4_K_M",
            requiresRuntimeVersion = "1.0.0",
            files = files
        )
    }
    
    /**
     * Check if model is currently installed
     * Supports both GGUF and legacy MLC formats
     */
    fun isModelInstalled(): Boolean {
        val modelDir = ModelPaths.modelDir(context)
        val verifiedFile = File(modelDir, VERIFIED_MARKER)
        
        if (!verifiedFile.exists() || verifiedFile.length() == 0L) {
            return false
        }
        
        // Get required files based on what's actually installed
        val modelId = ModelPaths.DEFAULT_MODEL_ID
        val requiredFiles = ModelPaths.getRequiredFiles(modelId)
        
        // Verify all required files still exist
        return requiredFiles.all { path ->
            val file = File(modelDir, path)
            file.exists() && when (path) {
                VERIFIED_MARKER -> file.length() > 0L
                else -> file.length() >= ModelPaths.getMinFileSize(modelId, path)
            }
        }
    }
    
    /**
     * Get installed model info
     */
    fun getInstalledModelInfo(): ModelManifest? {
        if (!isModelInstalled()) return null
        
        return try {
            val modelDir = ModelPaths.modelDir(context)
            val manifestFile = File(modelDir, "manifest.json")
            
            if (manifestFile.exists()) {
                gson.fromJson(manifestFile.readText(), ModelManifest::class.java)
            } else {
                createDefaultManifest()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading model manifest", e)
            null
        }
    }

    fun getInstalledModelSizeMB(): Int {
        if (!isModelInstalled()) return 0
        return (calculateDirectorySize(ModelPaths.modelDir(context)) / 1_000_000).toInt()
    }
    
    /**
     * Delete installed model
     */
    fun deleteModel(): Boolean {
        return try {
            val modelDir = ModelPaths.modelDir(context)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            
            // Clear preferences
            securePreferences.setLlmModelDownloaded(false)
            securePreferences.setLlmModelVersion("")
            securePreferences.setLlmModelSizeMB(0f)
            securePreferences.setLlmModelChecksum("")
            
            Log.d(TAG, "Model deleted successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting model", e)
            false
        }
    }
    
    /**
     * Check if file is a symbolic link (API 24+ compatible)
     * Uses java.nio.file.Files for API 26+ and canonical path comparison for API 24-25
     */
    private fun isSymbolicLinkCompat(file: File): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                // API 26+: Use Files.isSymbolicLink
                Files.isSymbolicLink(file.toPath())
            } else {
                // API 24-25: Compare canonical vs absolute path
                // Symlinks have different canonical and absolute paths
                val canonical = file.canonicalPath
                val absolute = file.absolutePath
                canonical != absolute
            }
        } catch (e: Exception) {
            // If we can't determine, assume it's not a symlink (fail open)
            Log.w(TAG, "Could not check for symlink: ${file.path}", e)
            false
        }
    }

    private fun formatStorage(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) {
            String.format("%.1f GB", gb)
        } else {
            val mb = bytes / 1_000_000.0
            String.format("%.0f MB", mb)
        }
    }
}

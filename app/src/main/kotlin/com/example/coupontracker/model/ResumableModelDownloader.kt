package com.example.coupontracker.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.min

/**
 * Resumable downloader for large model files (2-3GB)
 * Supports HTTP Range requests, pause/resume, and SHA-256 verification
 */
class ResumableModelDownloader(
    private val context: Context
) {
    companion object {
        private const val TAG = "ResumableDownloader"
        private const val BUFFER_SIZE = 8192
        private const val CHUNK_SIZE = 8 * 1024 * 1024  // 8MB chunks
    }
    
    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val speedBytesPerSecond: Long,
        val statusMessage: String
    )
    
    sealed class DownloadResult {
        data class Success(val file: File, val sha256: String) : DownloadResult()
        data class Failed(val reason: String, val error: Throwable? = null) : DownloadResult()
        data class Progress(val progress: DownloadProgress) : DownloadResult()
    }
    
    /**
     * Download a file with resume support
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        expectedSize: Long,
        expectedSha256: String? = null,
        onProgress: (DownloadProgress) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting download: $url")
            Log.d(TAG, "Destination: ${destFile.absolutePath}")
            
            // Create parent directories
            destFile.parentFile?.mkdirs()
            
            // Check if partially downloaded
            val existingSize = if (destFile.exists()) destFile.length() else 0L
            Log.d(TAG, "Existing file size: $existingSize bytes")
            
            // Open connection with Range support
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            
            // Request resume from existing size
            if (existingSize > 0) {
                connection.setRequestProperty("Range", "bytes=$existingSize-")
                Log.d(TAG, "Resuming from byte: $existingSize")
            }
            
            connection.connect()
            
            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")
            
            // Check if server supports resume
            val supportsResume = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (!supportsResume && existingSize > 0) {
                Log.w(TAG, "Server doesn't support resume, restarting download")
                destFile.delete()
            }
            
            // Get total size
            val contentLength = connection.contentLengthLong
            val totalSize = if (supportsResume) {
                existingSize + contentLength
            } else {
                contentLength
            }
            
            Log.d(TAG, "Total size: $totalSize bytes (${totalSize / 1_000_000}MB)")
            
            // Validate expected size (within 10% tolerance)
            if (expectedSize > 0) {
                val sizeDiff = kotlin.math.abs(totalSize - expectedSize)
                val tolerance = expectedSize * 0.1  // 10% tolerance
                if (sizeDiff > tolerance) {
                    return@withContext DownloadResult.Failed(
                        "Size mismatch: expected ${expectedSize / 1_000_000}MB, " +
                        "got ${totalSize / 1_000_000}MB"
                    )
                }
            }
            
            // Download the file
            connection.inputStream.use { input ->
                RandomAccessFile(destFile, "rw").use { output ->
                    // Seek to end if resuming
                    if (supportsResume && existingSize > 0) {
                        output.seek(existingSize)
                    }
                    
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesDownloaded = if (supportsResume) existingSize else 0L
                    var lastProgressUpdate = System.currentTimeMillis()
                    var lastBytesForSpeed = totalBytesDownloaded
                    val startTime = System.currentTimeMillis()
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesDownloaded += bytesRead
                        
                        // Update progress every 500ms
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) {
                            val elapsed = (now - lastProgressUpdate) / 1000.0
                            val bytesSinceLastUpdate = totalBytesDownloaded - lastBytesForSpeed
                            val speed = (bytesSinceLastUpdate / elapsed).toLong()
                            
                            val progress = DownloadProgress(
                                bytesDownloaded = totalBytesDownloaded,
                                totalBytes = totalSize,
                                progressPercent = ((totalBytesDownloaded * 100) / totalSize).toInt(),
                                speedBytesPerSecond = speed,
                                statusMessage = formatProgress(totalBytesDownloaded, totalSize, speed)
                            )
                            
                            onProgress(progress)
                            
                            lastProgressUpdate = now
                            lastBytesForSpeed = totalBytesDownloaded
                        }
                    }
                    
                    Log.d(TAG, "Download complete: $totalBytesDownloaded bytes")
                }
            }
            
            connection.disconnect()
            
            // Final progress update
            onProgress(DownloadProgress(
                bytesDownloaded = totalSize,
                totalBytes = totalSize,
                progressPercent = 100,
                speedBytesPerSecond = 0,
                statusMessage = "Download complete, verifying..."
            ))
            
            // Verify SHA-256 if provided
            if (expectedSha256 != null && expectedSha256 != "COMPUTE_ON_FIRST_DOWNLOAD") {
                Log.d(TAG, "Verifying SHA-256...")
                val actualSha256 = computeSha256(destFile) { progress ->
                    onProgress(DownloadProgress(
                        bytesDownloaded = totalSize,
                        totalBytes = totalSize,
                        progressPercent = 100,
                        speedBytesPerSecond = 0,
                        statusMessage = "Verifying: ${progress}%"
                    ))
                }
                
                if (actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    Log.d(TAG, "✓ SHA-256 verified")
                    DownloadResult.Success(destFile, actualSha256)
                } else {
                    Log.e(TAG, "SHA-256 mismatch!")
                    Log.e(TAG, "Expected: $expectedSha256")
                    Log.e(TAG, "Actual:   $actualSha256")
                    destFile.delete()
                    DownloadResult.Failed("Checksum verification failed")
                }
            } else {
                // Compute SHA-256 for first download
                Log.d(TAG, "Computing SHA-256 for verification...")
                val sha256 = computeSha256(destFile) { progress ->
                    onProgress(DownloadProgress(
                        bytesDownloaded = totalSize,
                        totalBytes = totalSize,
                        progressPercent = 100,
                        speedBytesPerSecond = 0,
                        statusMessage = "Computing checksum: ${progress}%"
                    ))
                }
                Log.d(TAG, "SHA-256: $sha256")
                DownloadResult.Success(destFile, sha256)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DownloadResult.Failed("Download error: ${e.message}", e)
        }
    }
    
    /**
     * Compute SHA-256 for large file with progress
     */
    private fun computeSha256(file: File, onProgress: (Int) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        val fileSize = file.length()
        var totalRead = 0L
        var lastProgress = 0
        
        file.inputStream().use { input ->
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
                totalRead += bytesRead
                
                val progress = ((totalRead * 100) / fileSize).toInt()
                if (progress != lastProgress && progress % 10 == 0) {
                    onProgress(progress)
                    lastProgress = progress
                }
            }
        }
        
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Format progress message
     */
    private fun formatProgress(downloaded: Long, total: Long, speedBps: Long): String {
        val downloadedMB = downloaded / 1_000_000.0
        val totalMB = total / 1_000_000.0
        val speedMBps = speedBps / 1_000_000.0
        
        return String.format(
            "%.1f / %.1f MB (%.1f MB/s)",
            downloadedMB,
            totalMB,
            speedMBps
        )
    }
    
    /**
     * Check if enough storage space is available
     */
    fun checkStorageSpace(requiredBytes: Long): Boolean {
        val availableSpace = context.filesDir.usableSpace
        Log.d(TAG, "Available space: ${availableSpace / 1_000_000}MB")
        Log.d(TAG, "Required space: ${requiredBytes / 1_000_000}MB")
        return availableSpace >= requiredBytes
    }
}


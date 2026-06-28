package com.example.coupontracker.network

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure model downloader with privacy guarantees
 * 
 * PRIVACY GUARANTEE:
 * - Only downloads models (GET requests to allowlisted hosts)
 * - NEVER uploads user data
 * - All inference is offline after download
 * - Network interceptors enforce these rules
 */
@Singleton
class SecureModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SecureModelDownloader"
        
        /**
         * Allowlisted hosts for model downloads
         * Only these hosts are permitted for network access
         */
        private val ALLOWED_HOSTS = setOf(
            "github.com",
            "raw.githubusercontent.com",
            "objects.githubusercontent.com",
            "huggingface.co",
            "cdn-lfs.huggingface.co"
            // Add your CDN here if you mirror models
        )
        
        /**
         * Privacy guarantee: Only these methods are allowed
         */
        private val ALLOWED_METHODS = setOf("GET", "HEAD")
        
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L
        private const val CHUNK_SIZE = 8192
        private val SHA256_REGEX = Regex("^[A-Fa-f0-9]{64}$")
    }
    
    /**
     * Allowlist interceptor - blocks any request not to approved model hosts
     */
    private class AllowlistInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            
            // Enforce HTTPS only
            if (url.scheme != "https") {
                throw SecurityException(
                    "SECURITY: Only HTTPS requests allowed. Blocked: ${url.scheme}://${url.host}"
                )
            }
            
            // Enforce allowlisted hosts
            if (url.host !in ALLOWED_HOSTS) {
                throw SecurityException(
                    "SECURITY: Host not in allowlist. Blocked: ${url.host}"
                )
            }
            
            // Enforce GET/HEAD only (no uploads)
            if (request.method !in ALLOWED_METHODS) {
                throw SecurityException(
                    "SECURITY: Only GET/HEAD allowed. Blocked: ${request.method}"
                )
            }
            
            // Log for audit
            Log.d(TAG, "✅ Allowlist check passed: ${request.method} ${url.host}")
            
            return chain.proceed(request)
        }
    }
    
    /**
     * Upload prevention interceptor - double-check no data is being uploaded
     */
    private class UploadPreventionInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            
            // Check for request body (uploads)
            if (request.body != null && request.body!!.contentLength() > 0) {
                throw SecurityException(
                    "SECURITY: Request body detected. No uploads allowed! Method: ${request.method}"
                )
            }
            
            return chain.proceed(request)
        }
    }
    
    /**
     * Create secure OkHttp client with all privacy guarantees
     */
    private fun createSecureClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .addInterceptor(AllowlistInterceptor())
            .addInterceptor(UploadPreventionInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
    
    data class DownloadProgress(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progressPercent: Int,
        val speedBytesPerSec: Long,
        val statusMessage: String
    )
    
    sealed class DownloadResult {
        data class Success(val file: File, val sha256: String) : DownloadResult()
        data class Failed(val reason: String, val error: Throwable? = null) : DownloadResult()
    }
    
    /**
     * Download file with resume support, SHA-256 verification, and progress tracking
     * 
     * @param url HTTPS URL to download from (must be in allowlist)
     * @param destFile Destination file
     * @param expectedSize Expected file size in bytes (for validation)
     * @param expectedSha256 Expected SHA-256 hash (null when no upstream checksum is available)
     * @param onProgress Progress callback
     * @return DownloadResult
     */
    suspend fun downloadFile(
        url: String,
        destFile: File,
        expectedSize: Long,
        expectedSha256: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val client = createSecureClient()
        
        try {
            Log.i(TAG, "Starting secure download: $url")
            Log.i(TAG, "  Destination: ${destFile.absolutePath}")
            Log.i(TAG, "  Expected size: ${expectedSize / 1_000_000} MB")
            
            // Ensure parent directory exists
            destFile.parentFile?.mkdirs()
            
            // Support resume if partial file exists
            val existingSize = if (destFile.exists()) destFile.length() else 0L
            
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (existingSize > 0) {
                        Log.i(TAG, "Resuming from byte $existingSize")
                        addHeader("Range", "bytes=$existingSize-")
                    }
                }
                .build()
            
            val startTime = System.currentTimeMillis()
            var lastProgressTime = startTime
            var lastProgressBytes = existingSize
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadResult.Failed(
                        "Download failed with HTTP ${response.code}"
                    )
                }
                
                val totalBytes = if (response.code == 206) {
                    // Partial content (resume)
                    existingSize + (response.body?.contentLength() ?: 0L)
                } else {
                    // Full download
                    response.body?.contentLength() ?: expectedSize
                }
                
                Log.i(TAG, "Download started: $totalBytes bytes total")
                
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile, existingSize > 0).use { output ->
                        val buffer = ByteArray(CHUNK_SIZE)
                        var bytesRead: Int
                        var totalRead = existingSize
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            
                            // Calculate speed and report progress
                            val now = System.currentTimeMillis()
                            if (now - lastProgressTime >= 500) { // Update every 500ms
                                val elapsedSec = (now - lastProgressTime) / 1000.0
                                val bytesInPeriod = totalRead - lastProgressBytes
                                val speedBps = (bytesInPeriod / elapsedSec).toLong()
                                
                                val percent = ((totalRead * 100) / totalBytes).toInt()
                                
                                onProgress(
                                    DownloadProgress(
                                        bytesDownloaded = totalRead,
                                        totalBytes = totalBytes,
                                        progressPercent = percent,
                                        speedBytesPerSec = speedBps,
                                        statusMessage = "Downloaded ${totalRead / 1_000_000} MB / ${totalBytes / 1_000_000} MB (${speedBps / 1_000_000} MB/s)"
                                    )
                                )
                                
                                lastProgressTime = now
                                lastProgressBytes = totalRead
                            }
                        }
                        
                        output.fd.sync() // Ensure data is written to disk
                    }
                }
                
                // Verify file size
                val actualSize = destFile.length()
                Log.i(TAG, "Download complete: $actualSize bytes")
                
                if (actualSize < expectedSize * 0.95) { // Allow 5% tolerance
                    return@withContext DownloadResult.Failed(
                        "File too small: $actualSize bytes (expected >= $expectedSize)"
                    )
                }
                
                // Calculate SHA-256
                val sha256 = calculateSha256(destFile)
                Log.i(TAG, "SHA-256: $sha256")
                
                // Verify checksum if provided
                if (expectedSha256 != null) {
                    if (!SHA256_REGEX.matches(expectedSha256)) {
                        destFile.delete()
                        return@withContext DownloadResult.Failed("Invalid expected SHA-256 checksum")
                    }
                    if (!sha256.equals(expectedSha256, ignoreCase = true)) {
                        destFile.delete()
                        return@withContext DownloadResult.Failed(
                            "SHA-256 mismatch! Expected: $expectedSha256, Got: $sha256"
                        )
                    }
                    Log.i(TAG, "✅ SHA-256 verification passed")
                }
                
                return@withContext DownloadResult.Success(destFile, sha256)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Security violation: ${e.message}")
            return@withContext DownloadResult.Failed("Security violation: ${e.message}", e)
        } catch (e: IOException) {
            Log.e(TAG, "❌ Download failed", e)
            return@withContext DownloadResult.Failed("Network error: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error", e)
            return@withContext DownloadResult.Failed("Unexpected error: ${e.message}", e)
        }
    }
    
    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Check if sufficient storage is available
     */
    fun checkStorageSpace(requiredBytes: Long): Boolean {
        val available = context.filesDir.usableSpace
        Log.d(TAG, "Storage check: ${available / 1_000_000_000} GB available, ${requiredBytes / 1_000_000_000} GB required")
        return available >= requiredBytes
    }
}

package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility class for persisting shared URIs by copying them to app storage
 * This ensures long-term access to images even after app restarts
 */
class UriPersistenceManager(private val context: Context) {

    companion object {
        private const val TAG = "UriPersistenceManager"
        private const val COUPON_IMAGES_DIR = "coupon_images"
        private const val FILE_PREFIX = "coupon_"
        private const val FILE_EXTENSION = ".jpg"
    }

    /**
     * Copy a shared URI to app storage for persistent access
     * @param sourceUri The original content:// URI from gallery/share
     * @return The file:// URI of the copied image, or null if copy failed
     */
    suspend fun persistUri(sourceUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Persisting URI: $sourceUri")

            // Skip if already a file URI in our app storage
            if (isAppStorageUri(sourceUri)) {
                Log.d(TAG, "URI already in app storage: $sourceUri")
                return@withContext sourceUri
            }

            // Create the coupon images directory if it doesn't exist
            val imagesDir = File(context.filesDir, COUPON_IMAGES_DIR)
            if (!imagesDir.exists()) {
                imagesDir.mkdirs()
            }

            // Generate unique filename
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val fileName = "$FILE_PREFIX$timestamp$FILE_EXTENSION"
            val destinationFile = File(imagesDir, fileName)

            // Copy the file
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for URI: $sourceUri")
                return@withContext null
            }

            // Create file URI using FileProvider for security
            val persistedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destinationFile
            )

            Log.d(TAG, "Successfully persisted URI: $sourceUri -> $persistedUri")
            return@withContext persistedUri

        } catch (e: Exception) {
            Log.e(TAG, "Error persisting URI: $sourceUri", e)
            return@withContext null
        }
    }

    /**
     * Copy multiple shared URIs to app storage
     * @param sourceUris List of original content:// URIs
     * @return List of persisted file:// URIs (may be smaller if some failed)
     */
    suspend fun persistUris(sourceUris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val persistedUris = mutableListOf<Uri>()
        
        for (sourceUri in sourceUris) {
            persistUri(sourceUri)?.let { persistedUri ->
                persistedUris.add(persistedUri)
            }
        }
        
        Log.d(TAG, "Persisted ${persistedUris.size}/${sourceUris.size} URIs")
        return@withContext persistedUris
    }

    /**
     * Check if a URI is already in app storage
     * @param uri The URI to check
     * @return True if the URI points to app storage
     */
    private fun isAppStorageUri(uri: Uri): Boolean {
        return when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return false
                path.startsWith(context.filesDir.absolutePath)
            }
            "content" -> {
                val authority = uri.authority
                authority == "${context.packageName}.fileprovider"
            }
            else -> false
        }
    }

    /**
     * Clean up old coupon images to prevent storage bloat
     * Removes images older than the specified number of days
     * @param olderThanDays Delete images older than this many days (default: 90)
     */
    suspend fun cleanupOldImages(olderThanDays: Int = 90) = withContext(Dispatchers.IO) {
        try {
            val imagesDir = File(context.filesDir, COUPON_IMAGES_DIR)
            if (!imagesDir.exists()) return@withContext

            val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
            var deletedCount = 0

            imagesDir.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            }

            Log.d(TAG, "Cleaned up $deletedCount old coupon images")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old images", e)
        }
    }

    /**
     * Get the size of the coupon images directory in bytes
     * @return Size in bytes
     */
    suspend fun getCouponImagesSize(): Long = withContext(Dispatchers.IO) {
        try {
            val imagesDir = File(context.filesDir, COUPON_IMAGES_DIR)
            if (!imagesDir.exists()) return@withContext 0L

            var totalSize = 0L
            imagesDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    totalSize += file.length()
                }
            }

            return@withContext totalSize
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating coupon images size", e)
            return@withContext 0L
        }
    }

    /**
     * Delete a specific persisted image file
     * @param uri The file URI to delete
     * @return True if deleted successfully
     */
    suspend fun deletePersistedImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isAppStorageUri(uri)) {
                Log.w(TAG, "URI is not in app storage, cannot delete: $uri")
                return@withContext false
            }

            val path = uri.path ?: return@withContext false
            val file = File(path)
            
            if (file.exists() && file.delete()) {
                Log.d(TAG, "Deleted persisted image: $uri")
                return@withContext true
            }

            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting persisted image: $uri", e)
            return@withContext false
        }
    }
}

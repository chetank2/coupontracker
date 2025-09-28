package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.util.*

/**
 * Utility class for extracting metadata from images, including capture timestamps
 */
object ImageMetadataExtractor {
    private const val TAG = "ImageMetadataExtractor"

    /**
     * Extract the capture timestamp from an image URI
     * @param context The context
     * @param uri The image URI
     * @return The capture timestamp as a Date, or null if not found
     */
    fun extractCaptureTimestamp(context: Context, uri: Uri): Date? {
        try {
            // First try MediaStore (for gallery images)
            val mediaStoreDate = extractFromMediaStore(context, uri)
            if (mediaStoreDate != null) {
                Log.d(TAG, "Found capture timestamp from MediaStore: $mediaStoreDate")
                return mediaStoreDate
            }

            // Then try EXIF data (for direct file access)
            val exifDate = extractFromExif(context, uri)
            if (exifDate != null) {
                Log.d(TAG, "Found capture timestamp from EXIF: $exifDate")
                return exifDate
            }

            // Finally try file modification time as fallback
            val fileDate = extractFromFileSystem(context, uri)
            if (fileDate != null) {
                Log.d(TAG, "Using file modification time as capture timestamp: $fileDate")
                return fileDate
            }

            Log.w(TAG, "No capture timestamp found for URI: $uri")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting capture timestamp from URI: $uri", e)
            return null
        }
    }

    /**
     * Extract timestamp from MediaStore
     */
    private fun extractFromMediaStore(context: Context, uri: Uri): Date? {
        return try {
            val projection = arrayOf(
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            )

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Try DATE_TAKEN first (most accurate for photos)
                    val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    if (dateTakenIndex >= 0) {
                        val dateTaken = cursor.getLong(dateTakenIndex)
                        if (dateTaken > 0) {
                            return Date(dateTaken)
                        }
                    }

                    // Fallback to DATE_ADDED
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    if (dateAddedIndex >= 0) {
                        val dateAdded = cursor.getLong(dateAddedIndex)
                        if (dateAdded > 0) {
                            return Date(dateAdded * 1000) // DATE_ADDED is in seconds
                        }
                    }

                    // Last resort: DATE_MODIFIED
                    val dateModifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                    if (dateModifiedIndex >= 0) {
                        val dateModified = cursor.getLong(dateModifiedIndex)
                        if (dateModified > 0) {
                            return Date(dateModified * 1000) // DATE_MODIFIED is in seconds
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting timestamp from MediaStore", e)
            null
        }
    }

    /**
     * Extract timestamp from EXIF data
     */
    private fun extractFromExif(context: Context, uri: Uri): Date? {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return null

            inputStream.use { stream ->
                val exif = ExifInterface(stream)
                
                // Try different EXIF date tags in order of preference
                val dateTags = listOf(
                    ExifInterface.TAG_DATETIME_ORIGINAL,  // When photo was taken
                    ExifInterface.TAG_DATETIME_DIGITIZED, // When photo was digitized
                    ExifInterface.TAG_DATETIME           // When file was modified
                )

                for (tag in dateTags) {
                    val dateString = exif.getAttribute(tag)
                    if (!dateString.isNullOrBlank()) {
                        try {
                            // EXIF date format: "YYYY:MM:DD HH:MM:SS"
                            val parts = dateString.split(" ")
                            if (parts.size == 2) {
                                val datePart = parts[0].replace(":", "-")
                                val timePart = parts[1]
                                val isoDateTime = "${datePart}T$timePart"
                                
                                val calendar = Calendar.getInstance()
                                val dateTimeParts = dateString.split(" ")
                                val dateParts = dateTimeParts[0].split(":")
                                val timeParts = dateTimeParts[1].split(":")
                                
                                calendar.set(
                                    dateParts[0].toInt(), // year
                                    dateParts[1].toInt() - 1, // month (0-based)
                                    dateParts[2].toInt(), // day
                                    timeParts[0].toInt(), // hour
                                    timeParts[1].toInt(), // minute
                                    timeParts[2].toInt()  // second
                                )
                                
                                return calendar.time
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error parsing EXIF date: $dateString", e)
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting timestamp from EXIF", e)
            null
        }
    }

    /**
     * Extract timestamp from file system (last modified time)
     */
    private fun extractFromFileSystem(context: Context, uri: Uri): Date? {
        return try {
            // This is a fallback and may not be accurate for capture time
            // but it's better than using current time
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        return Date(file.lastModified())
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting timestamp from file system", e)
            null
        }
    }

    /**
     * Get a reasonable fallback date for when no metadata is available
     * This assumes the image was captured recently (within the last day)
     */
    fun getFallbackCaptureDate(): Date {
        val calendar = Calendar.getInstance()
        // Assume image was taken 12 hours ago as a reasonable middle ground
        calendar.add(Calendar.HOUR_OF_DAY, -12)
        return calendar.time
    }

    /**
     * Check if a date seems reasonable as a capture timestamp
     * (not too far in the past or future)
     */
    fun isReasonableCaptureDate(date: Date): Boolean {
        val now = Calendar.getInstance()
        val dateCalendar = Calendar.getInstance().apply { time = date }
        
        // Should be within the last 10 years and not in the future
        val tenYearsAgo = Calendar.getInstance().apply { 
            add(Calendar.YEAR, -10) 
        }
        
        return dateCalendar.after(tenYearsAgo) && dateCalendar.before(now)
    }
}

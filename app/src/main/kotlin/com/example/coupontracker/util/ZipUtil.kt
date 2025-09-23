package com.example.coupontracker.util

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Utility class for working with ZIP files
 */
object ZipUtil {
    private const val TAG = "ZipUtil"
    private const val BUFFER_SIZE = 8192
    
    /**
     * Zip a directory and all its contents
     * @param directory The directory to zip
     * @param zipFile The output zip file
     */
    fun zipDirectory(directory: File, zipFile: File) {
        try {
            val buffer = ByteArray(BUFFER_SIZE)
            val outputStream = FileOutputStream(zipFile)
            val zipOutputStream = ZipOutputStream(BufferedOutputStream(outputStream))
            
            // Add all files in the directory to the zip file
            addFilesToZip(directory, directory, zipOutputStream, buffer)
            
            zipOutputStream.close()
            outputStream.close()
            
            Log.d(TAG, "Successfully zipped directory: ${directory.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error zipping directory", e)
        }
    }
    
    /**
     * Recursively add files to a zip file
     * @param rootDir The root directory (used for calculating relative paths)
     * @param currentDir The current directory being processed
     * @param zipOutputStream The zip output stream
     * @param buffer Buffer for reading files
     */
    private fun addFilesToZip(
        rootDir: File,
        currentDir: File,
        zipOutputStream: ZipOutputStream,
        buffer: ByteArray
    ) {
        val files = currentDir.listFiles() ?: return
        
        for (file in files) {
            if (file.isDirectory) {
                // Recursively add files in subdirectories
                addFilesToZip(rootDir, file, zipOutputStream, buffer)
            } else {
                // Add the file to the zip
                val inputStream = BufferedInputStream(FileInputStream(file), BUFFER_SIZE)
                
                // Calculate the path relative to the root directory
                val relativePath = file.absolutePath.substring(rootDir.absolutePath.length + 1)
                val entry = ZipEntry(relativePath.replace("\\\\", "/"))
                
                zipOutputStream.putNextEntry(entry)
                
                var count: Int
                while (inputStream.read(buffer, 0, BUFFER_SIZE).also { count = it } != -1) {
                    zipOutputStream.write(buffer, 0, count)
                }
                
                inputStream.close()
                zipOutputStream.closeEntry()
            }
        }
    }
    
    /**
     * Unzip a file to a directory
     * @param zipFile The zip file to extract
     * @param targetDirectory The directory to extract to
     */
    fun unzip(zipFile: File, targetDirectory: File) {
        try {
            if (!targetDirectory.exists()) {
                targetDirectory.mkdirs()
            }
            
            val buffer = ByteArray(BUFFER_SIZE)
            val zipInputStream = ZipInputStream(BufferedInputStream(FileInputStream(zipFile)))
            
            var zipEntry = zipInputStream.nextEntry
            while (zipEntry != null) {
                val newFile = File(targetDirectory, zipEntry.name)
                
                // Create parent directories if they don't exist
                val parentDir = newFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }
                
                if (!zipEntry.isDirectory) {
                    // Extract the file
                    val outputStream = FileOutputStream(newFile)
                    val bufferedOutputStream = BufferedOutputStream(outputStream, BUFFER_SIZE)
                    
                    var count: Int
                    while (zipInputStream.read(buffer, 0, BUFFER_SIZE).also { count = it } != -1) {
                        bufferedOutputStream.write(buffer, 0, count)
                    }
                    
                    bufferedOutputStream.flush()
                    bufferedOutputStream.close()
                    outputStream.close()
                } else {
                    // Create directory
                    newFile.mkdirs()
                }
                
                zipInputStream.closeEntry()
                zipEntry = zipInputStream.nextEntry
            }
            
            zipInputStream.close()
            
            Log.d(TAG, "Successfully unzipped file: ${zipFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error unzipping file", e)
        }
    }
}

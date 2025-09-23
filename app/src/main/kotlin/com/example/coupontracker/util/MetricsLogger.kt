package com.example.coupontracker.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Simple utility to persist OCR metrics to an on-device CSV file and export them for debugging.
 */
object MetricsLogger {
    private const val TAG = "MetricsLogger"
    private const val LOG_DIR = "logs"
    private const val FILE_NAME = "ocr_metrics.csv"

    private fun ensureLogDirectory(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun logFile(context: Context): File {
        val dir = ensureLogDirectory(context)
        val file = File(dir, FILE_NAME)
        if (!file.exists()) {
            file.appendText("timestamp,detection_ms,roi_ms,roi_count,best_confidence,fallback\n")
        }
        return file
    }

    fun logOcrEvent(
        context: Context,
        detectionMs: Long,
        roiMs: Long,
        roiCount: Int,
        bestConfidence: Float,
        fallbackUsed: Boolean
    ) {
        runCatching {
            val file = logFile(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date())
            FileWriter(file, true).use { writer ->
                writer.appendLine(
                    listOf(
                        timestamp,
                        detectionMs.toString(),
                        roiMs.toString(),
                        roiCount.toString(),
                        String.format(Locale.US, "%.2f", bestConfidence),
                        fallbackUsed.toString()
                    ).joinToString(",")
                )
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to write OCR metrics", throwable)
        }
    }

    fun exportLogs(context: Context): File? {
        return runCatching {
            val source = logFile(context)
            if (!source.exists() || source.length() == 0L) {
                null
            } else {
                val exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: ensureLogDirectory(context)
                if (!exportDir.exists()) exportDir.mkdirs()
                val exportFile = File(
                    exportDir,
                    "ocr_metrics_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.csv"
                )
                source.copyTo(exportFile, overwrite = true)
                exportFile
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to export logs", throwable)
        }.getOrNull()
    }

    fun clearLogs(context: Context) {
        runCatching {
            val file = logFile(context)
            if (file.exists()) {
                file.delete()
            }
        }
    }
}

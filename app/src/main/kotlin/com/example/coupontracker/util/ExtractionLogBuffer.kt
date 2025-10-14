package com.example.coupontracker.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory buffer for coupon extraction logs.
 */
object ExtractionLogBuffer {

    private const val MAX_ENTRIES = 200

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val entries = CopyOnWriteArrayList<String>()

    fun append(tag: String, level: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val builder = StringBuilder()
        builder.append("[$timestamp]")
            .append(" [").append(level.uppercase(Locale.US)).append("]")
            .append(" [").append(tag).append("] ")
            .append(message)

        throwable?.let {
            builder.append('\n').append(it.stackTraceToString())
        }

        entries += builder.toString()
        if (entries.size > MAX_ENTRIES) {
            val overflow = entries.size - MAX_ENTRIES
            repeat(overflow) {
                entries.removeAt(0)
            }
        }
    }

    fun appendInfo(tag: String, message: String) = append(tag, "INFO", message)

    fun appendWarning(tag: String, message: String) = append(tag, "WARN", message)

    fun appendError(tag: String, message: String, throwable: Throwable? = null) =
        append(tag, "ERROR", message, throwable)

    fun getLogText(): String = entries.joinToString(separator = "\n\n")

    fun clear() {
        entries.clear()
    }
}



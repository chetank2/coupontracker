package com.example.coupontracker.util

import android.graphics.Bitmap
import com.example.coupontracker.ocr.OcrTextSpan
import kotlin.math.abs

/**
 * Removes Android system chrome OCR before coupon extraction.
 *
 * This intentionally uses geometry first and text only as evidence. A token like
 * "Paytm" can be a real merchant, so it is removed only when it sits in the
 * top system area alongside status/notification indicators.
 */
object OcrChromeFilter {
    private val timePattern = Regex("""^\d{1,2}:\d{2}(?:\s*[AP]M)?$""", RegexOption.IGNORE_CASE)
    private val percentPattern = Regex("""^\d{1,3}%$""")
    private val networkPattern = Regex("""^(?:5G|4G|LTE|VoLTE|Wi-?Fi|SIM\d*)$""", RegexOption.IGNORE_CASE)
    private val notificationWordPattern = Regex("""^(?:gmail|mail|paytm|pautm|phonepe|gpay|cred)$""", RegexOption.IGNORE_CASE)
    private val navigationPattern = Regex("""^(?:back|home|recent|close|dismiss)$""", RegexOption.IGNORE_CASE)

    fun filterAndFlatten(bitmap: Bitmap, spans: List<OcrTextSpan>): String {
        return flatten(filter(bitmap.width, bitmap.height, spans))
    }

    fun filterAndFlatten(width: Int, height: Int, spans: List<OcrTextSpan>): String {
        return flatten(filter(width, height, spans))
    }

    fun filter(width: Int, height: Int, spans: List<OcrTextSpan>): List<OcrTextSpan> {
        if (width <= 0 || height <= 0 || spans.isEmpty()) return spans

        val topScanLimit = (height * 0.14f).toInt()
        val bottomLimit = (height * 0.94f).toInt()
        val topSpans = spans.filter { it.boundingBox.top < topScanLimit }
        val topChromeBottom = resolveTopChromeBottom(topSpans, width, height)

        return spans.filter { span ->
            val text = span.text.trim()
            val inTopChrome = topChromeBottom != null && span.boundingBox.centerYCompat() <= topChromeBottom
            val inBottomChrome = span.boundingBox.bottom > bottomLimit && isBottomChromeText(text)
            !inTopChrome && !inBottomChrome
        }
    }

    private fun resolveTopChromeBottom(topSpans: List<OcrTextSpan>, width: Int, height: Int): Int? {
        if (!hasTopSystemChrome(topSpans, width, height)) return null

        val chromeCandidates = topSpans.filter { isStatusChromeText(it.text.trim()) }
        if (chromeCandidates.isEmpty()) return null

        val topMostCenter = chromeCandidates.minOf { it.boundingBox.centerYCompat() }
        val rowTolerance = maxOf(32, (height * 0.018f).toInt())
        val chromeRow = topSpans.filter { abs(it.boundingBox.centerYCompat() - topMostCenter) <= rowTolerance }
        val rowBottom = chromeRow.maxOfOrNull { it.boundingBox.bottom } ?: return null
        val maxChromeBottom = (height * 0.08f).toInt()

        return minOf(rowBottom + rowTolerance, maxChromeBottom)
    }

    private fun hasTopSystemChrome(topSpans: List<OcrTextSpan>, width: Int, height: Int): Boolean {
        if (topSpans.isEmpty()) return false

        val texts = topSpans.map { it.text.trim() }.filter { it.isNotBlank() }
        val statusHits = texts.count(::isStatusChromeText)
        val hasTime = texts.any { timePattern.matches(it) }
        val hasSystemIndicator = texts.any { percentPattern.matches(it) || networkPattern.matches(it) }
        val hasNotificationText = texts.any { notificationWordPattern.matches(it) }
        val screenshotShape = width >= 720 && height >= 1280 && height.toFloat() / width.toFloat() > 1.3f

        return statusHits >= 2 ||
            (hasTime && hasSystemIndicator) ||
            (screenshotShape && hasTime && hasNotificationText) ||
            (screenshotShape && hasSystemIndicator && hasNotificationText)
    }

    private fun isStatusChromeText(text: String): Boolean {
        return timePattern.matches(text) ||
            percentPattern.matches(text) ||
            networkPattern.matches(text) ||
            notificationWordPattern.matches(text)
    }

    private fun isBottomChromeText(text: String): Boolean {
        return navigationPattern.matches(text) || text.all { !it.isLetterOrDigit() }
    }

    private fun flatten(spans: List<OcrTextSpan>): String {
        if (spans.isEmpty()) return ""

        val sorted = spans.sortedWith(
            compareBy<OcrTextSpan> { centerY(it) }
                .thenBy { it.boundingBox.left }
        )
        val lines = mutableListOf<MutableList<OcrTextSpan>>()
        val lineTolerance = 14

        sorted.forEach { span ->
            val line = lines.firstOrNull { existing ->
                abs(centerY(existing.first()) - centerY(span)) <= lineTolerance
            }
            if (line != null) {
                line.add(span)
            } else {
                lines.add(mutableListOf(span))
            }
        }

        return lines
            .map { line ->
                line.sortedBy { it.boundingBox.left }
                    .joinToString(" ") { it.text.trim() }
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    private fun centerY(span: OcrTextSpan): Int {
        return span.boundingBox.centerYCompat()
    }

    private fun android.graphics.Rect.centerYCompat(): Int {
        return (top + bottom) / 2
    }
}

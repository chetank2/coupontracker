package com.example.coupontracker.ocr

import android.graphics.Rect

/**
 * Merges two OCR span lists. Primary (MLKit) is the base; for each secondary
 * (Tesseract) span, we either add it (if no primary span covers the same
 * vertical band within 10px) or replace a lower-confidence primary span.
 *
 * Vertical-band overlap is a coarse proxy for "same text region" since OCR
 * engines occasionally differ in horizontal tokenisation.
 */
object OcrMerger {

    const val VERTICAL_OVERLAP_PX = 10

    fun merge(primary: List<OcrTextSpan>, secondary: List<OcrTextSpan>): List<OcrTextSpan> {
        val out = primary.toMutableList()
        for (sec in secondary) {
            val overlapIndex = out.indexOfFirst { isOverlap(it.boundingBox, sec.boundingBox) }
            if (overlapIndex < 0) {
                out += sec
            } else if (sec.confidence > out[overlapIndex].confidence) {
                out[overlapIndex] = sec
            }
        }
        return out
    }

    private fun isOverlap(a: Rect, b: Rect): Boolean {
        val midA = (a.top + a.bottom) / 2
        val midB = (b.top + b.bottom) / 2
        return kotlin.math.abs(midA - midB) <= VERTICAL_OVERLAP_PX
    }
}

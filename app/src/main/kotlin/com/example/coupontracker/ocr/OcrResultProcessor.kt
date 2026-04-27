package com.example.coupontracker.ocr

import com.example.coupontracker.util.OcrTextCleaner
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalizes raw OCR output (tiles + text) before it is fed to the LLM prompt builder.
 * Handles deskewing (sorting by geometry), merging adjacent tiles into lines, and
 * removing obvious UI noise. Emits quality metrics for observability.
 */
class OcrResultProcessor {

    data class OcrTile(
        val text: String,
        val bounds: BoundingBox?,
        val confidence: Float
    )

    data class ProcessedOcrResult(
        val normalizedText: String,
        val mergedLines: List<String>,
        val averageConfidence: Float,
        val unknownGlyphRate: Float,
        val tileCount: Int,
        val removedNoiseLines: Int,
        val alphanumericCharCount: Int,
        val rawCharCount: Int
    ) {
        fun meetsQualityThreshold(
            minAverageConfidence: Float,
            maxUnknownGlyphRate: Float,
            minAlphaNumericChars: Int
        ): Boolean {
            val confidenceGate = averageConfidence == 0f || averageConfidence >= minAverageConfidence
            val glyphGate = unknownGlyphRate <= maxUnknownGlyphRate
            val lengthGate = alphanumericCharCount >= minAlphaNumericChars
            return confidenceGate && glyphGate && lengthGate && normalizedText.isNotBlank()
        }
    }

    companion object {
        private val UNKNOWN_GLYPH_PATTERN = Regex("[\\uFFFD\\u25A1?]")
    }

    fun process(rawText: String, tiles: List<OcrTile> = emptyList()): ProcessedOcrResult {
        val sanitizedTiles = sanitizeTiles(tiles)
        val (mergedLines, removedNoise) = mergeAndCleanLines(rawText, sanitizedTiles)
        val cleanedText = mergedLines.joinToString(separator = "\n").trim()
        val cleanedWithFallback = if (cleanedText.isBlank()) rawText.trim() else cleanedText
        val cleanedResult = OcrTextCleaner.cleanForLlmExtraction(cleanedWithFallback)
        val normalizedText = cleanedResult.cleanedText.ifBlank { cleanedWithFallback }

        val alphanumericChars = normalizedText.count { it.isLetterOrDigit() }
        val avgConfidence = calculateAverageConfidence(sanitizedTiles)
        val unknownGlyphRate = calculateUnknownGlyphRate(normalizedText)

        System.err.println(
            String.format(
                Locale.US,
                "OcrResultProcessor: ocr-quality avg_conf=%.3f unknown_rate=%.3f tiles=%d lines=%d removed_noise=%d raw_chars=%d normalized_chars=%d",
                avgConfidence,
                unknownGlyphRate,
                sanitizedTiles.size,
                mergedLines.size,
                removedNoise,
                rawText.length,
                normalizedText.length
            )
        )

        return ProcessedOcrResult(
            normalizedText = normalizedText,
            mergedLines = mergedLines,
            averageConfidence = avgConfidence,
            unknownGlyphRate = unknownGlyphRate,
            tileCount = sanitizedTiles.size,
            removedNoiseLines = removedNoise,
            alphanumericCharCount = alphanumericChars,
            rawCharCount = rawText.length
        )
    }

    private fun sanitizeTiles(tiles: List<OcrTile>): List<OcrTile> {
        return tiles
            .filter { it.text.isNotBlank() }
            .map { tile ->
                val clampedConfidence = tile.confidence.coerceIn(0f, 1f)
                tile.copy(text = tile.text.trim(), confidence = clampedConfidence)
            }
    }

    private fun mergeAndCleanLines(
        rawText: String,
        tiles: List<OcrTile>
    ): Pair<List<String>, Int> {
        if (tiles.isEmpty()) {
            val rawLines = rawText.lines().map { it.trim() }
            val cleaned = rawLines.filterNot { OcrTextCleaner.isUiChrome(it) }.filter { it.isNotBlank() }
            return cleaned to (rawLines.size - cleaned.size)
        }

        val sortedTiles = tiles.sortedWith(
            compareBy<OcrTile> { it.bounds?.centerY ?: Float.MAX_VALUE }
                .thenBy { it.bounds?.left ?: Int.MIN_VALUE }
        )

        val rows = mutableListOf<MutableList<OcrTile>>()
        for (tile in sortedTiles) {
            val currentRow = rows.lastOrNull()
            if (currentRow == null) {
                rows.add(mutableListOf(tile))
                continue
            }

            val currentCenter = averageCenterY(currentRow)
            val targetCenter = tile.bounds?.centerY
            val threshold = rowMergeThreshold(currentRow, tile)
            if (currentCenter != null && targetCenter != null && abs(targetCenter - currentCenter) <= threshold) {
                currentRow.add(tile)
            } else {
                rows.add(mutableListOf(tile))
            }
        }

        val mergedLines = rows.map { row ->
            row.sortedBy { it.bounds?.left ?: 0 }
                .joinToString(separator = " ") { normalizeToken(it.text) }
                .replace("  ", " ")
                .trim()
        }

        val cleanedLines = mergedLines.filterNot { OcrTextCleaner.isUiChrome(it) }.filter { it.isNotBlank() }
        val removedNoise = mergedLines.count { it.isBlank() || OcrTextCleaner.isUiChrome(it) }
        return cleanedLines to removedNoise
    }

    private fun averageCenterY(row: List<OcrTile>): Float? {
        val centers = row.mapNotNull { it.bounds?.centerY }
        if (centers.isEmpty()) return null
        return centers.average().toFloat()
    }

    private fun rowMergeThreshold(currentRow: List<OcrTile>, candidate: OcrTile): Float {
        val heights = currentRow.mapNotNull { it.bounds?.height?.toFloat() }
        val candidateHeight = candidate.bounds?.height?.toFloat()
        val avgHeight = when {
            heights.isNotEmpty() -> heights.average().toFloat()
            candidateHeight != null -> candidateHeight
            else -> 24f
        }
        return max(8f, avgHeight * 0.6f)
    }

    private fun normalizeToken(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.endsWith("-") -> trimmed.dropLast(1)
            else -> trimmed
        }
    }

    private fun calculateAverageConfidence(tiles: List<OcrTile>): Float {
        if (tiles.isEmpty()) return 0f
        val confidences = tiles.map { it.confidence }.filter { it > 0f }
        if (confidences.isEmpty()) return 0f
        val sum = confidences.sum()
        return (sum / confidences.size).coerceIn(0f, 1f)
    }

    private fun calculateUnknownGlyphRate(text: String): Float {
        if (text.isEmpty()) return 0f
        val unknownCount = UNKNOWN_GLYPH_PATTERN.findAll(text).count()
        return min(1f, unknownCount.toFloat() / text.length.toFloat())
    }
}

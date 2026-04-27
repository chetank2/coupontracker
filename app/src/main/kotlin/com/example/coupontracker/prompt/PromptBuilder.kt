package com.example.coupontracker.prompt

import com.example.coupontracker.ocr.OcrResultProcessor
import com.example.coupontracker.ocr.OcrResultProcessor.OcrTile
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.Schema

/**
 * Builds LLM prompts from OCR output while applying normalization and schema guardrails.
 */
class PromptBuilder(
    private val schema: Schema = CouponSchema.SCHEMA,
    private val ocrResultProcessor: OcrResultProcessor = OcrResultProcessor(),
    private val isV2Enabled: () -> Boolean = { false }
) {

    data class Result(
        val prompt: String,
        val systemPrompt: String,
        val userPrompt: String,
        val assistantPrimer: String,
        val processedOcr: OcrResultProcessor.ProcessedOcrResult,
        val truncatedOcrForPrompt: String
    )

    fun build(rawOcrText: String, tiles: List<OcrTile> = emptyList()): Result {
        val processed = ocrResultProcessor.process(rawOcrText, tiles)
        val promptParts = PromptFormatter.build(
            schema = schema,
            ocr = PromptOcrSnapshot(
                normalizedText = processed.normalizedText,
                mergedLines = processed.mergedLines,
                averageConfidence = processed.averageConfidence,
                unknownGlyphRate = processed.unknownGlyphRate
            ),
            v2Enabled = isV2Enabled()
        )
        return Result(
            prompt = promptParts.prompt,
            systemPrompt = promptParts.systemPrompt,
            userPrompt = promptParts.userPrompt,
            assistantPrimer = promptParts.assistantPrimer,
            processedOcr = processed,
            truncatedOcrForPrompt = promptParts.truncatedOcrForPrompt
        )
    }
}

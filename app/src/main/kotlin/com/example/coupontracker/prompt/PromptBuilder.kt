package com.example.coupontracker.prompt

import com.example.coupontracker.ocr.OcrResultProcessor
import com.example.coupontracker.ocr.OcrResultProcessor.OcrTile
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.Schema
import com.example.coupontracker.schema.PromptGenerator
import java.util.Locale

/**
 * Builds LLM prompts from OCR output while applying normalization and schema guardrails.
 */
class PromptBuilder(
    private val schema: Schema = CouponSchema.SCHEMA,
    private val ocrResultProcessor: OcrResultProcessor = OcrResultProcessor()
) {

    data class Result(
        val prompt: String,
        val systemPrompt: String,
        val userPrompt: String,
        val assistantPrimer: String,
        val processedOcr: OcrResultProcessor.ProcessedOcrResult,
        val truncatedOcrForPrompt: String
    )

    companion object {
        private const val MAX_OCR_CHARS = 360
        private const val MAX_OCR_LINES = 18
        private val HIGH_SIGNAL_KEYWORDS = listOf(
            "code",
            "coupon",
            "redeem",
            "offer",
            "cashback",
            "expires",
            "valid",
            "store",
            "brand",
            "discount"
        )
    }

    fun build(rawOcrText: String, tiles: List<OcrTile> = emptyList()): Result {
        val processed = ocrResultProcessor.process(rawOcrText, tiles)
        val ocrExcerpt = buildOcrExcerpt(processed)
        val system = buildSystemPrompt()
        val user = buildUserPrompt(ocrExcerpt, processed)
        val assistant = "<|im_start|>assistant\n{"
        val prompt = listOf(system, user, assistant).joinToString(separator = "\n\n")
        return Result(
            prompt = prompt,
            systemPrompt = system,
            userPrompt = user,
            assistantPrimer = assistant,
            processedOcr = processed,
            truncatedOcrForPrompt = ocrExcerpt
        )
    }

    private fun buildSystemPrompt(): String {
        val requiredFields = schema.getRequiredFields().joinToString { it.name }
        val optionalFields = schema.getOptionalFields().joinToString { it.name }
        return buildString {
            appendLine("<|im_start|>system")
            appendLine("You return exactly one compact JSON object and nothing else.")
            appendLine("Required keys: $requiredFields")
            if (optionalFields.isNotBlank()) {
                appendLine("Optional keys (use the string \"unknown\" when unavailable): $optionalFields")
            }
            appendLine("Rules: no markdown, no comments, no extra keys, preserve coupon text verbatim.")
            appendLine("All string values must be trimmed and non-empty. Never output null, \"null\", \"NULL\", \"N/A\", or empty strings.")
            appendLine("If a field is truly missing, output the literal string \"unknown\".")
            appendLine("storeName must be the merchant or brand highlighted in the coupon.")
            appendLine("redeemCode must match the coupon code text exactly; choose the most prominent if multiple exist.")
            appendLine("expiryDate must repeat the date format found in the coupon (for example, \"31 May, 2025\").")
            appendLine("Keep storeNameEvidence to between zero and three short snippets copied from the OCR text.")
            append("<|im_end|>")
        }
    }

    private fun buildUserPrompt(
        truncatedOcr: String,
        processed: OcrResultProcessor.ProcessedOcrResult
    ): String {
        val metrics = String.format(
            Locale.US,
            "metrics: avg_conf=%.2f unknown_rate=%.2f", processed.averageConfidence, processed.unknownGlyphRate
        )
        return buildString {
            appendLine("<|im_start|>user")
            appendLine("Structure the OCR excerpt into JSON (quality $metrics). Respond with JSON only.")
            appendLine("Prioritise extracting merchant/store name, coupon description, redeem code, and expiry date from the text below.")
            appendLine("If any of those are missing in the text, write \"unknown\" but do not invent new information.")
            appendLine("OCR excerpt:")
            appendLine(truncatedOcr)
            append("<|im_end|>")
        }
    }

    private fun buildOcrExcerpt(processed: OcrResultProcessor.ProcessedOcrResult): String {
        val prioritized = LinkedHashSet<String>()
        val merged = processed.mergedLines.map { it.trim() }.filter { it.isNotEmpty() }
        val highlight = merged.filter { line ->
            val lower = line.lowercase(Locale.US)
            HIGH_SIGNAL_KEYWORDS.any { keyword -> lower.contains(keyword) }
        }
        highlight.take(MAX_OCR_LINES).forEach { prioritized += it }
        merged.take(MAX_OCR_LINES).forEach { prioritized += it }

        val builder = StringBuilder()
        var linesAdded = 0
        prioritized.forEach { line ->
            if (linesAdded >= MAX_OCR_LINES) return@forEach
            val pendingLength = if (builder.isEmpty()) line.length else builder.length + 1 + line.length
            if (pendingLength > MAX_OCR_CHARS) return@forEach
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
            linesAdded++
        }

        if (builder.isNotEmpty()) {
            return builder.toString()
        }

        return PromptGenerator.sanitizeOcrSnippet(processed.normalizedText, MAX_OCR_CHARS)
    }
}

package com.example.coupontracker.prompt

import com.example.coupontracker.schema.PromptGenerator
import com.example.coupontracker.schema.Schema
import java.util.Locale

data class PromptOcrSnapshot(
    val normalizedText: String,
    val mergedLines: List<String>,
    val averageConfidence: Float,
    val unknownGlyphRate: Float
)

data class PromptParts(
    val prompt: String,
    val systemPrompt: String,
    val userPrompt: String,
    val assistantPrimer: String,
    val truncatedOcrForPrompt: String
)

object PromptFormatter {
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

    private val CTA_LINE_PATTERNS = listOf(
        Regex("""^copy$""", RegexOption.IGNORE_CASE),
        Regex("""^tap to copy$""", RegexOption.IGNORE_CASE),
        Regex("""^(?:avail|apply|subscribe|redeem|claim|grab|shop|buy) now$""", RegexOption.IGNORE_CASE),
        Regex("""^get deal$""", RegexOption.IGNORE_CASE)
    )

    private val CTA_SUFFIXES = listOf(
        "copy",
        "copy code",
        "tap to copy",
        "avail now",
        "apply now",
        "subscribe now",
        "redeem now",
        "claim now",
        "grab deal",
        "shop now",
        "buy now",
        "get offer",
        "get deal"
    )

    fun build(schema: Schema, ocr: PromptOcrSnapshot, v2Enabled: Boolean = false): PromptParts {
        val ocrExcerpt = buildOcrExcerpt(ocr)
        val system = buildSystemPrompt(schema, v2Enabled)
        val user = buildUserPrompt(ocrExcerpt, ocr)
        val assistant = "<|im_start|>assistant\n{"
        val prompt = listOf(system, user, assistant).joinToString(separator = "\n\n")
        return PromptParts(
            prompt = prompt,
            systemPrompt = system,
            userPrompt = user,
            assistantPrimer = assistant,
            truncatedOcrForPrompt = ocrExcerpt
        )
    }

    private fun buildSystemPrompt(schema: Schema, v2Enabled: Boolean): String {
        val requiredFields = schema.getRequiredFields().joinToString { it.name }
        val optionalFields = schema.getOptionalFields().joinToString { it.name }
        return buildString {
            appendLine("<|im_start|>system")
            appendLine("You return exactly one compact JSON object and nothing else.")
            appendLine("Required keys: $requiredFields")
            val effectiveOptional = if (v2Enabled) {
                listOfNotNull(
                    optionalFields.takeIf { it.isNotBlank() },
                    "category, storeUrl, paymentMethod, minimumPurchase, maximumDiscount, " +
                        "redeemCodes, primaryRedeemCode, offerType"
                ).joinToString(", ")
            } else {
                optionalFields
            }
            if (effectiveOptional.isNotBlank()) {
                appendLine("Optional keys (use the string \"unknown\" when unavailable): $effectiveOptional")
            }
            val extraKeysClause = if (v2Enabled)
                "no markdown, no comments, preserve coupon text verbatim"
            else
                "no markdown, no comments, no extra keys, preserve coupon text verbatim"
            appendLine("Rules: $extraKeysClause.")
            appendLine("All string values must be trimmed and non-empty. Never output null, \"null\", \"NULL\", \"N/A\", or empty strings.")
            appendLine("If a field is truly missing, output the literal string \"unknown\".")
            appendLine("storeName must be the merchant or brand highlighted in the coupon.")
            appendLine("redeemCode must match the coupon code text exactly, using uppercase letters/numbers only with no spaces or CTA text like COPY.")
            appendLine("expiryDate must repeat the date format found in the coupon (for example, \"31 May, 2025\").")
            appendLine("Keep storeNameEvidence to between zero and three short snippets copied from the OCR text.")
            append("<|im_end|>")
        }
    }

    private fun buildUserPrompt(truncatedOcr: String, ocr: PromptOcrSnapshot): String {
        val metrics = String.format(
            Locale.US,
            "metrics: avg_conf=%.2f unknown_rate=%.2f",
            ocr.averageConfidence,
            ocr.unknownGlyphRate
        )
        return buildString {
            appendLine("<|im_start|>user")
            appendLine("Structure the OCR excerpt into JSON (quality $metrics). Respond with JSON only.")
            appendLine("Prioritise extracting merchant/store name, coupon description, redeem code, and expiry date from the text below.")
            appendLine("If any of those are missing in the text, write \"unknown\" but do not invent new information.")
            appendLine("Ensure the redeemCode contains only the actual coupon code characters (no words like COPY or APPLY).")
            appendLine("OCR excerpt:")
            appendLine(truncatedOcr)
            append("<|im_end|>")
        }
    }

    private fun buildOcrExcerpt(ocr: PromptOcrSnapshot): String {
        val prioritized = LinkedHashSet<String>()
        val merged = ocr.mergedLines.mapNotNull { sanitizeLineForPrompt(it) }
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

        val fallbackSnippet = PromptGenerator.sanitizeOcrSnippet(ocr.normalizedText, MAX_OCR_CHARS)
        val sanitizedFallback = fallbackSnippet
            .lines()
            .mapNotNull { sanitizeLineForPrompt(it) }
            .joinToString(separator = "\n")
        return sanitizedFallback.ifBlank { fallbackSnippet.trim() }
    }

    private fun sanitizeLineForPrompt(rawLine: String): String? {
        var line = rawLine.trim()
        if (line.isEmpty()) return null
        if (CTA_LINE_PATTERNS.any { it.containsMatchIn(line) }) return null

        var changed: Boolean
        do {
            changed = false
            for (suffix in CTA_SUFFIXES) {
                val updated = line.removeCaseInsensitiveSuffix(" $suffix")
                if (updated.length != line.length) {
                    line = updated
                    changed = true
                } else {
                    val alt = line.removeCaseInsensitiveSuffix(suffix)
                    if (alt.length != line.length) {
                        line = alt
                        changed = true
                    }
                }
            }
        } while (changed)

        val collapsed = line.replace("\\s+".toRegex(), " ").trim()
        return collapsed.takeIf { it.isNotBlank() }
    }
}

private fun String.removeCaseInsensitiveSuffix(suffix: String): String {
    if (suffix.isBlank() || this.length < suffix.length) return this
    val ending = this.substring(this.length - suffix.length)
    return if (ending.equals(suffix, ignoreCase = true)) {
        this.substring(0, this.length - suffix.length).trimEnd()
    } else {
        this
    }
}

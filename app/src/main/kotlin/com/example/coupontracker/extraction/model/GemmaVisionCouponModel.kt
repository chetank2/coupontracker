package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.gemma.GemmaVisionRuntime
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter over the Gemma 3 Vision MediaPipe path.
 * Composition: GemmaVisionRuntime.runVisionInference(...) → CouponJsonContract.enforce(...).
 */
class GemmaVisionCouponModel @Inject constructor(
    private val runtime: GemmaVisionRuntime
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_GEMMA

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        throw NotImplementedError(
            "GemmaVisionCouponModel is vision-only; use GemmaTextCouponModel for text."
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        lateinit var raw: String
        val latency = measureTimeMillis {
            val multimodalPrompt = buildMultimodalPrompt(prompt, ocrText)
            Log.d(TAG, "Running Gemma Vision with compact prompt chars=${multimodalPrompt.length}")
            raw = runtime.runVisionInferenceOrThrow(
                bitmap = image,
                prompt = multimodalPrompt
            )
        }
        val rawResponse = raw.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Gemma Vision returned an empty response.")
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
            latencyMs = latency,
            usedFallback = false
        )
    }

    companion object {
        internal fun buildMultimodalPrompt(basePrompt: String, ocrText: String?): String = buildString {
            append(basePrompt)
            compactOcrAnchors(ocrText).takeIf { it.isNotBlank() }?.let { anchors ->
                append("\nOCR:")
                append(anchors)
            }
        }

        internal fun compactOcrAnchors(ocrText: String?): String {
            if (ocrText.isNullOrBlank()) return ""
            val normalized = ocrText
                .replace(Regex("""[ \t]+"""), " ")
                .lines()
                .map { it.trim().trim('|', '-', '•') }
                .filter { it.length >= MIN_ANCHOR_LENGTH }
                .filterNot(::isUiOrChromeNoise)

            val relevant = normalized.filter(::isCouponRelevantAnchor)
            val selected = (relevant.ifEmpty { normalized })
                .distinctBy { it.lowercase() }
                .take(MAX_ANCHOR_LINES)

            return selected
                .joinToString(separator = "\n", prefix = "\n")
                .take(MAX_ANCHOR_CHARS)
        }

        private fun isCouponRelevantAnchor(line: String): Boolean {
            val lower = line.lowercase()
            return COUPON_ANCHOR_KEYWORDS.any(lower::contains) ||
                COUPON_CODE_PATTERN.containsMatchIn(line) ||
                MONEY_OR_PERCENT_PATTERN.containsMatchIn(line)
        }

        private fun isUiOrChromeNoise(line: String): Boolean {
            val lower = line.lowercase()
            if (lower in UI_NOISE_WORDS) return true
            if (STATUS_BAR_PATTERN.matches(line)) return true
            if (RATING_ONLY_PATTERN.matches(line)) return true
            return UI_NOISE_WORDS.any { noise ->
                lower == noise || lower.startsWith("$noise ") || lower.endsWith(" $noise")
            }
        }

        private const val TAG = "GemmaVisionCouponModel"
        private const val MIN_ANCHOR_LENGTH = 3
        private const val MAX_ANCHOR_LINES = 8
        private const val MAX_ANCHOR_CHARS = 360
        private val COUPON_ANCHOR_KEYWORDS = listOf(
            "you won",
            "cashback",
            "off",
            "code",
            "coupon",
            "expires",
            "valid",
            "minimum",
            "min ",
            "save",
            "flat"
        )
        private val UI_NOISE_WORDS = setOf(
            "details",
            "redeem",
            "redeem now",
            "copy",
            "rate this voucher",
            "vouchers",
            "active",
            "lifetime",
            "lte",
            "volte",
            "5g",
            "4g"
        )
        private val STATUS_BAR_PATTERN = Regex("""(?i)^\s*(?:\d{1,2}:\d{2}|[45]g|lte|volte|wi-?fi|wifi|\d{1,3}%|[a-z]{1,2})\s*$""")
        private val RATING_ONLY_PATTERN = Regex("""^\s*[★☆]?\s*\d(?:\.\d{1,2})?\s*$""")
        private val COUPON_CODE_PATTERN = Regex("""\b(?=[A-Z0-9-]*\d)[A-Z0-9][A-Z0-9-]{5,39}\b""")
        private val MONEY_OR_PERCENT_PATTERN = Regex("""(?:₹|rs\.?|inr|\d+\s*%)""", RegexOption.IGNORE_CASE)
    }
}

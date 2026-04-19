package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
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
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = runtime.runVisionInference(
                bitmap = image,
                prompt = buildMultimodalPrompt(prompt, ocrText)
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException(
                "GemmaVisionRuntime returned null (vision bundle missing or inference failed)"
            )
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
            latencyMs = latency,
            usedFallback = false
        )
    }

    private fun buildMultimodalPrompt(basePrompt: String, ocrText: String?): String = buildString {
        append(basePrompt)
        if (!ocrText.isNullOrBlank()) {
            append("\n\nOCR anchors (copy codes exactly from here):\n")
            append(ocrText)
        }
    }
}

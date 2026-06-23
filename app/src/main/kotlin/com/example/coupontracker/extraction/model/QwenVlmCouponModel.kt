package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class QwenVlmCouponModel @Inject constructor(
    private val llmRuntime: LlmRuntimeManager
) : CouponExtractionModel, RawVisionExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_QWEN

    override suspend fun extractFromText(
        ocrText: String, prompt: String, grammar: String?
    ): ModelExtractionResult {
        throw UnsupportedOperationException("QwenVlmCouponModel is vision-only; use QwenTextCouponModel for text.")
    }

    override suspend fun extractFromImage(
        image: Bitmap, ocrText: String?, prompt: String
    ): ModelExtractionResult {
        val rawResult = extractRawFromImage(image, ocrText, prompt)
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResult.canonicalJson),
            latencyMs = rawResult.latencyMs,
            usedFallback = false
        )
    }

    override suspend fun extractRawFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        lateinit var raw: String
        val latency = measureTimeMillis {
            raw = llmRuntime.runInference(
                bitmap = image,
                prompt = buildMultimodalPrompt(prompt, ocrText)
            ) ?: throw IllegalStateException("runInference returned null")
        }
        return ModelExtractionResult(
            canonicalJson = raw,
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

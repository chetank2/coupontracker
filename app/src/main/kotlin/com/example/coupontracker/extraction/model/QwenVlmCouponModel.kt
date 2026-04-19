package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import javax.inject.Inject
import kotlin.system.measureTimeMillis

class QwenVlmCouponModel @Inject constructor(
    private val llmRuntime: LlmRuntimeManager
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_QWEN

    override suspend fun extractFromText(
        ocrText: String, prompt: String, grammar: String?
    ): ModelExtractionResult {
        throw NotImplementedError("QwenVlmCouponModel is vision-only; use QwenTextCouponModel for text.")
    }

    override suspend fun extractFromImage(
        image: Bitmap, ocrText: String?, prompt: String
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = llmRuntime.runInference(
                bitmap = image,
                prompt = buildMultimodalPrompt(prompt, ocrText)
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("runInference returned null")
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

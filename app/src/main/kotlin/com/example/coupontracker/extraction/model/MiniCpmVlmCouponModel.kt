package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter for the MiniCPM vision path. The legacy
 * MiniCPM weights flow through the same native bridge as the Qwen VLM path
 * (`LlmRuntimeManager.runInference`); which weights are actually loaded is
 * decided by the runtime's currently-installed model assets, not by the
 * adapter. Selecting `VLM_MINICPM` therefore presumes the MiniCPM bundle is
 * the active loaded model.
 */
class MiniCpmVlmCouponModel @Inject constructor(
    private val llmRuntime: LlmRuntimeManager
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.VLM_MINICPM

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        throw UnsupportedOperationException("MiniCpmVlmCouponModel is vision-only.")
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = llmRuntime.runInference(
                bitmap = image,
                prompt = buildMultimodalPrompt(prompt, ocrText)
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("MiniCPM runInference returned null")
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

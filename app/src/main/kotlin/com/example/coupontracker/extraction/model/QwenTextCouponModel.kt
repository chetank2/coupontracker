package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.LlmRuntimeManager
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter over the existing Qwen text-inference path.
 * Text-only in this plan; vision support lands with the VLM retry plan.
 *
 * Composition rather than subclassing:
 *   LlmRuntimeManager.runTextInference(...)   // raw LLM output
 *   → CouponJsonContract.enforce(...)         // strip + alias remap
 *
 * Keeps LocalLlmOcrService untouched — benchmarks that want the raw
 * Qwen text path do not have to drag in OCR orchestration.
 */
class QwenTextCouponModel @Inject constructor(
    private val llmRuntime: LlmRuntimeManager
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.TEXT_QWEN

    /**
     * `grammar` is intentionally ignored here: the MLC runtime applies the
     * coupon grammar at init-time (loaded from bundled assets by
     * `LlmRuntimeManager`), not per inference call. Adapters for backends
     * that accept per-call grammar should thread the parameter through.
     */
    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = llmRuntime.runTextInference(
                ocrText = ocrText,
                prompt = prompt,
                keepLoaded = false,
                maxTokensOverride = null
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("runTextInference returned null")
        val canonical = CouponJsonContract.enforce(rawResponse)
        return ModelExtractionResult(
            canonicalJson = canonical,
            latencyMs = latency,
            usedFallback = false
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        throw UnsupportedOperationException(
            "QwenTextCouponModel does not support vision. See Plan 5 (VLM retry)."
        )
    }
}

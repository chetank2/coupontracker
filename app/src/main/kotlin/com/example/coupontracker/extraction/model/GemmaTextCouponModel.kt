package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.llm.gemma.GemmaRuntime
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * CouponExtractionModel adapter over the Gemma text inference path.
 * Composition: GemmaRuntime.runTextInference(...) → CouponJsonContract.enforce(...).
 */
class GemmaTextCouponModel @Inject constructor(
    private val runtime: GemmaRuntime
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.TEXT_GEMMA

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        var raw: String? = null
        val latency = measureTimeMillis {
            raw = runtime.runTextInference(
                ocrText = ocrText,
                prompt = prompt,
                maxTokensOverride = null
            )
        }
        val rawResponse = raw
            ?: throw IllegalStateException("GemmaRuntime returned null (model missing or inference failed)")
        return ModelExtractionResult(
            canonicalJson = CouponJsonContract.enforce(rawResponse),
            latencyMs = latency,
            usedFallback = false
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        throw NotImplementedError(
            "GemmaTextCouponModel does not support vision. See Plan 5 (VLM retry)."
        )
    }
}

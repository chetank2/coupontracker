package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.RawVisionExtractionModel

class VlmCouponLayoutDetector(
    private val modelSelector: ModelSelector,
    private val parser: CouponLayoutJsonParser = CouponLayoutJsonParser()
) : CouponLayoutDetector {

    override val name: String = "vlm_layout"

    override suspend fun detectLayout(
        bitmap: Bitmap,
        context: LayoutDetectionContext
    ): CouponLayoutDetection {
        val failures = mutableListOf<String>()
        val adapter = runCatching { modelSelector.select(ModelRole.LOW_CONFIDENCE_RETRY) }
            .onFailure { failures += "retry_slot_unavailable" }
            .getOrNull()

        if (adapter == null || adapter.mode.name !in VLM_MODE_NAMES) {
            return CouponLayoutDetection(
                cards = emptyList(),
                source = LayoutDetectionSource.VLM,
                confidence = 0f,
                diagnostics = LayoutDiagnostics(
                    detectorName = name,
                    rejectedReasons = failures + "retry_mode_not_vision"
                )
            )
        }

        val rawAdapter = adapter as? RawVisionExtractionModel
        if (rawAdapter == null) {
            return CouponLayoutDetection(
                cards = emptyList(),
                source = LayoutDetectionSource.VLM,
                confidence = 0f,
                diagnostics = LayoutDiagnostics(
                    detectorName = name,
                    rejectedReasons = failures + "${adapter.mode.name}:raw_vision_unavailable"
                )
            )
        }

        val detection = runCatching {
                val result = rawAdapter.extractRawFromImage(
                    image = bitmap,
                    ocrText = context.ocrText.takeIf { it.isNotBlank() },
                    prompt = PROMPT
                )
                parser.parse(result.canonicalJson)
            }.onFailure { error ->
                failures += "${adapter.mode.name}:${error.javaClass.simpleName}"
                Log.w(TAG, "VLM layout mode ${adapter.mode} failed: ${error.message}")
            }.getOrNull()
        if (detection != null) {
            return detection.copy(
                source = LayoutDetectionSource.VLM,
                diagnostics = detection.diagnostics.copy(
                    detectorName = name,
                    rejectedReasons = failures
                )
            )
        }
        return CouponLayoutDetection(
            cards = emptyList(),
            source = LayoutDetectionSource.VLM,
            confidence = 0f,
            diagnostics = LayoutDiagnostics(
                detectorName = name,
                rejectedReasons = failures
            )
        )
    }

    companion object {
        private const val TAG = "VlmCouponLayoutDetector"
        private val VLM_MODE_NAMES = setOf(
            "VLM_GEMMA",
            "VLM_QWEN",
            "VLM_MINICPM"
        )
        private const val PROMPT =
            "Find visible coupon cards in this screenshot. Return JSON only with a cards array. " +
                "Each card must have box {x,y,width,height}, completeness complete|partial|too_incomplete, " +
                "confidence 0..1, visibleFields, and reason. Do not extract final coupon code, cashback, expiry, or merchant values."
    }
}

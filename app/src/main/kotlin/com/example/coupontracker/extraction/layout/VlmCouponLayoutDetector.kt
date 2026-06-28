package com.example.coupontracker.extraction.layout

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.model.RawVisionExtractionModel
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionLayoutCard

class VlmCouponLayoutDetector(
    private val modelSelector: ModelSelector,
    private val securePreferencesManager: SecurePreferencesManager? = null,
    private val parser: CouponLayoutJsonParser = CouponLayoutJsonParser(),
    private val visionParser: VisionFieldJsonParser = VisionFieldJsonParser()
) : CouponLayoutDetector {

    override val name: String = "vlm_layout"

    override suspend fun detectLayout(
        bitmap: Bitmap,
        context: LayoutDetectionContext
    ): CouponLayoutDetection {
        val failures = mutableListOf<String>()
        if (securePreferencesManager?.isGemmaVisionVerifierEnabled() == false) {
            return CouponLayoutDetection(
                cards = emptyList(),
                source = LayoutDetectionSource.VLM,
                confidence = 0f,
                diagnostics = LayoutDiagnostics(
                    detectorName = name,
                    rejectedReasons = listOf("gemma_vision_verifier_disabled")
                )
            )
        }
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
                parseLayoutResult(result.canonicalJson, bitmap)
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

    private fun parseLayoutResult(rawJson: String, bitmap: Bitmap): CouponLayoutDetection {
        val normalized = runCatching {
            val parsed = visionParser.parseLayout(rawJson)
            CouponLayoutDetection(
                cards = parsed.cards.mapIndexed { index, card -> card.toCouponCardRegion(bitmap, index) },
                source = LayoutDetectionSource.VLM,
                confidence = parsed.confidence,
                diagnostics = LayoutDiagnostics(
                    detectorName = name,
                    rawCardCount = parsed.cards.size
                )
            )
        }.getOrNull()
        if (normalized != null && normalized.cards.isNotEmpty()) return normalized

        return parser.parse(rawJson)
    }

    private fun VisionLayoutCard.toCouponCardRegion(bitmap: Bitmap, index: Int): CouponCardRegion {
        val left = (bounds.x * bitmap.width).toInt().coerceIn(0, bitmap.width)
        val top = (bounds.y * bitmap.height).toInt().coerceIn(0, bitmap.height)
        val right = (bounds.right * bitmap.width).toInt().coerceIn(left, bitmap.width)
        val bottom = (bounds.bottom * bitmap.height).toInt().coerceIn(top, bitmap.height)
        return CouponCardRegion(
            bounds = Rect(left, top, right, bottom),
            completeness = when (layoutState) {
                Coupon.LayoutState.COMPLETE,
                Coupon.LayoutState.MODAL_FOREGROUND -> CardCompleteness.COMPLETE
                Coupon.LayoutState.LOW_CONFIDENCE -> CardCompleteness.TOO_INCOMPLETE
                else -> CardCompleteness.PARTIAL
            },
            confidence = confidence,
            visibleFields = emptySet(),
            reason = "vlm_normalized_layout",
            sourceIndex = index
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
            "JSON only. Layout only. Do not return store, offer, code, or expiry. " +
            "Return exactly {\"layoutState\":\"\",\"confidence\":0,\"cards\":[{\"active\":true,\"confidence\":0,\"bounds\":{\"x\":0,\"y\":0,\"w\":0,\"h\":0}}]}. " +
            "Bounds are normalized 0..1. Pick one active foreground coupon/card/modal. " +
            "layoutState: COMPLETE, PARTIAL, MODAL_FOREGROUND, MULTI_CARD, or LOW_CONFIDENCE."
    }
}

package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.ocr.OcrEngine
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-region orchestrator. Given a list of already-cropped coupon regions,
 * runs OCR + extraction per region, dedupes by canonical (store, code, expiry),
 * caps at MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT.
 *
 * Region detection itself lives upstream (HybridCouponDetector + OCR);
 * keeping the pipeline detector-agnostic keeps it unit-testable without
 * dragging in MultiEngineOCR.
 */
@Singleton
class CouponRegionPipeline @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val modelSelector: ModelSelector
) {

    suspend fun extractFromCrops(crops: List<Bitmap>): List<JSONObject> {
        val accepted = crops
            .filter { it.width * it.height >= MultiCouponLimits.MIN_REGION_AREA_PX }
            .take(MultiCouponLimits.MAX_COUPONS_PER_SCREENSHOT)
        if (accepted.isEmpty()) return emptyList()
        val coupons = accepted.map { extractSingle(it) }
        return CouponDeduplicator.dedupe(coupons)
    }

    /** Convenience: detect-free path for the single-image case. */
    suspend fun extractWhole(bitmap: Bitmap): List<JSONObject> {
        return listOf(extractSingle(bitmap))
    }

    private suspend fun extractSingle(crop: Bitmap): JSONObject {
        val ocrText = ocrEngine.recognize(crop)
        val adapter = modelSelector.select(ModelRole.DEFAULT)
        val result = adapter.extractFromText(
            ocrText = ocrText,
            prompt = DEFAULT_PROMPT,
            grammar = null
        )
        return JSONObject(result.canonicalJson)
    }

    companion object {
        // Deliberately a thin stand-in. PromptBuilder is the production source
        // of truth and should be threaded through here once that dependency
        // graph is reachable from this entry point.
        private const val DEFAULT_PROMPT =
            "Extract the coupon fields as canonical JSON using the provided OCR text."
    }
}

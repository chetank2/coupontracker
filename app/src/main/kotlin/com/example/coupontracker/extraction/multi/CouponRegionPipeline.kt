package com.example.coupontracker.extraction.multi

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.ModelMode
import com.example.coupontracker.extraction.model.ModelRole
import com.example.coupontracker.extraction.model.ModelSelector
import com.example.coupontracker.extraction.validation.CouponFieldBundleValidator
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.llm.CouponSchemaKeys
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
    private val bundleValidator = CouponFieldBundleValidator()

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
        val ocrBlocks = runCatching { ocrEngine.recognizeWithBoxes(crop) }
            .getOrDefault(emptyList())
            .map { span ->
                TextBlock(
                    text = span.text,
                    bounds = RectF(span.boundingBox),
                    confidence = span.confidence
                )
            }
        val json = runVlmFirst(crop, ocrText)
            ?: runTextFallback(ocrText)
        return validateCanonicalJson(json, ocrText, ocrBlocks, crop.height)
    }

    private suspend fun runVlmFirst(crop: Bitmap, ocrText: String): JSONObject? {
        for (mode in VLM_PRIORITY) {
            val adapter = modelSelector.selectMode(mode) ?: continue
            val canonical = runCatching {
                val result = adapter.extractFromImage(
                    image = crop,
                    ocrText = ocrText,
                    prompt = VLM_PROMPT
                )
                JSONObject(result.canonicalJson)
            }.onFailure { error ->
                Log.w(TAG, "VLM mode $mode failed for crop; falling back if possible: ${error.message}")
            }.getOrNull()
            if (canonical != null) {
                return canonical
            }
        }
        return null
    }

    private suspend fun runTextFallback(ocrText: String): JSONObject {
        val adapter = modelSelector.selectText(ModelRole.DEFAULT)
        val result = adapter.extractFromText(
            ocrText = ocrText,
            prompt = TEXT_FALLBACK_PROMPT,
            grammar = null
        )
        return JSONObject(result.canonicalJson)
    }

    private fun validateCanonicalJson(
        canonical: JSONObject,
        ocrText: String,
        ocrBlocks: List<TextBlock>,
        imageHeight: Int
    ): JSONObject {
        val fields = buildFieldCandidates(canonical)
        val validation = bundleValidator.validate(
            bundle = FieldValueBundle(
                storeName = canonical.optString(CouponSchemaKeys.STORE_NAME).takeIf { it.isNotBlank() },
                description = canonical.optString(CouponSchemaKeys.DESCRIPTION).takeIf { it.isNotBlank() },
                redeemCode = canonical.optString(CouponSchemaKeys.REDEEM_CODE).takeIf { it.isNotBlank() },
                expiryDateText = canonical.optString(CouponSchemaKeys.EXPIRY_DATE).takeIf { it.isNotBlank() }
            ),
            fields = fields,
            rawOcrText = ocrText,
            ocrBlocks = ocrBlocks,
            imageHeight = imageHeight
        )
        if (validation.needsAttention) {
            Log.w(TAG, "Region pipeline final validation needs review: ${validation.reason}")
            canonical.put(CouponSchemaKeys.NEEDS_ATTENTION, true)
        }
        return canonical
    }

    private fun buildFieldCandidates(canonical: JSONObject): Map<FieldType, FieldCandidate> {
        return buildMap {
            putIfPresent(FieldType.STORE_NAME, canonical, CouponSchemaKeys.STORE_NAME)
            putIfPresent(FieldType.DESCRIPTION, canonical, CouponSchemaKeys.DESCRIPTION)
            putIfPresent(FieldType.COUPON_CODE, canonical, CouponSchemaKeys.REDEEM_CODE)
            putIfPresent(FieldType.EXPIRY_DATE, canonical, CouponSchemaKeys.EXPIRY_DATE)
        }
    }

    private fun MutableMap<FieldType, FieldCandidate>.putIfPresent(
        fieldType: FieldType,
        canonical: JSONObject,
        key: String
    ) {
        val value = canonical.optString(key).trim()
        if (value.isBlank() || value.equals("unknown", ignoreCase = true)) return
        put(
            fieldType,
            FieldCandidate(
                value = value,
                confidence = 0.75f,
                source = "region_pipeline_model",
                context = "canonical_json"
            )
        )
    }

    companion object {
        private const val TAG = "CouponRegionPipeline"
        private val VLM_PRIORITY = listOf(
            ModelMode.VLM_GEMMA,
            ModelMode.VLM_QWEN,
            ModelMode.VLM_MINICPM
        )
        private const val VLM_PROMPT =
            "Read this single cropped coupon region and return canonical coupon JSON. " +
                "Use only fields visible inside this crop. Do not infer from outside context."
        private const val TEXT_FALLBACK_PROMPT =
            "Extract the coupon fields as canonical JSON using the provided OCR text."
    }
}

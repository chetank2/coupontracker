package com.example.coupontracker.worker

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.model.GemmaVisionCouponModel
import com.example.coupontracker.extraction.rules.TextExtractor
import com.example.coupontracker.extraction.vision.VisionEvidenceMergePolicy
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionFieldMergeInput
import com.example.coupontracker.extraction.vision.VisionLayoutCard
import com.example.coupontracker.extraction.vision.VisionVerificationConfig
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.util.GenericFieldHeuristics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

internal data class VisionInput(
    val bitmap: Bitmap,
    val usedTargetedCrop: Boolean,
    val source: String,
    val normalizedBoundsJson: String?,
    val pixelCrop: Rect?,
    val layoutState: String?,
    val debugEvidence: String?
) {
    fun toMergeInput(): VisionFieldMergeInput {
        return VisionFieldMergeInput(
            usedTargetedCrop = usedTargetedCrop,
            source = source,
            normalizedBoundsJson = normalizedBoundsJson,
            pixelCrop = pixelCrop,
            layoutState = layoutState,
            debugEvidence = debugEvidence
        )
    }
}

internal class VisionCropPreparer(
    private val ocrEngine: OcrEngine,
    private val gemmaVisionCouponModel: GemmaVisionCouponModel,
    private val visionFieldJsonParser: VisionFieldJsonParser,
    private val visionEvidenceMergePolicy: VisionEvidenceMergePolicy,
    private val textExtractor: TextExtractor
) {
    suspend fun prepareTwoPassVisionInput(source: Bitmap, coupon: Coupon, rawOcr: String): VisionInput? {
        Log.i(TAG, "GEMMA_LAYOUT_STARTED image=${source.width}x${source.height}")
        var layoutFailureEvidence: String? = null
        var layoutRawJson: String? = null
        val layoutCrop = runCatching {
            val result = withTimeout(VisionVerificationConfig.LAYOUT_TIMEOUT_MS) {
                gemmaVisionCouponModel.extractRawFromImage(
                    image = source,
                    ocrText = null,
                    prompt = VisionVerificationPrompts.layout()
                )
            }
            layoutRawJson = result.canonicalJson
            val detection = visionFieldJsonParser.parseLayout(result.canonicalJson)
            val selected = detection.activeCard
            Log.i(
                TAG,
                "GEMMA_LAYOUT_PARSED cards=${detection.cards.size} confidence=${"%.2f".format(detection.confidence)} " +
                    "selectedConfidence=${"%.2f".format(selected?.confidence ?: 0f)} bounds=${selected?.bounds}"
            )
            if (
                selected == null ||
                detection.confidence < VisionVerificationConfig.MIN_LAYOUT_CONFIDENCE ||
                selected.confidence < VisionVerificationConfig.MIN_LAYOUT_CONFIDENCE
            ) {
                Log.w(TAG, "GEMMA_LAYOUT_REJECTED reason=low_confidence")
                null
            } else {
                cropBitmapToLayoutCard(source, selected)
            }
        }.onFailure { error ->
            Log.w(TAG, "GEMMA_LAYOUT_REJECTED reason=${error.javaClass.simpleName} message=${error.message}")
            layoutFailureEvidence = visionEvidenceMergePolicy.buildFailureEvidence(
                stage = "layout_parse_failed",
                visionInput = null,
                error = error,
                rawVisionJson = layoutRawJson
            )
        }.getOrNull()
        if (layoutCrop != null) return layoutCrop

        return prepareOcrTargetedVisionBitmap(source, coupon, rawOcr, layoutFailureEvidence)
    }

    private fun cropBitmapToLayoutCard(source: Bitmap, card: VisionLayoutCard): VisionInput? {
        val pixelCrop = card.bounds.toPixelRect(source, VisionVerificationConfig.LAYOUT_CROP_PADDING_RATIO)
        val widthRatio = pixelCrop.width().toFloat() / source.width.toFloat()
        val heightRatio = pixelCrop.height().toFloat() / source.height.toFloat()
        val areaRatio = (pixelCrop.width().toFloat() * pixelCrop.height().toFloat()) /
            (source.width.toFloat() * source.height.toFloat())
        if (widthRatio >= VisionVerificationConfig.MAX_LAYOUT_CROP_WIDTH_RATIO ||
            heightRatio >= VisionVerificationConfig.MAX_LAYOUT_CROP_HEIGHT_RATIO ||
            areaRatio >= VisionVerificationConfig.MAX_LAYOUT_CROP_AREA_RATIO
        ) {
            Log.w(
                TAG,
                "GEMMA_LAYOUT_REJECTED reason=crop_too_large pixelCrop=${pixelCrop.flattenToString()} " +
                    "widthRatio=${"%.2f".format(widthRatio)} heightRatio=${"%.2f".format(heightRatio)} " +
                    "areaRatio=${"%.2f".format(areaRatio)}"
            )
            return null
        }
        Log.i(
            TAG,
            "GEMMA_LAYOUT_CROP_SELECTED normalizedBounds=${card.bounds} pixelCrop=${pixelCrop.flattenToString()} " +
                "layoutState=${card.layoutState} confidence=${"%.2f".format(card.confidence)}"
        )
        val crop = Bitmap.createBitmap(source, pixelCrop.left, pixelCrop.top, pixelCrop.width(), pixelCrop.height())
        Log.i(TAG, "GEMMA_LAYOUT_CROP_READY source=${source.width}x${source.height} crop=${crop.width}x${crop.height}")
        return VisionInput(
            bitmap = crop,
            usedTargetedCrop = true,
            source = "layout",
            normalizedBoundsJson = buildNormalizedBoundsJson(card).toString(),
            pixelCrop = pixelCrop,
            layoutState = card.layoutState,
            debugEvidence = null
        )
    }

    private fun buildNormalizedBoundsJson(card: VisionLayoutCard): JSONObject {
        return JSONObject()
            .put("x", card.bounds.x.toDouble())
            .put("y", card.bounds.y.toDouble())
            .put("w", card.bounds.w.toDouble())
            .put("h", card.bounds.h.toDouble())
    }

    private suspend fun prepareOcrTargetedVisionBitmap(
        source: Bitmap,
        coupon: Coupon,
        rawOcr: String,
        debugEvidence: String?
    ): VisionInput? {
        val crop = runCatching { cropBitmapToCouponEvidence(source, coupon, rawOcr) }
            .onFailure { Log.w(TAG, "Could not create target crop for Gemma Vision: ${it.message}") }
            .getOrNull()
        if (crop == null) {
            Log.w(TAG, "Gemma Vision rejected original bitmap fallback because no crop was isolated")
            return null
        }
        Log.d(
            TAG,
            "Gemma Vision using OCR-targeted crop ${source.width}x${source.height} -> " +
                "${crop.bitmap.width}x${crop.bitmap.height} pixelCrop=${crop.rect.flattenToString()}"
        )
        return VisionInput(
            bitmap = crop.bitmap,
            usedTargetedCrop = true,
            source = "ocr_targeted_fallback",
            normalizedBoundsJson = null,
            pixelCrop = crop.rect,
            layoutState = null,
            debugEvidence = debugEvidence
        )
    }

    private data class TargetedCrop(
        val bitmap: Bitmap,
        val rect: Rect
    )

    private suspend fun cropBitmapToCouponEvidence(source: Bitmap, coupon: Coupon, rawOcr: String): TargetedCrop? {
        val spans = withContext(Dispatchers.IO) { ocrEngine.recognizeWithBoxes(source) }
            .filter { it.text.isNotBlank() && !it.boundingBox.isEmpty }
        if (spans.isEmpty()) return null

        val anchorSpans = spans.filter { span -> isCouponAnchorSpan(span, coupon, rawOcr) }
        val exactCodeAnchors = coupon.redeemCode
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.let { code -> anchorSpans.count { normalizeEvidence(it.text).contains(normalizeEvidence(code)) } }
            ?: 0
        if (anchorSpans.size < VisionVerificationConfig.MIN_OCR_CROP_ANCHORS && exactCodeAnchors == 0) return null

        val minAnchorY = anchorSpans.minOf { it.boundingBox.top }
        val maxAnchorY = anchorSpans.maxOf { it.boundingBox.bottom }
        val verticalPadding = (source.height * VisionVerificationConfig.OCR_CROP_VERTICAL_PADDING_RATIO).toInt()
            .coerceAtLeast(VisionVerificationConfig.MIN_OCR_CROP_PADDING_PX)
        val cropTop = (minAnchorY - verticalPadding).coerceAtLeast(0)
        val initialCropBottom = (maxAnchorY + verticalPadding).coerceAtMost(source.height)
        val maxCropHeight = (source.height * VisionVerificationConfig.MAX_OCR_CROP_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val cropBottom = if (initialCropBottom - cropTop > maxCropHeight) {
            (cropTop + maxCropHeight).coerceAtMost(source.height)
        } else {
            initialCropBottom
        }
        val cropHeight = cropBottom - cropTop
        if (cropHeight <= 0) return null

        val horizontalBounds = spans
            .filter { centerY(it.boundingBox) in cropTop..cropBottom }
            .map { it.boundingBox }
        val cropLeft = (horizontalBounds.minOfOrNull { it.left } ?: 0)
            .minus((source.width * VisionVerificationConfig.OCR_CROP_HORIZONTAL_PADDING_RATIO).toInt())
            .coerceAtLeast(0)
        val cropRight = (horizontalBounds.maxOfOrNull { it.right } ?: source.width)
            .plus((source.width * VisionVerificationConfig.OCR_CROP_HORIZONTAL_PADDING_RATIO).toInt())
            .coerceAtMost(source.width)
        val cropWidth = cropRight - cropLeft
        if (cropWidth <= 0) return null

        val rect = Rect(cropLeft, cropTop, cropRight, cropBottom)
        return TargetedCrop(
            bitmap = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height()),
            rect = rect
        )
    }

    private fun isCouponAnchorSpan(span: OcrTextSpan, coupon: Coupon, rawOcr: String): Boolean {
        val text = normalizeEvidence(span.text)
        if (text.isBlank()) return false

        coupon.redeemCode
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.let { code ->
                if (text.contains(normalizeEvidence(code))) return true
            }

        val storeTokens = evidenceTokens(coupon.storeName)
        if (storeTokens.any { token -> text.contains(token) }) return true

        val descriptionTokens = evidenceTokens(coupon.description)
            .filterNot { it in GENERIC_DESCRIPTION_ANCHORS }
        if (descriptionTokens.any { token -> text.contains(token) }) return true

        val scopedOcr = textExtractor.extractCouponBlockForStore(rawOcr, coupon.storeName).orEmpty()
        val scopedTokens = evidenceTokens(scopedOcr)
        return scopedTokens.take(MAX_SCOPED_ANCHOR_TOKENS).any { token -> text.contains(token) }
    }

    private fun evidenceTokens(value: String?): List<String> {
        return normalizeEvidence(value.orEmpty())
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= MIN_EVIDENCE_TOKEN_LENGTH }
            .filterNot { it in GENERIC_DESCRIPTION_ANCHORS }
            .distinct()
    }

    private fun normalizeEvidence(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun centerY(rect: Rect): Int = (rect.top + rect.bottom) / 2

    private companion object {
        private const val TAG = "VisionCropPreparer"
        private const val MIN_EVIDENCE_TOKEN_LENGTH = 4
        private const val MAX_SCOPED_ANCHOR_TOKENS = 18
        private val GENERIC_DESCRIPTION_ANCHORS = setOf(
            "coupon",
            "offer",
            "details",
            "redeem",
            "cashback",
            "valid",
            "expires",
            "prime",
            "more",
            "with"
        )
    }
}

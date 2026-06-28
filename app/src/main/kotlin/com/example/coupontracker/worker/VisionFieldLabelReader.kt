package com.example.coupontracker.worker

import android.util.Log
import com.example.coupontracker.extraction.model.GemmaVisionCouponModel
import com.example.coupontracker.extraction.vision.VisionFieldExtraction
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionVerificationConfig
import com.example.coupontracker.ocr.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class VisionFieldLabelReader(
    private val ocrEngine: OcrEngine,
    private val gemmaVisionCouponModel: GemmaVisionCouponModel,
    private val visionFieldJsonParser: VisionFieldJsonParser
) {
    suspend fun read(couponId: Long, visionInput: VisionInput): VisionFieldLabelReadResult {
        val cropOcrText = withContext(Dispatchers.IO) {
            ocrEngine.recognize(visionInput.bitmap)
        }
        Log.i(
            TAG,
            "CROP_OCR_DONE couponId=$couponId source=${visionInput.source} " +
                "textLength=${cropOcrText.length} pixelCrop=${visionInput.pixelCrop?.flattenToString()}"
        )
        // Do not fall back to full-screen OCR here: it can prove fields from
        // background cards for a foreground crop.
        val cropEvidenceText = cropOcrText.takeIf { it.isNotBlank() }
        val visionResult = withTimeout(VisionVerificationConfig.FIELD_LABEL_TIMEOUT_MS) {
            gemmaVisionCouponModel.extractRawFromImage(
                image = visionInput.bitmap,
                ocrText = cropEvidenceText,
                prompt = VisionVerificationPrompts.fieldLabels()
            )
        }
        return runCatching {
            visionFieldJsonParser.parse(visionResult.canonicalJson)
        }.fold(
            onSuccess = { fieldLabels ->
                Log.i(
                    TAG,
                    "GEMMA_FIELD_LABEL_PARSED couponId=$couponId source=${visionInput.source} cards=${fieldLabels.cards.size} " +
                        "confidence=${"%.2f".format(fieldLabels.confidence)} " +
                        "activeCode=${fieldLabels.activeCard?.codeState} activeExpiry=${fieldLabels.activeCard?.expiryState}"
                )
                VisionFieldLabelReadResult.Success(
                    fieldLabels = fieldLabels,
                    rawOcr = cropEvidenceText
                )
            },
            onFailure = { error ->
                VisionFieldLabelReadResult.Failure(
                    error = error,
                    stage = "field_label_parse_failed",
                    rawVisionJson = visionResult.canonicalJson,
                    rawOcr = cropEvidenceText
                )
            }
        )
    }

    private companion object {
        private const val TAG = "VisionFieldLabelReader"
    }
}

internal sealed interface VisionFieldLabelReadResult {
    data class Success(
        val fieldLabels: VisionFieldExtraction,
        val rawOcr: String?
    ) : VisionFieldLabelReadResult

    data class Failure(
        val error: Throwable,
        val stage: String,
        val rawVisionJson: String?,
        val rawOcr: String?
    ) : VisionFieldLabelReadResult
}

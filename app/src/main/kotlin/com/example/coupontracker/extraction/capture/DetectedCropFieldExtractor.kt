package com.example.coupontracker.extraction.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.debug.ExtractionDebugScorer
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.rules.CouponInfo
import com.example.coupontracker.extraction.rules.TextExtractor
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.ExtractionTelemetryService
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.RunPath
import com.example.coupontracker.util.CouponCardOcrNormalizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class FieldExtractionResult(
    val fields: Map<String, String>,
    val llmStatus: LlmProgress,
    val runPath: RunPath? = null,
    val debugSnapshot: ExtractionDebugSnapshot? = null,
    val qualityScore: Int? = null,
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val sourceStage: ExtractionStage? = null,
    val fullOcrText: String? = null,
    val ocrBlocks: List<TextBlock> = emptyList(),
    val imageHeight: Int = 0
)

enum class LlmProgress {
    SUCCESS,
    NEEDS_REVIEW,
    FALLBACK;

    fun displayName(): String {
        val lower = name.lowercase(Locale.getDefault()).replace('_', ' ')
        return lower.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(Locale.getDefault()) else char.toString()
        }
    }
}

@Singleton
class DetectedCropFieldExtractor @Inject constructor(
    @ApplicationContext context: Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,
    private val telemetryService: ExtractionTelemetryService
) {
    private val multiEngineOCR: MultiEngineOCR = MultiEngineOCR(context, ocrEngine).apply {
        setNetworkAvailability(true)
    }
    private val textExtractor = TextExtractor()
    private val fieldHeuristics: GenericFieldHeuristics = GenericFieldHeuristics

    suspend fun extractTextFromFields(
        couponInstance: CouponInstance,
        captureTimestamp: Date?
    ): FieldExtractionResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            val extractedInfo = mutableMapOf<String, String>()
            val progress = LlmProgress.FALLBACK
            val triedStages = mutableListOf<String>()
            var finalStage = "OCR"
            var qualityScore: Int? = null
            var fieldConfidences: Map<String, Float> = emptyMap()
            val sourceStage: ExtractionStage? = ExtractionStage.MLKIT
            var fullOcrText: String? = null
            var ocrBlocks: List<TextBlock> = emptyList()
            var imageHeight = 0

            extractedInfo["minicpmConfidence"] = couponInstance.confidence.toString()
            extractedInfo["minicpmDetectionStatus"] = couponInstance.status.name

            try {
                triedStages.add("OCR")
                val fallbackResult = runFallbackOcr(couponInstance.cropBitmap, captureTimestamp)
                extractedInfo.putAll(fallbackResult.fields)
                fullOcrText = fallbackResult.text
                ocrBlocks = fallbackResult.ocrBlocks
                imageHeight = fallbackResult.imageHeight
                qualityScore = if (fallbackResult.fields.isNotEmpty()) 55 else 0
                fieldConfidences = fallbackResult.fields.keys.associateWith { 0.55f }
                Log.d(TAG, "OCR-only field extraction completed with ${fallbackResult.fields.size} fields")
            } catch (e: Exception) {
                Log.e(TAG, "OCR field extraction failed", e)
                triedStages.add("OCR_FAILED")
                finalStage = "OCR_FAILED"
                qualityScore = qualityScore ?: 0
            }

            val totalTime = System.currentTimeMillis() - startTime
            val runPath = RunPath(
                primary = "OCR",
                tried = triedStages,
                final = finalStage,
                nativeAvailable = false,
                totalTimeMs = totalTime
            )

            telemetryService.trackRunPath(runPath)

            extractedInfo["minicpmProcessing"] = progress.name
            extractedInfo["runPath"] = "${runPath.strategy} → ${runPath.final}"
            extractedInfo["processingTimeMs"] = totalTime.toString()
            qualityScore?.let { extractedInfo["qualityScore"] = it.toString() }

            val baseResult = FieldExtractionResult(
                fields = extractedInfo.toMap(),
                llmStatus = progress,
                runPath = runPath,
                qualityScore = qualityScore,
                fieldConfidences = fieldConfidences,
                sourceStage = sourceStage,
                fullOcrText = fullOcrText,
                ocrBlocks = ocrBlocks,
                imageHeight = imageHeight
            )

            val snapshot = ExtractionDebugScorer.fromFieldExtraction(baseResult, runPath)

            baseResult.copy(debugSnapshot = snapshot)
        }
    }

    private suspend fun runFallbackOcr(bitmap: Bitmap, captureTimestamp: Date?): FallbackOcrResult {
        val boxedResult = runCatching {
            val spans = ocrEngine.recognizeWithBoxes(bitmap)
            BoxedOcrResult(
                text = CouponCardOcrNormalizer.normalize(bitmap.width, bitmap.height, spans),
                blocks = ocrSpansToTextBlocks(spans),
                imageHeight = bitmap.height
            )
        }.getOrNull()

        val boxedText = boxedResult?.text
        if (!boxedText.isNullOrBlank()) {
            val couponInfo = textExtractor.extractCouponInfoSync(boxedText, captureTimestamp)
            val fields = mapCouponInfoToFields(couponInfo)
            if (fields.isNotEmpty()) {
                return FallbackOcrResult(
                    fields = fields,
                    text = boxedText,
                    ocrBlocks = boxedResult.blocks,
                    imageHeight = boxedResult.imageHeight
                )
            }
        }

        return when (val result = multiEngineOCR.processImage(bitmap)) {
            is MultiEngineOCR.OCRResult.Success -> {
                val couponInfo = textExtractor.extractCouponInfoSync(result.text, captureTimestamp)
                val fields = mapCouponInfoToFields(couponInfo).ifEmpty { result.extractedInfo }
                FallbackOcrResult(
                    fields = fields,
                    text = result.text,
                    ocrBlocks = boxedResult?.blocks.orEmpty(),
                    imageHeight = boxedResult?.imageHeight ?: 0
                )
            }
            is MultiEngineOCR.OCRResult.Error -> {
                Log.w(TAG, "Fallback OCR failed: ${result.message}")
                FallbackOcrResult(
                    fields = emptyMap(),
                    text = null,
                    ocrBlocks = boxedResult?.blocks.orEmpty(),
                    imageHeight = boxedResult?.imageHeight ?: 0
                )
            }
        }
    }

    private fun mapCouponInfoToFields(couponInfo: CouponInfo): MutableMap<String, String> {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val fields = mutableMapOf<String, String>()

        val storeName = couponInfo.storeName
        if (storeName.isNotBlank() && !fieldHeuristics.isGenericOrMissing(storeName)) {
            fields["storeName"] = storeName
        }

        val description = couponInfo.description
        if (description.isNotBlank() && !fieldHeuristics.isGenericOrMissing(description)) {
            fields["description"] = description
        }

        couponInfo.redeemCode?.takeIf { it.isNotBlank() }?.let { fields["code"] = it }

        couponInfo.expiryDate?.let { date ->
            fields["expiryDate"] = formatter.format(date)
        }

        val savingsDetail = couponInfo.cashbackDetail
            ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }
            ?: DescriptionUtils.extractCashbackLine(couponInfo.description)
                ?.takeIf { GenericFieldHeuristics.hasMeaningfulCashback(it) }

        savingsDetail?.let { fields["amount"] = it }

        couponInfo.minimumPurchase?.takeIf { !GenericFieldHeuristics.isZeroOrMeaningless(it) }?.let {
            fields["minOrderAmount"] = formatNumeric(it)
        }

        couponInfo.paymentMethod?.takeIf { it.isNotBlank() }?.let { fields["paymentMethod"] = it }
        couponInfo.platformType?.takeIf { it.isNotBlank() }?.let { fields["platformType"] = it }
        couponInfo.status?.takeIf { it.isNotBlank() }?.let { status ->
            fields["status"] = status
        }

        return fields
    }

    private fun formatNumeric(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.2f", value)
        }
    }

    private data class FallbackOcrResult(
        val fields: Map<String, String>,
        val text: String?,
        val ocrBlocks: List<TextBlock> = emptyList(),
        val imageHeight: Int = 0
    )

    private data class BoxedOcrResult(
        val text: String,
        val blocks: List<TextBlock>,
        val imageHeight: Int
    )

    private companion object {
        private const val TAG = "DetectedCropFieldExtractor"
    }
}

fun FieldExtractionResult.toDetectedCropFieldExtraction(): DetectedCropFieldExtraction {
    return DetectedCropFieldExtraction(
        fields = fields,
        runPath = runPath,
        qualityScore = qualityScore,
        fieldConfidences = fieldConfidences,
        sourceStage = sourceStage,
        fullOcrText = fullOcrText,
        ocrBlocks = ocrBlocks,
        imageHeight = imageHeight
    )
}

internal fun ocrSpansToTextBlocks(spans: List<OcrTextSpan>): List<TextBlock> {
    return spans.map { span ->
        TextBlock(
            text = span.text,
            bounds = RectF(span.boundingBox),
            confidence = span.confidence
        )
    }
}

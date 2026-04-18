package com.example.coupontracker.ocr

import android.graphics.Bitmap
import com.example.coupontracker.util.ExtractionTelemetryService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primary OcrEngine. Runs MLKit first; if any fallback predicate fires, also
 * runs Tesseract and merges the two span lists. The merged list is used as
 * the downstream OCR input; `recognize()` returns the flattened text.
 */
@Singleton
class OcrCoordinator(
    private val primary: MlKitOcrEngine,
    private val secondary: TesseractOcrEngine,
    private val predicates: List<OcrFallbackPredicate>,
    private val telemetry: ExtractionTelemetryService? = null
) : OcrEngine {

    @Inject
    constructor(
        primary: MlKitOcrEngine,
        secondary: TesseractOcrEngine,
        telemetry: ExtractionTelemetryService
    ) : this(primary, secondary, OcrFallbackPredicates.DEFAULT_CHAIN, telemetry)

    override suspend fun recognize(bitmap: Bitmap): String {
        val spans = recognizeWithBoxes(bitmap)
        return spans.joinToString("\n") { it.text }
    }

    override suspend fun recognizeWithBoxes(bitmap: Bitmap): List<OcrTextSpan> {
        val primaryText = primary.recognize(bitmap)
        val primarySpans = primary.recognizeWithBoxes(bitmap)
        val meanConfidence = if (primarySpans.isEmpty()) 0f
            else primarySpans.sumOf { it.confidence.toDouble() }.toFloat() / primarySpans.size

        val reason = predicates
            .map { it.evaluate(OcrResult(primaryText, primarySpans, meanConfidence)) }
            .firstOrNull { it != OcrFallbackReason.NONE }
            ?: OcrFallbackReason.NONE

        if (reason == OcrFallbackReason.NONE) {
            telemetry?.recordOcrFallback(reason.name, triggered = false)
            return primarySpans
        }

        telemetry?.recordOcrFallback(reason.name, triggered = true)
        val secondarySpans = runCatching { secondary.recognizeWithBoxes(bitmap) }
            .getOrElse {
                telemetry?.recordOcrFallbackFailure(reason.name, it.javaClass.simpleName)
                return primarySpans
            }
        return OcrMerger.merge(primarySpans, secondarySpans)
    }

    override fun isReady(): Boolean = primary.isReady()

    override fun release() {
        runCatching { primary.release() }
        runCatching { secondary.release() }
    }
}

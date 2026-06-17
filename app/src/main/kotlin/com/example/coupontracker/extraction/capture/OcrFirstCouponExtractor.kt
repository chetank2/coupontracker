package com.example.coupontracker.extraction.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.universal.ExtractionCandidate
import com.example.coupontracker.universal.ExtractionContext
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.StoreCandidateValidator
import com.example.coupontracker.util.normalizeExpiryDate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class OcrFirstExtractionResult(
    val coupon: Coupon,
    val rawOcrText: String,
    val confidence: Float,
    val success: Boolean,
    val failureReason: String?
)

@Singleton
class OcrFirstCouponExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrEngine: com.example.coupontracker.ocr.OcrEngine,
    private val universalExtractionService: UniversalExtractionService
) {
    private val screenshotClassifier = ScreenshotClassifier()
    private val multiEngineOCR = MultiEngineOCR(context, ocrEngine).apply {
        setNetworkAvailability(true)
    }

    suspend fun extract(
        bitmap: Bitmap,
        imageUri: String?,
        captureTimestamp: Date? = null
    ): OcrFirstExtractionResult {
        return when (val ocrResult = multiEngineOCR.processImage(bitmap)) {
            is MultiEngineOCR.OCRResult.Success -> {
                val ocrBlocks = recognizeTextBlocks(bitmap)
                extractFromOcr(
                    bitmap = bitmap,
                    ocrText = ocrResult.text,
                    ocrHints = ocrResult.extractedInfo,
                    ocrBlocks = ocrBlocks,
                    imageUri = imageUri,
                    captureTimestamp = captureTimestamp
                )
            }

            is MultiEngineOCR.OCRResult.Error -> {
                createOcrOnlyResult(
                    imageUri = imageUri,
                    ocrText = "",
                    confidence = 0f,
                    captureTimestamp = captureTimestamp,
                    failureReason = ocrResult.message
                )
            }
        }
    }

    suspend fun extractFromOcr(
        bitmap: Bitmap,
        ocrText: String,
        ocrHints: Map<String, String> = emptyMap(),
        ocrBlocks: List<TextBlock> = emptyList(),
        imageUri: String?,
        captureTimestamp: Date? = null
    ): OcrFirstExtractionResult {
        if (ocrText.isBlank()) {
            return createOcrOnlyResult(
                imageUri = imageUri,
                ocrText = "",
                confidence = 0f,
                captureTimestamp = captureTimestamp,
                failureReason = "No text found in screenshot"
            )
        }

        if (!screenshotClassifier.isLikelySingleCoupon(ocrText)) {
            return createOcrOnlyResult(
                imageUri = imageUri,
                ocrText = ocrText,
                confidence = 0f,
                captureTimestamp = captureTimestamp,
                failureReason = "Multiple coupons detected. Review one coupon at a time."
            )
        }

        val extractionContext = buildExtractionContext(ocrText, ocrHints)
        val extractionResult = universalExtractionService.extractCoupon(
            image = bitmap,
            ocrText = ocrText,
            context = extractionContext,
            ocrBlocks = ocrBlocks
        )

        if (!extractionResult.success || extractionResult.confidence <= OCR_ACCEPTANCE_CONFIDENCE) {
            return createOcrOnlyResult(
                imageUri = imageUri,
                ocrText = ocrText,
                confidence = extractionResult.confidence,
                captureTimestamp = captureTimestamp,
                failureReason = "OCR result needs review"
            )
        }

        val confidenceBreakdown = buildConfidenceMap(
            extractionResult.coupon.extractionConfidenceBreakdown,
            extractionResult.extractedFields
        )
        val baseCoupon = extractionResult.coupon.copy(
            imageUri = imageUri,
            extractionConfidenceBreakdown = confidenceBreakdown,
            cleanupStatus = Coupon.CleanupStatus.NONE,
            cleanupStartedAt = null,
            cleanupFinishedAt = null,
            cleanupError = null,
            rawOcrText = ocrText,
            ocrConfidence = extractionResult.confidence,
            extractionSource = Coupon.ExtractionSource.OCR_FAST
        )
        val coupon = finalizeCoupon(baseCoupon, ocrText, captureTimestamp)
            .ensureConfidenceBreakdown(confidenceBreakdown)

        return OcrFirstExtractionResult(
            coupon = coupon,
            rawOcrText = ocrText,
            confidence = extractionResult.confidence,
            success = true,
            failureReason = null
        )
    }

    private fun createOcrOnlyResult(
        imageUri: String?,
        ocrText: String,
        confidence: Float,
        captureTimestamp: Date?,
        failureReason: String
    ): OcrFirstExtractionResult {
        val description = ocrText
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(500)
            .ifBlank { failureReason }

        val coupon = finalizeCoupon(
            base = Coupon(
                storeName = Coupon.Defaults.UNKNOWN_STORE,
                description = description,
                redeemCode = null,
                imageUri = imageUri,
                status = Coupon.Status.ACTIVE,
                needsAttention = true,
                cleanupStatus = Coupon.CleanupStatus.NONE,
                rawOcrText = ocrText,
                ocrConfidence = confidence,
                extractionSource = Coupon.ExtractionSource.OCR_FAST
            ),
            ocrText = ocrText,
            captureTimestamp = captureTimestamp
        )

        return OcrFirstExtractionResult(
            coupon = coupon,
            rawOcrText = ocrText,
            confidence = confidence,
            success = false,
            failureReason = failureReason
        )
    }

    private fun buildExtractionContext(
        ocrText: String,
        extractedInfo: Map<String, String>
    ): ExtractionContext {
        val fallbackBrand = ocrText.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    line.length in 3..48 &&
                    line.any { it.isLetter() } &&
                    StoreCandidateValidator.isAcceptable(line, ocrText)
            }

        val brandHint = sequenceOf(
            extractedInfo["storeName"],
            extractedInfo["brand"],
            extractedInfo["app"],
            extractedInfo["merchant"],
            fallbackBrand
        )
            .mapNotNull { candidate ->
                candidate?.takeIf { StoreCandidateValidator.isAcceptable(it, ocrText) }
            }
            .firstOrNull()

        val categoryHint = extractedInfo["category"]?.takeIf { it.isNotBlank() }

        return ExtractionContext(
            brandHint = brandHint,
            categoryHint = categoryHint,
            previousSuccesses = brandHint?.let { listOf(it) } ?: emptyList()
        )
    }

    private fun buildConfidenceMap(
        existing: Map<String, Float>,
        extractedFields: Map<FieldType, ExtractionCandidate>
    ): Map<String, Float> {
        if (existing.isNotEmpty()) return existing
        if (extractedFields.isEmpty()) return emptyMap()
        return extractedFields.entries.associate { (fieldType, candidate) ->
            fieldType.name.lowercase(Locale.ROOT) to candidate.confidence
        }
    }

    private suspend fun recognizeTextBlocks(bitmap: Bitmap): List<TextBlock> {
        return runCatching {
            ocrEngine.recognizeWithBoxes(bitmap).map { span ->
                TextBlock(
                    text = span.text,
                    bounds = RectF(span.boundingBox),
                    confidence = span.confidence
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun Coupon.ensureConfidenceBreakdown(breakdown: Map<String, Float>): Coupon {
        if (breakdown.isEmpty()) return this
        if (extractionConfidenceBreakdown.isNotEmpty()) return this
        return copy(extractionConfidenceBreakdown = breakdown)
    }

    private fun finalizeCoupon(base: Coupon, ocrText: String?, captureTimestamp: Date?): Coupon {
        val refined = CouponPostProcessor.refine(
            coupon = base,
            context = CouponFixContext(
                ocrText = ocrText,
                captureTimestamp = captureTimestamp
            )
        )
        val normalized = refined.copy(expiryDate = normalizeExpiryDate(refined.expiryDate, captureTimestamp))
        val assessment = CouponExtractionConfidenceScorer.score(normalized, ocrText)
        return normalized.copy(
            extractionQualityScore = assessment.score,
            extractionConfidenceBreakdown = normalized.extractionConfidenceBreakdown.ifEmpty {
                assessment.fieldConfidences
            },
            needsAttention = normalized.needsAttention ||
                assessment.recommendation != ExtractionRecommendation.SAVE_DIRECTLY
        )
    }

    private companion object {
        private const val OCR_ACCEPTANCE_CONFIDENCE = 0.4f
    }
}

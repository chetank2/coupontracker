package com.example.coupontracker.domain.usecase

import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.capture.FullImageFallbackProbe
import com.example.coupontracker.extraction.capture.FullImageFallbackProbeResult
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.OcrFirstExtractionResult
import com.example.coupontracker.extraction.capture.shouldBlockFullImageFallback
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.MultiEngineOCR
import java.util.Date
import javax.inject.Inject

class ExtractCouponUseCase @Inject constructor(
    private val extractor: OcrFirstCouponExtractor,
    private val multiCouponExtractionService: MultiCouponExtractionService,
    private val routingUseCase: SingleScanRoutingUseCase,
    private val fullImageFallbackProbe: FullImageFallbackProbe
) {
    private companion object {
        const val TAG = "ExtractCouponUseCase"
    }

    suspend operator fun invoke(
        bitmap: Bitmap,
        imageUri: String?,
        captureTimestamp: Date? = null
    ): OcrFirstExtractionResult = extract(
        ExtractCouponRequest.BitmapInput(
            bitmap = bitmap,
            imageUri = imageUri,
            captureTimestamp = captureTimestamp
        )
    )

    suspend fun extract(request: ExtractCouponRequest): OcrFirstExtractionResult {
        return when (request) {
            is ExtractCouponRequest.BitmapInput -> extractor.extract(
                bitmap = request.bitmap,
                imageUri = request.imageUri,
                captureTimestamp = request.captureTimestamp
            )
            is ExtractCouponRequest.ScopedOcrInput -> extractor.extractFromOcr(
                bitmap = request.bitmap,
                ocrText = request.ocrText,
                ocrHints = request.ocrHints,
                ocrBlocks = request.ocrBlocks,
                imageUri = request.imageUri,
                captureTimestamp = request.captureTimestamp
            )
        }
    }

    suspend fun routeSingleScan(request: SingleScanExtractionRequest): SingleScanExtractionOutcome {
        val cropInstances = if (request.detectorAvailable) {
            request.detectCouponCrops(request.bitmap)
        } else {
            emptyList()
        }

        val initialAction = routingUseCase.planAfterCropDetection(
            detectorAvailable = request.detectorAvailable,
            detectedCropCount = cropInstances.size
        )

        return when (initialAction) {
            is SingleScanRouteAction.ProcessSingleCrop -> SingleScanExtractionOutcome.ProcessSingleCrop(
                couponInstance = cropInstances.first(),
                events = listOf(initialAction.toEvent())
            )
            is SingleScanRouteAction.ShowMultiCouponSelection -> SingleScanExtractionOutcome.ShowMultiCouponSelection(
                couponInstances = cropInstances,
                events = listOf(initialAction.toEvent())
            )
            is SingleScanRouteAction.TryLayoutThenGuardedFallback -> routeLayoutThenGuardedFallback(
                request = request,
                initialAction = initialAction
            )
        }
    }

    private suspend fun routeLayoutThenGuardedFallback(
        request: SingleScanExtractionRequest,
        initialAction: SingleScanRouteAction.TryLayoutThenGuardedFallback
    ): SingleScanExtractionOutcome {
        val events = mutableListOf(initialAction.toEvent())
        val multiResult = runCatching {
            multiCouponExtractionService.extractMultipleCoupons(
                bitmap = request.bitmap,
                imageUri = request.imageUri,
                captureTimestamp = request.captureTimestamp,
                allowProgressiveFallback = false
            )
        }.getOrElse { error ->
            Log.e(TAG, "Layout multi-coupon probe failed", error)
            null
        }

        val extractedCoupons = multiResult?.coupons.orEmpty()
        if (extractedCoupons.isNotEmpty()) {
            events += SingleScanRouteEvent(
                executedStrategy = if (extractedCoupons.size > 1) {
                    "layout_multi_coupon_extraction"
                } else {
                    "layout_single_coupon_extraction"
                },
                reason = "${initialAction.reason}_layout_detected_${extractedCoupons.size}"
            )
            return SingleScanExtractionOutcome.LayoutCoupons(
                multiResult = multiResult!!,
                events = events
            )
        }

        events += SingleScanRouteEvent(
            executedStrategy = "ocr_first_manual_clean",
            reason = "${initialAction.reason}_layout_no_candidates"
        )

        if (shouldBlockFullImageFallback(multiResult)) {
            return SingleScanExtractionOutcome.FullImageReviewFallback(
                rawOcrText = "",
                reason = "${initialAction.reason}_layout_${multiResult?.screenshotType}_detected_${multiResult?.totalDetected ?: 0}",
                events = events
            )
        }

        val probeResult = fullImageFallbackProbe.evaluate(request.bitmap, request.processFullImageOcr)
        val fallbackReason = "${initialAction.reason}_${probeResult.decision.reason}"
        events += SingleScanRouteEvent(
            executedStrategy = if (probeResult.decision.allowDirectOcr) {
                "ocr_first_full_image_guarded"
            } else {
                "full_image_review_only"
            },
            reason = fallbackReason
        )

        return if (probeResult.decision.allowDirectOcr) {
            SingleScanExtractionOutcome.FullImageOcr(
                extraction = extract(
                    ExtractCouponRequest.BitmapInput(
                        bitmap = request.bitmap,
                        imageUri = request.imageUri,
                        captureTimestamp = request.captureTimestamp
                    )
                ),
                probeResult = probeResult,
                events = events
            )
        } else {
            SingleScanExtractionOutcome.FullImageReviewFallback(
                rawOcrText = probeResult.rawOcrText,
                reason = fallbackReason,
                ocrErrorMessage = probeResult.ocrErrorMessage,
                events = events
            )
        }
    }
}

sealed class ExtractCouponRequest {
    abstract val bitmap: Bitmap
    abstract val imageUri: String?
    abstract val captureTimestamp: Date?

    data class BitmapInput(
        override val bitmap: Bitmap,
        override val imageUri: String?,
        override val captureTimestamp: Date? = null
    ) : ExtractCouponRequest()

    data class ScopedOcrInput(
        override val bitmap: Bitmap,
        val ocrText: String,
        val ocrHints: Map<String, String> = emptyMap(),
        val ocrBlocks: List<TextBlock> = emptyList(),
        override val imageUri: String?,
        override val captureTimestamp: Date? = null
    ) : ExtractCouponRequest()
}

data class SingleScanExtractionRequest(
    val bitmap: Bitmap,
    val imageUri: String?,
    val captureTimestamp: Date?,
    val detectorAvailable: Boolean,
    val detectCouponCrops: suspend (Bitmap) -> List<CouponInstance>,
    val processFullImageOcr: suspend (Bitmap) -> MultiEngineOCR.OCRResult
)

data class SingleScanRouteEvent(
    val executedStrategy: String,
    val reason: String
)

sealed class SingleScanExtractionOutcome {
    abstract val events: List<SingleScanRouteEvent>

    data class ProcessSingleCrop(
        val couponInstance: CouponInstance,
        override val events: List<SingleScanRouteEvent>
    ) : SingleScanExtractionOutcome()

    data class ShowMultiCouponSelection(
        val couponInstances: List<CouponInstance>,
        override val events: List<SingleScanRouteEvent>
    ) : SingleScanExtractionOutcome()

    data class LayoutCoupons(
        val multiResult: MultiCouponExtractionService.MultiCouponResult,
        override val events: List<SingleScanRouteEvent>
    ) : SingleScanExtractionOutcome()

    data class FullImageOcr(
        val extraction: OcrFirstExtractionResult,
        val probeResult: FullImageFallbackProbeResult,
        override val events: List<SingleScanRouteEvent>
    ) : SingleScanExtractionOutcome()

    data class FullImageReviewFallback(
        val rawOcrText: String,
        val reason: String,
        val ocrErrorMessage: String? = null,
        override val events: List<SingleScanRouteEvent>
    ) : SingleScanExtractionOutcome()
}

private fun SingleScanRouteAction.toEvent(): SingleScanRouteEvent =
    SingleScanRouteEvent(
        executedStrategy = executedStrategy,
        reason = reason
    )

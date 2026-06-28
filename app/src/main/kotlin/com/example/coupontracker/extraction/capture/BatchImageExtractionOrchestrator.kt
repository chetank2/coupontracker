package com.example.coupontracker.extraction.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.multi.BatchPipelineFeatureFlag
import com.example.coupontracker.extraction.multi.CouponRegionPipeline
import com.example.coupontracker.extraction.multi.JsonToCouponConverter
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.util.AnalyticsTracker
import com.example.coupontracker.util.ExtractionStrategy
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.MultiEngineOCR
import com.example.coupontracker.util.UriPersistenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Owns image-level batch extraction after a bitmap has been decoded.
 *
 * The ViewModel supplies runtime detector/OCR callbacks; this class keeps the
 * batch capture package responsible for crop-first region isolation, crop
 * extraction, pipeline selection, and strategy telemetry.
 */
class BatchImageExtractionOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrFirstCouponExtractor: OcrFirstCouponExtractor,
    private val analyticsTracker: AnalyticsTracker,
    private val regionPipeline: CouponRegionPipeline,
    private val batchPipelineFlag: BatchPipelineFeatureFlag,
    private val batchRegionIsolationCoordinator: BatchRegionIsolationCoordinator,
    private val batchRegionExtractionRunner: BatchRegionExtractionRunner
) {
    private val uriPersistenceManager = UriPersistenceManager(context)

    suspend fun extract(
        uri: Uri,
        bitmap: Bitmap,
        runOcr: suspend (Bitmap) -> MultiEngineOCR.OCRResult,
        detectRegions: suspend (Bitmap, MultiEngineOCR.OCRResult.Success) -> List<HybridCouponDetector.CouponRegion>,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit
    ): List<Coupon> = batchRegionIsolationCoordinator.extract(
        uri = uri,
        bitmap = bitmap,
        runOcr = runOcr,
        detectRegions = detectRegions,
        extractIsolatedRegions = { isolatedRegions ->
            batchRegionExtractionRunner.extract(
                bitmap = bitmap,
                couponRegions = isolatedRegions,
                usePipeline = batchPipelineFlag.isEnabled(),
                trackBitmap = trackBitmap,
                releaseBitmap = releaseBitmap,
                extractPipeline = { crops -> extractPipelineCrops(crops, uri) },
                extractSingleRegion = { regionBitmap ->
                    extractCouponFromRegion(
                        regionBitmap = regionBitmap,
                        uri = uri
                    )
                }
            )
        }
    )

    private suspend fun extractCouponFromRegion(
        regionBitmap: Bitmap,
        uri: Uri
    ): Coupon {
        logStrategyExecution(
            requested = ExtractionStrategy.OCR_FIRST,
            executed = "ocr_first_manual_clean"
        )
        return processWithOcrFirstPath(uri, regionBitmap)
    }

    private suspend fun processWithOcrFirstPath(
        uri: Uri,
        bitmap: Bitmap
    ): Coupon = withContext(Dispatchers.IO) {
        val persistedUri = persistUri(uri)
        val captureTimestamp = extractCaptureTimestamp(persistedUri, uri)
        val extraction = ocrFirstCouponExtractor.extract(
            bitmap = bitmap,
            imageUri = persistedUri,
            captureTimestamp = captureTimestamp
        )
        if (!extraction.success) {
            Log.w(TAG, "OCR_FIRST low confidence; returning shared OCR review result")
        }
        extraction.coupon
    }

    private suspend fun extractPipelineCrops(
        crops: List<Bitmap>,
        uri: Uri
    ): List<Coupon> {
        val canonicalJsons = regionPipeline.extractFromCrops(crops)
        return canonicalJsons.map { json ->
            JsonToCouponConverter.convert(json, uri)
        }
    }

    private fun extractCaptureTimestamp(persistedUri: String?, originalUri: Uri): java.util.Date? {
        return runCatching {
            persistedUri?.let { ImageMetadataExtractor.extractCaptureTimestamp(context, Uri.parse(it)) }
        }.getOrNull()
            ?: runCatching {
                ImageMetadataExtractor.extractCaptureTimestamp(context, originalUri)
            }.getOrNull()
    }

    private suspend fun persistUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        uriPersistenceManager.persistUri(uri)?.toString()
    }

    private suspend fun logStrategyExecution(
        requested: ExtractionStrategy,
        executed: String,
        reason: String? = null
    ) {
        val normalizedExecuted = executed.lowercase(Locale.getDefault())
        val message = buildString {
            append("Strategy[batch]: requested=")
            append(requested.name)
            append(", executed=")
            append(normalizedExecuted)
            if (!reason.isNullOrBlank()) {
                append(", reason=")
                append(reason)
            }
        }

        Log.i(TAG, message)
        analyticsTracker.trackStrategyExecution(
            STRATEGY_SURFACE_BATCH,
            requested,
            normalizedExecuted,
            reason
        )

        if (!requested.name.equals(normalizedExecuted, ignoreCase = true) && !reason.isNullOrBlank()) {
            analyticsTracker.trackStrategyFallback(
                STRATEGY_SURFACE_BATCH,
                requested,
                normalizedExecuted,
                reason
            )
        }
    }

    private companion object {
        private const val TAG = "BatchImageExtractionOrchestrator"
        private const val STRATEGY_SURFACE_BATCH = "batch_capture"
    }
}

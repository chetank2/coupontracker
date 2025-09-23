package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Model-based OCR Service that uses the trained model for coupon recognition
 * without relying on external APIs
 */
class ModelBasedOCRService(private val context: Context) {
    private val TAG = "ModelBasedOCRService"

    // OCR components
    private val imagePreprocessor = ImagePreprocessor()
    private val detectorPipeline = DetectorPipeline(context)
    private val textExtractor = TextExtractor()
    private val couponPatternRecognizer = CouponPatternRecognizer(context)
    private val patternInitialized = AtomicBoolean(false)

    // Model version and metadata
    private val modelVersion = "3.0.0"
    private val modelName = "unified_coupon_recognizer"

    /**
     * Initialize the service
     */
    suspend fun initialize() {
        withContext(Dispatchers.IO) {
            try {
                // Initialize pattern recognizer so first request does not pay the setup cost
                val ready = couponPatternRecognizer.initialize()
                patternInitialized.set(ready)

                Log.d(TAG, "Service initialized. Pattern recognizer available: $ready")
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing service", e)
                patternInitialized.set(false)
            }
        }
    }

    /**
     * Process an image to extract coupon information
     */
    suspend fun processCouponImage(bitmap: Bitmap): CouponInfo {
        try {
            Log.d(TAG, "Processing coupon image with model $modelName v$modelVersion")

            val detectionStart = SystemClock.elapsedRealtimeNanos()
            val detections = detectorPipeline.detect(bitmap)
            val detectionMs = (SystemClock.elapsedRealtimeNanos() - detectionStart) / 1_000_000
            Log.d(TAG, "Detector produced ${detections.size} ROI(s) in ${detectionMs}ms")

            val roiStart = SystemClock.elapsedRealtimeNanos()
            val roiInfos = mutableListOf<Pair<CouponInfo, DetectorPipeline.Detection>>()

            ensurePatternRecognizerInitialized()

            for (detection in detections) {
                val roiBitmap = cropRegion(bitmap, detection.boundingBox)
                val roiInfo = try {
                    recognizeFromRegion(roiBitmap)
                } catch (e: Exception) {
                    Log.w(TAG, "ROI recognition failed", e)
                    null
                }
                if (roiInfo != null && roiInfo.isValid()) {
                    val baseConfidence = if (roiInfo.confidence > 0f) roiInfo.confidence else 0.6f
                    val adjustedConfidence = (baseConfidence * detection.confidence).coerceIn(0f, 1f)
                    val scaledFieldConf = roiInfo.fieldConfidences.mapValues { (_, value) ->
                        (value * detection.confidence).coerceIn(0f, 1f)
                    }
                    val normalizedInfo = roiInfo.copy(
                        confidence = adjustedConfidence,
                        fieldConfidences = scaledFieldConf
                    )
                    roiInfos.add(normalizedInfo to detection)
                    Log.d(
                        TAG,
                        "ROI success label=${detection.label} detConf=${"%.2f".format(detection.confidence)}" +
                            " roiConf=${"%.2f".format(roiInfo.confidence)}"
                    )
                }
            }
            val roiDurationMs = (SystemClock.elapsedRealtimeNanos() - roiStart) / 1_000_000
            Log.d(TAG, "ROI OCR completed in ${roiDurationMs}ms (${roiInfos.size} valid regions)")

            val bestEntry = roiInfos.maxByOrNull { it.first.confidence }
            if (bestEntry != null && bestEntry.first.isValid()) {
                Log.d(TAG, "Detector pipeline selected coupon info: ${bestEntry.first}")
                MetricsLogger.logOcrEvent(
                    context = context,
                    detectionMs = detectionMs,
                    roiMs = roiDurationMs,
                    roiCount = roiInfos.size,
                    bestConfidence = bestEntry.first.confidence,
                    fallbackUsed = false
                )
                return bestEntry.first
            }

            Log.w(TAG, "Detector pipeline fallback triggered; using whole-image recognizer")

            val preprocessedBitmap = imagePreprocessor.preprocess(bitmap)
            val patternResults = runCatching {
                ensurePatternRecognizerInitialized()
                couponPatternRecognizer.recognizeElements(preprocessedBitmap)
            }.getOrElse {
                Log.w(TAG, "Pattern recognizer fallback failed", it)
                emptyMap()
            }

            if (patternResults.isEmpty()) {
                Log.w(TAG, "Pattern recognizer returned no elements for bitmap ${bitmap.width}x${bitmap.height}")
                throw OCRProcessingException("Pattern recognizer returned no results")
            }

            val couponInfo = couponPatternRecognizer.convertToCouponInfo(patternResults)
            val enrichedCouponInfo = enrichCouponInfo(couponInfo, patternResults)

            if (!enrichedCouponInfo.isValid()) {
                Log.w(
                    TAG,
                    "Coupon data from pattern recognizer failed validation: $enrichedCouponInfo"
                )
                throw OCRProcessingException("Extracted coupon information failed validation")
            }

            Log.d(TAG, "Fallback coupon processing complete: $enrichedCouponInfo")
            MetricsLogger.logOcrEvent(
                context = context,
                detectionMs = detectionMs,
                roiMs = roiDurationMs,
                roiCount = roiInfos.size,
                bestConfidence = enrichedCouponInfo.confidence,
                fallbackUsed = true
            )
            return enrichedCouponInfo
        } catch (e: OCRProcessingException) {
            Log.e(TAG, "OCR processing failed", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error processing coupon image", e)
            throw OCRProcessingException("Unexpected OCR failure", e)
        }
    }

    private fun cropRegion(bitmap: Bitmap, region: RectF): Bitmap {
        val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private suspend fun recognizeFromRegion(bitmap: Bitmap): CouponInfo? {
        val elements = couponPatternRecognizer.recognizeElements(bitmap)
        if (elements.isEmpty()) return null
        val info = couponPatternRecognizer.convertToCouponInfo(elements)
        return if (info.isValid()) info else null
    }

    private suspend fun ensurePatternRecognizerInitialized() {
        if (patternInitialized.get()) return
        Log.d(TAG, "Initializing CouponPatternRecognizer...")
        val initialized = couponPatternRecognizer.initialize()
        patternInitialized.set(initialized)
        if (!initialized) {
            Log.e(TAG, "CouponPatternRecognizer initialization failed")
            throw OCRProcessingException("Unable to initialize coupon pattern recognizer")
        } else {
            Log.d(TAG, "CouponPatternRecognizer ready for use")
        }
    }

    private fun enrichCouponInfo(
        initialCouponInfo: CouponInfo,
        patternResults: Map<String, String>
    ): CouponInfo {
        val aggregatedText = patternResults.entries.joinToString(separator = "\n") { (type, value) ->
            "${type.trim()}: ${value.trim()}"
        }

        if (aggregatedText.isBlank()) {
            return initialCouponInfo
        }

        val extractedFromText = textExtractor.extractCouponInfoSync(aggregatedText)

        if (!extractedFromText.isValid()) {
            return initialCouponInfo
        }

        return mergeCouponInfo(initialCouponInfo, extractedFromText)
    }

    private fun mergeCouponInfo(primary: CouponInfo, secondary: CouponInfo): CouponInfo {
        val mergedFieldConf = secondary.fieldConfidences.toMutableMap().apply {
            putAll(primary.fieldConfidences)
        }

        return CouponInfo(
            storeName = primary.storeName.ifBlank { secondary.storeName },
            description = primary.description.ifBlank { secondary.description },
            expiryDate = primary.expiryDate ?: secondary.expiryDate,
            cashbackAmount = primary.cashbackAmount ?: secondary.cashbackAmount,
            redeemCode = primary.redeemCode ?: secondary.redeemCode,
            category = primary.category ?: secondary.category,
            rating = primary.rating ?: secondary.rating,
            status = primary.status ?: secondary.status,
            discountType = primary.discountType ?: secondary.discountType,
            minimumPurchase = primary.minimumPurchase ?: secondary.minimumPurchase,
            maximumDiscount = primary.maximumDiscount ?: secondary.maximumDiscount,
            paymentMethod = primary.paymentMethod ?: secondary.paymentMethod,
            platformType = primary.platformType ?: secondary.platformType,
            usageLimit = primary.usageLimit ?: secondary.usageLimit,
            benefitType = primary.benefitType ?: secondary.benefitType,
            benefitValue = primary.benefitValue ?: secondary.benefitValue,
            currency = primary.currency ?: secondary.currency,
            expiryIso = primary.expiryIso ?: secondary.expiryIso,
            app = primary.app ?: secondary.app,
            confidence = max(primary.confidence, secondary.confidence),
            fieldConfidences = mergedFieldConf
        )
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        detectorPipeline.close()
    }


}

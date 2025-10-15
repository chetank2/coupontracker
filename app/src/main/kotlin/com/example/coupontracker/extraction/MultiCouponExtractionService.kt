package com.example.coupontracker.extraction

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.extraction.deterministic.DescriptionComposer
import com.example.coupontracker.extraction.deterministic.DeterministicCouponExtractor
import com.example.coupontracker.extraction.deterministic.SmartCouponSanitizer
import com.example.coupontracker.extraction.deterministic.StoreCanon
import com.example.coupontracker.extraction.region.CouponRegionizer
import com.example.coupontracker.extraction.region.CouponRegionizerConfig
import com.example.coupontracker.ml.HybridCouponDetector
import com.example.coupontracker.ml.ScreenshotClassifier
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.MultiEngineOCR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-Coupon Extraction Service
 * Specialized pipeline for extracting multiple coupons from app screenshots
 * 
 * Optimized for:
 * - Amazon offers page
 * - Myntra deals
 * - PhonePe rewards
 * - Flipkart coupons
 * - Paytm cashback offers
 * 
 * Flow:
 * 1. Classify screenshot type
 * 2. Segment into coupon regions (hybrid detection)
 * 3. Extract each region with validation
 * 4. Filter low-confidence results
 * 5. Return array of validated coupons
 */
@Singleton
class MultiCouponExtractionService @Inject constructor(
    private val context: Context,
    private val ocrEngine: OcrEngine,
    private val progressiveExtractionService: ProgressiveExtractionService,
    private val confidenceScorer: ConfidenceScorer,
    private val extractionValidator: ExtractionValidator
) {
    
    private val screenshotClassifier = ScreenshotClassifier()
    private val hybridDetector = HybridCouponDetector(context, ocrEngine)
    private val multiEngineOCR = MultiEngineOCR(context, ocrEngine)
    private val regionizerConfig = CouponRegionizerConfig.load(context)
    private val regionizer = CouponRegionizer(regionizerConfig)
    private val storeCanon = StoreCanon(context)
    private val deterministicExtractor = DeterministicCouponExtractor(
        storeCanon = storeCanon,
        rewardDropPhrases = regionizerConfig.reward.dropPhrases
    )
    private val descriptionComposer = DescriptionComposer(storeCanon)
    private val sanitizer = SmartCouponSanitizer(storeCanon, descriptionComposer)
    
    companion object {
        private const val TAG = "MultiCouponExtractionService"
        
        // Minimum confidence threshold for accepting extracted coupons
        private const val MIN_CONFIDENCE_THRESHOLD = 0.50f
        
        // Maximum coupons to extract from a single screenshot
        private const val MAX_COUPONS_PER_SCREENSHOT = 10
    }
    
    /**
     * Coupon with confidence metadata
     */
    data class CouponWithConfidence(
        val coupon: Coupon,
        val confidence: Float,
        val extractionQuality: ExtractionValidator.ExtractionQuality,
        val warnings: List<String>
    )
    
    /**
     * Multi-coupon extraction result
     */
    data class MultiCouponResult(
        val coupons: List<CouponWithConfidence>,
        val screenshotType: ScreenshotClassifier.ScreenshotType,
        val totalDetected: Int,
        val totalExtracted: Int,
        val totalFiltered: Int
    )
    
    /**
     * Extract multiple coupons from screenshot
     * Main entry point for multi-coupon extraction
     */
    suspend fun extractMultipleCoupons(
        bitmap: Bitmap,
        imageUri: String? = null
    ): MultiCouponResult = withContext(Dispatchers.Default) {
        
        Log.d(TAG, "========================================")
        Log.d(TAG, "Starting multi-coupon extraction")
        Log.d(TAG, "Image: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "========================================")
        
        try {
            // Step 1: Run OCR on full image
            Log.d(TAG, "Step 1: Running OCR...")
            val ocrResult = multiEngineOCR.processImage(bitmap)
            
            if (ocrResult !is MultiEngineOCR.OCRResult.Success) {
                Log.e(TAG, "OCR failed, cannot proceed with multi-coupon extraction")
                return@withContext MultiCouponResult(
                    coupons = emptyList(),
                    screenshotType = ScreenshotClassifier.ScreenshotType.CAMERA_CAPTURE,
                    totalDetected = 0,
                    totalExtracted = 0,
                    totalFiltered = 0
                )
            }
            
            val fullText = ocrResult.extractedInfo.values.joinToString("\n")
            Log.d(TAG, "OCR extracted ${fullText.length} characters")
            
            // Step 2: Classify screenshot type
            Log.d(TAG, "Step 2: Classifying screenshot type...")
            val classification = screenshotClassifier.classify(bitmap, fullText)
            Log.d(TAG, "Classification: ${classification.type} (confidence: ${classification.confidence})")
            
            // Step 3: Detect coupon regions using hybrid detector
            Log.d(TAG, "Step 3: Detecting coupon regions...")
            val couponRegions = hybridDetector.detectCoupons(bitmap, ocrResult)
            Log.d(TAG, "Detected ${couponRegions.size} coupon region(s)")

            val regionCandidates = regionizer.regionize(
                bitmap = bitmap,
                screenshotType = classification.type,
                ocrText = fullText,
                fallbackRegions = couponRegions
            )

            // Limit to MAX_COUPONS_PER_SCREENSHOT
            val regionsToProcess = regionCandidates.take(MAX_COUPONS_PER_SCREENSHOT)
            if (regionCandidates.size > MAX_COUPONS_PER_SCREENSHOT) {
                Log.w(TAG, "Too many regions detected (${regionCandidates.size}), limiting to $MAX_COUPONS_PER_SCREENSHOT")
            }

            // Step 4: Extract each coupon region
            Log.d(TAG, "Step 4: Extracting ${regionsToProcess.size} coupon(s)...")
            val extractedCoupons = mutableListOf<CouponWithConfidence>()
            var filteredCount = 0
            
            for ((index, region) in regionsToProcess.withIndex()) {
                try {
                    Log.d(TAG, "  Extracting coupon ${index + 1}/${regionsToProcess.size} (mode=${region.mode})...")

                    val couponWithConfidence = extractSingleRegion(
                        bitmap = bitmap,
                        candidate = region,
                        regionIndex = index,
                        imageUri = imageUri
                    )
                    
                    // Step 5: Filter by confidence threshold
                    if (couponWithConfidence.confidence >= MIN_CONFIDENCE_THRESHOLD) {
                        extractedCoupons.add(couponWithConfidence)
                        Log.d(TAG, "  ✅ Coupon ${index + 1} extracted: store='${couponWithConfidence.coupon.storeName}', confidence=${couponWithConfidence.confidence}")
                    } else {
                        filteredCount++
                        Log.w(TAG, "  ⚠️ Coupon ${index + 1} filtered: confidence too low (${couponWithConfidence.confidence})")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "  ❌ Error extracting coupon ${index + 1}", e)
                    filteredCount++
                }
            }
            
            Log.d(TAG, "========================================")
            Log.d(TAG, "Multi-coupon extraction complete")
            Log.d(TAG, "Detected: ${couponRegions.size}, Extracted: ${extractedCoupons.size}, Filtered: $filteredCount")
            Log.d(TAG, "========================================")
            
            return@withContext MultiCouponResult(
                coupons = extractedCoupons,
                screenshotType = classification.type,
                totalDetected = regionCandidates.size,
                totalExtracted = extractedCoupons.size,
                totalFiltered = filteredCount
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Fatal error in multi-coupon extraction", e)
            return@withContext MultiCouponResult(
                coupons = emptyList(),
                screenshotType = ScreenshotClassifier.ScreenshotType.CAMERA_CAPTURE,
                totalDetected = 0,
                totalExtracted = 0,
                totalFiltered = 0
            )
        }
    }
    
    /**
     * Extract a single coupon region with validation
     */
    private suspend fun extractSingleRegion(
        bitmap: Bitmap,
        candidate: CouponRegionizer.RegionCandidate,
        regionIndex: Int,
        imageUri: String?
    ): CouponWithConfidence {

        val regionBitmap = cropBitmapToRegion(bitmap, candidate.bounds)
            ?: throw IllegalStateException("Failed to crop region $regionIndex")

        return try {
            val existingText = candidate.sourceRegion?.ocrText?.takeIf { it.isNotBlank() }
            val regionText = existingText ?: when (val ocr = multiEngineOCR.processImage(regionBitmap)) {
                is MultiEngineOCR.OCRResult.Success -> ocr.text
                else -> ""
            }

            val deterministicResult = deterministicExtractor.extract(regionText, candidate.mode)

            val fallbackExtraction = if (deterministicResult.requiresFallback()) {
                progressiveExtractionService.extractCoupon(
                    androidContext = context,
                    image = regionBitmap,
                    ocrText = regionText,
                    ocrBlocks = emptyList(),
                    imageUri = imageUri ?: "multi_coupon_region_$regionIndex",
                    captureTimestamp = Date()
                )
            } else {
                null
            }

            val mergedFields = deterministicResult.withFallbackCoupon(fallbackExtraction?.coupon)
            val sanitized = sanitizer.sanitize(
                fields = mergedFields,
                fallbackCoupon = fallbackExtraction?.coupon,
                imageUri = imageUri,
                captureTimestamp = Date()
            )

            val refinedCoupon = CouponPostProcessor.refine(
                coupon = sanitized.coupon,
                context = CouponFixContext(
                    ocrText = regionText,
                    captureTimestamp = Date()
                )
            )

            val validationResult = extractionValidator.validate(refinedCoupon)

            CouponWithConfidence(
                coupon = refinedCoupon,
                confidence = maxOf(sanitized.confidence, validationResult.validationResult.overallConfidence),
                extractionQuality = validationResult.extractionQuality,
                warnings = (sanitized.issues + validationResult.actionableRecommendations).distinct()
            )
        } finally {
            if (!regionBitmap.isRecycled) {
                regionBitmap.recycle()
            }
        }
    }
    
    /**
     * Crop bitmap to region bounds (safe cropping with bounds validation)
     */
    private fun cropBitmapToRegion(
        bitmap: Bitmap,
        region: android.graphics.Rect
    ): Bitmap? {
        return try {
            // Validate and constrain bounds
            val left = region.left.coerceIn(0, bitmap.width)
            val top = region.top.coerceIn(0, bitmap.height)
            val right = region.right.coerceIn(left, bitmap.width)
            val bottom = region.bottom.coerceIn(top, bitmap.height)
            
            val width = right - left
            val height = bottom - top
            
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Invalid crop dimensions: width=$width, height=$height")
                return null
            }
            
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap to region", e)
            null
        }
    }
    
    /**
     * Quick check if image should use multi-coupon extraction
     * (Can be called before full extraction to route to appropriate flow)
     */
    suspend fun shouldUseMultiCouponExtraction(bitmap: Bitmap): Boolean = withContext(Dispatchers.Default) {
        try {
            // Run quick OCR
            val ocrResult = multiEngineOCR.processImage(bitmap)
            if (ocrResult !is MultiEngineOCR.OCRResult.Success) {
                return@withContext false
            }
            
            val fullText = ocrResult.extractedInfo.values.joinToString("\n")
            
            // Quick classification
            val classification = screenshotClassifier.classify(bitmap, fullText)
            
            // Use multi-coupon extraction if:
            // 1. Classified as MULTI_COUPON_APP with high confidence
            // 2. Or has multiple coupon indicators (fallback check)
            val shouldUse = classification.type == ScreenshotClassifier.ScreenshotType.MULTI_COUPON_APP &&
                           classification.confidence >= 0.7f
            
            val hasMultipleIndicators = screenshotClassifier.hasMultipleCouponIndicators(fullText)
            
            return@withContext shouldUse || hasMultipleIndicators
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if should use multi-coupon extraction", e)
            return@withContext false
        }
    }
}


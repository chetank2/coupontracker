package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.model.CouponData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Advanced OCR Pipeline for coupon extraction with multiple OCR engines,
 * preprocessing, and structured data extraction
 */
class AdvancedOCRPipeline(
    private val context: Context,
    private val googleCloudVisionApiKey: String? = null
) {
    companion object {
        private const val TAG = "AdvancedOCRPipeline"
    }
    
    // Components
    private val imagePreprocessor = ImagePreprocessor()
    private val couponFieldExtractor = CouponFieldExtractor()
    private val mlKitTextRecognition = MLKitRealTextRecognition()
    
    // Optional Google Vision API helper (initialized if API key is available)
    private val enhancedGoogleVision = googleCloudVisionApiKey?.takeIf { it.isNotBlank() }?.let {
        EnhancedGoogleVisionHelper(it, context)
    }
    
    /**
     * Process an image URI and extract structured coupon data
     * @param imageUri URI of the image to process
     * @return Extracted coupon data
     */
    suspend fun processCouponImage(imageUri: Uri): CouponData {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Processing coupon image: $imageUri")
                
                // Load bitmap
                val bitmap = ImageLoaderUtil.loadBitmapFromUri(context, imageUri)
                    ?: throw Exception("Failed to load image")
                
                processCouponImage(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing coupon image from URI", e)
                createFallbackCoupon("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Process a bitmap and extract structured coupon data
     * Uses multiple OCR engines in parallel for best results
     * @param bitmap Bitmap to process
     * @return Extracted coupon data
     */
    suspend fun processCouponImage(bitmap: Bitmap): CouponData = coroutineScope {
        try {
            Log.d(TAG, "Processing coupon bitmap: ${bitmap.width}x${bitmap.height}")
            
            // 1. Preprocess image
            val processedBitmap = imagePreprocessor.preprocess(bitmap)
            Log.d(TAG, "Preprocessed image: ${processedBitmap.width}x${processedBitmap.height}")
            
            // 2. Create image variants for different OCR approaches
            val variants = imagePreprocessor.createProcessingVariants(processedBitmap)
            Log.d(TAG, "Created ${variants.size} preprocessing variants")
            
            // 3. Run OCR with multiple engines in parallel
            val textResults = mutableListOf<String>()
            
            // Process in parallel for speed
            val deferredResults = mutableListOf(
                // Always use ML Kit as base OCR
                async { processWithMlKit(variants[0]) }
            )
            
            // Add Google Vision API if available
            if (enhancedGoogleVision != null) {
                deferredResults.add(async { 
                    processWithGoogleVision(variants.firstOrNull() ?: processedBitmap) 
                })
                
                // If we have multiple variants, process a second one too
                if (variants.size > 1) {
                    deferredResults.add(async {
                        processWithGoogleVision(variants[1])
                    })
                }
            }
            
            // Await all results
            val results = deferredResults.awaitAll()
            textResults.addAll(results.filterNotNull())
            
            // 4. Choose best OCR result (typically the longest)
            val bestText = textResults.maxByOrNull { it.length } ?: ""
            Log.d(TAG, "Best OCR text (${bestText.length} chars): ${bestText.take(100)}...")
            
            if (bestText.isBlank()) {
                Log.w(TAG, "No text extracted from any OCR engine")
                return@coroutineScope createFallbackCoupon("No text extracted")
            }
            
            // 5. Extract structured fields with confidence levels
            val extractedFields = couponFieldExtractor.extractWithConfidence(bestText)
            Log.d(TAG, "Extracted ${extractedFields.size} structured fields")
            
            // 6. Create and validate coupon data model
            val couponData = CouponData.fromExtractedFields(extractedFields)
            
            if (!couponData.isValid()) {
                Log.w(TAG, "Extracted coupon data failed validation")
                
                // Try to recover with a second extraction attempt on the second-best text
                val secondBestText = textResults.filter { it != bestText }
                    .maxByOrNull { it.length } ?: ""
                
                if (secondBestText.isNotBlank()) {
                    Log.d(TAG, "Trying second-best text (${secondBestText.length} chars)")
                    val secondAttemptFields = couponFieldExtractor.extractWithConfidence(secondBestText)
                    val secondAttemptData = CouponData.fromExtractedFields(secondAttemptFields)
                    
                    if (secondAttemptData.isValid()) {
                        Log.d(TAG, "Second attempt produced valid coupon data")
                        return@coroutineScope secondAttemptData
                    }
                }
                
                // If both attempts failed, use best effort
                return@coroutineScope couponData
            }
            
            couponData
        } catch (e: Exception) {
            Log.e(TAG, "Error processing coupon image", e)
            createFallbackCoupon("Error: ${e.message}")
        }
    }
    
    /**
     * Process image with ML Kit
     */
    private suspend fun processWithMlKit(bitmap: Bitmap): String? {
        return try {
            Log.d(TAG, "Processing with ML Kit")
            val text = mlKitTextRecognition.processImageFromBitmap(bitmap)
            if (text.isBlank()) {
                Log.w(TAG, "ML Kit returned empty text")
                null
            } else {
                Log.d(TAG, "ML Kit extracted ${text.length} chars")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with ML Kit", e)
            null
        }
    }
    
    /**
     * Process image with Google Vision API
     */
    private suspend fun processWithGoogleVision(bitmap: Bitmap): String? {
        return try {
            if (enhancedGoogleVision == null) {
                Log.w(TAG, "Google Vision API not available (no API key)")
                return null
            }
            
            Log.d(TAG, "Processing with Google Vision API")
            val text = enhancedGoogleVision.processImageFromBitmap(bitmap)
            if (text.isBlank()) {
                Log.w(TAG, "Google Vision API returned empty text")
                null
            } else {
                Log.d(TAG, "Google Vision API extracted ${text.length} chars")
                text
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing with Google Vision API", e)
            null
        }
    }
    
    /**
     * Create a fallback coupon when extraction fails
     */
    private fun createFallbackCoupon(reason: String): CouponData {
        return CouponData(
            merchantName = "Extraction Failed",
            code = "RETRY",
            amount = "₹0",
            expiryDate = null,
            description = "Failed to extract coupon: $reason",
            terms = null,
            extractionScore = 0
        )
    }
} 
package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Super OCR Service that combines all available OCR technologies
 * to produce the best possible results
 */
class SuperOCRService(
    private val context: Context,
    private val googleVisionApiKey: String?,
    private val mistralApiKey: String?
) {
    private val TAG = "SuperOCRService"
    
    // OCR components
    private val mlKitTextRecognition = MLKitRealTextRecognition()
    private val imagePreprocessor = ImagePreprocessor()
    private val textExtractor = TextExtractor()
    
    // Optional components that require API keys
    private val googleVisionHelper = googleVisionApiKey?.takeIf { it.isNotBlank() }?.let {
        EnhancedGoogleVisionHelper(it, context)
    }
    
    private val mistralOcrService = mistralApiKey?.takeIf { it.isNotBlank() }?.let {
        MistralOcrService(it)
    }
    
    private val combinedOcrService = if (googleVisionHelper != null && mistralOcrService != null) {
        CombinedOCRService(googleVisionHelper, mistralOcrService)
    } else {
        null
    }
    
    // Track service availability
    private var googleVisionAvailable = AtomicBoolean(false)
    private var mistralApiAvailable = AtomicBoolean(false)
    private var combinedServiceAvailable = AtomicBoolean(false)
    
    /**
     * Extract text from an image using all available OCR engines
     * @param bitmap The image to process
     * @return The best extracted text
     */
    suspend fun extractText(bitmap: Bitmap): String = coroutineScope {
        try {
            Log.d(TAG, "Extracting text with all available OCR engines")
            
            // Create preprocessing variants
            val variants = imagePreprocessor.createProcessingVariants(bitmap)
            Log.d(TAG, "Created ${variants.size} preprocessing variants")
            
            // Run all available OCR engines in parallel
            val deferredResults = mutableListOf<Deferred<OcrResult>>()
            
            // Always use ML Kit (on-device)
            deferredResults.add(async {
                try {
                    val text = mlKitTextRecognition.processImageFromBitmap(variants[0])
                    OcrResult("ML Kit", text, text.length)
                } catch (e: Exception) {
                    Log.e(TAG, "ML Kit processing failed", e)
                    OcrResult("ML Kit", "", 0)
                }
            })
            
            // Use Google Vision if available
            if (googleVisionHelper != null && googleVisionAvailable.get()) {
                deferredResults.add(async {
                    try {
                        val text = googleVisionHelper.extractText(variants[0])
                        OcrResult("Google Vision", text, text.length)
                    } catch (e: Exception) {
                        Log.e(TAG, "Google Vision processing failed", e)
                        OcrResult("Google Vision", "", 0)
                    }
                })
                
                // Process a second variant with Google Vision
                if (variants.size > 1) {
                    deferredResults.add(async {
                        try {
                            val text = googleVisionHelper.extractText(variants[1])
                            OcrResult("Google Vision (Variant)", text, text.length)
                        } catch (e: Exception) {
                            Log.e(TAG, "Google Vision variant processing failed", e)
                            OcrResult("Google Vision (Variant)", "", 0)
                        }
                    })
                }
            }
            
            // Use Mistral if available
            if (mistralOcrService != null && mistralApiAvailable.get()) {
                deferredResults.add(async {
                    try {
                        val text = mistralOcrService.extractTextFromImage(bitmap)
                        OcrResult("Mistral", text, text.length)
                    } catch (e: Exception) {
                        Log.e(TAG, "Mistral processing failed", e)
                        OcrResult("Mistral", "", 0)
                    }
                })
            }
            
            // Use Combined service if available
            if (combinedOcrService != null && combinedServiceAvailable.get()) {
                deferredResults.add(async {
                    try {
                        val text = combinedOcrService.extractTextWithValidation(bitmap)
                        OcrResult("Combined", text, text.length * 2) // Give higher weight to combined results
                    } catch (e: Exception) {
                        Log.e(TAG, "Combined service processing failed", e)
                        OcrResult("Combined", "", 0)
                    }
                })
            }
            
            // Await all results
            val results = deferredResults.awaitAll()
            
            // Log all results
            results.forEach { result ->
                Log.d(TAG, "OCR Result from ${result.engine}: ${result.text.length} chars")
            }
            
            // Filter out empty results
            val validResults = results.filter { it.text.isNotBlank() }
            
            if (validResults.isEmpty()) {
                Log.w(TAG, "No valid OCR results from any engine")
                return@coroutineScope ""
            }
            
            // Select the best result (highest score)
            val bestResult = validResults.maxByOrNull { it.score }
            
            if (bestResult != null) {
                Log.d(TAG, "Best OCR result from ${bestResult.engine}: ${bestResult.text.length} chars")
                return@coroutineScope bestResult.text
            } else {
                Log.w(TAG, "No best result found")
                return@coroutineScope ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text with all OCR engines", e)
            return@coroutineScope ""
        }
    }

    /**
     * Extract coupon information using all available OCR engines
     * @param bitmap The image to process
     * @return CouponInfo object with the best extracted information
     */
    suspend fun extractCouponInfo(bitmap: Bitmap): CouponInfo = coroutineScope {
        try {
            Log.d(TAG, "Extracting coupon info with all available OCR engines")
            
            // First get the best text from all OCR engines
            val bestText = extractText(bitmap)
            
            if (bestText.isBlank()) {
                Log.w(TAG, "No text extracted from any OCR engine")
                return@coroutineScope CouponInfo()
            }
            
            // Try different extraction methods in parallel
            val deferredResults = mutableListOf<Deferred<CouponInfoResult>>()
            
            // 1. Try template-based extraction
            deferredResults.add(async {
                try {
                    val templateExtractor = CouponTemplateExtractor()
                    val template = templateExtractor.identifyTemplate(bitmap, bestText)
                    
                    if (template != CouponTemplateExtractor.CouponTemplate.Unknown) {
                        val info = templateExtractor.extractFromTemplate(bitmap, bestText, template)
                        CouponInfoResult("Template", info, calculateCompleteness(info) * 1.5) // Higher weight for template matches
                    } else {
                        CouponInfoResult("Template", CouponInfo(), 0.0)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Template extraction failed", e)
                    CouponInfoResult("Template", CouponInfo(), 0.0)
                }
            })
            
            // 2. Try region-based extraction if Google Vision is available
            if (googleVisionHelper != null) {
                deferredResults.add(async {
                    try {
                        val regionExtractor = RegionBasedExtractor(googleVisionHelper)
                        val info = regionExtractor.extractCouponInfo(bitmap, bestText)
                        CouponInfoResult("Region", info, calculateCompleteness(info))
                    } catch (e: Exception) {
                        Log.e(TAG, "Region extraction failed", e)
                        CouponInfoResult("Region", CouponInfo(), 0.0)
                    }
                })
            }
            
            // 3. Try basic text extraction
            deferredResults.add(async {
                try {
                    val info = textExtractor.extractCouponInfoSync(bestText)
                    CouponInfoResult("Basic", info, calculateCompleteness(info))
                } catch (e: Exception) {
                    Log.e(TAG, "Basic extraction failed", e)
                    CouponInfoResult("Basic", CouponInfo(), 0.0)
                }
            })
            
            // 4. Try Mistral structured extraction if available
            if (mistralOcrService != null && mistralApiAvailable.get()) {
                deferredResults.add(async {
                    try {
                        val structuredDataPrompt = createStructuredDataPrompt(bestText)
                        val structuredDataJson = mistralOcrService.processTextWithCustomPrompt(bitmap, structuredDataPrompt)
                        
                        if (structuredDataJson.isNotBlank()) {
                            val info = parseCouponInfoFromJson(structuredDataJson) ?: CouponInfo()
                            CouponInfoResult("Mistral", info, calculateCompleteness(info) * 1.2) // Higher weight for AI extraction
                        } else {
                            CouponInfoResult("Mistral", CouponInfo(), 0.0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Mistral structured extraction failed", e)
                        CouponInfoResult("Mistral", CouponInfo(), 0.0)
                    }
                })
            }
            
            // Await all results
            val results = deferredResults.awaitAll()
            
            // Log all results
            results.forEach { result ->
                Log.d(TAG, "Extraction Result from ${result.method}: Score=${result.score}")
            }
            
            // Filter out empty results
            val validResults = results.filter { it.score > 0 }
            
            if (validResults.isEmpty()) {
                Log.w(TAG, "No valid extraction results from any method")
                return@coroutineScope CouponInfo()
            }
            
            // Select the best result (highest score)
            val bestResult = validResults.maxByOrNull { it.score }
            
            if (bestResult != null) {
                Log.d(TAG, "Best extraction result from ${bestResult.method}: Score=${bestResult.score}")
                return@coroutineScope bestResult.info
            } else {
                Log.w(TAG, "No best result found")
                return@coroutineScope CouponInfo()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting coupon info with all methods", e)
            return@coroutineScope CouponInfo()
        }
    }

    /**
     * Create structured data prompt for Mistral AI
     */
    private fun createStructuredDataPrompt(text: String): String {
        return """
            I have extracted the following text from a coupon image:
            ---
            $text
            ---
            
            Please extract the following information in JSON format:
            {
                "storeName": "The name of the store or brand",
                "code": "The coupon or promo code",
                "amount": "The discount amount or percentage",
                "expiryDate": "The expiry date if present",
                "description": "A brief description of the offer",
                "terms": "Any terms and conditions"
            }
            
            If any field is not found, set it to null. Return ONLY the JSON object, without any explanations or additional text.
        """.trimIndent()
    }

    /**
     * Parse coupon info from JSON response
     */
    private fun parseCouponInfoFromJson(jsonString: String): CouponInfo? {
        return try {
            // Try to extract just the JSON part if there's additional text
            val jsonRegex = """\{[\s\S]*\}""".toRegex()
            val jsonMatch = jsonRegex.find(jsonString)
            val jsonPart = jsonMatch?.value ?: jsonString
            
            val json = JSONObject(jsonPart)
            
            CouponInfo(
                storeName = json.optString("storeName", ""),
                redeemCode = json.optString("code", null),
                cashbackAmount = json.optString("amount", null),
                expiryDate = json.optString("expiryDate", null),
                description = json.optString("description", ""),
                terms = json.optString("terms", null)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON response", e)
            null
        }
    }

    /**
     * Test availability of all API services
     */
    suspend fun testApiAvailability() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Testing API services availability")
                
                // Test Google Vision availability
                googleVisionAvailable.set(false)
                if (googleVisionHelper != null) {
                    googleVisionAvailable.set(googleVisionHelper.testApiAvailability())
                }
                
                // Test Mistral API availability
                mistralApiAvailable.set(false)
                if (mistralOcrService != null) {
                    mistralApiAvailable.set(mistralOcrService.testApiAvailability())
                }
                
                // Test Combined service availability
                combinedServiceAvailable.set(false)
                if (combinedOcrService != null && googleVisionAvailable.get() && mistralApiAvailable.get()) {
                    combinedServiceAvailable.set(combinedOcrService.testApiAvailability())
                }
                
                Log.d(TAG, "API Service availability: " +
                      "Google Vision: ${googleVisionAvailable.get()}, " +
                      "Mistral: ${mistralApiAvailable.get()}, " +
                      "Combined: ${combinedServiceAvailable.get()}")
            } catch (e: Exception) {
                Log.e(TAG, "Error testing API services", e)
            }
        }
    }
    
    // Helper class for OCR results
    private data class OcrResult(
        val engine: String,
        val text: String,
        val score: Int
    )
    
    // Helper class for coupon info results
    private data class CouponInfoResult(
        val method: String,
        val info: CouponInfo,
        val score: Double
    )
    
    // Calculate completeness score for a CouponInfo object
    private fun calculateCompleteness(info: CouponInfo): Double {
        var score = 0.0
        
        if (info.storeName.isNotBlank()) score += 1.0
        if (info.redeemCode != null && info.redeemCode.isNotBlank()) score += 2.0
        if (info.description.isNotBlank()) score += 0.5
        if (info.cashbackAmount != null) score += 1.0
        if (info.expiryDate != null) score += 1.0
        if (info.terms != null && info.terms.isNotBlank()) score += 0.5
        
        return score
    }
}

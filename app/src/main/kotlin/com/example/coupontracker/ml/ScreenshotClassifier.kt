package com.example.coupontracker.ml

import android.graphics.Bitmap
import android.util.Log

/**
 * Screenshot Classifier
 * Classifies images to determine if they are:
 * - Multi-coupon app screenshots (Amazon offers, Myntra deals, etc.)
 * - Single screenshot captures
 * - Camera-captured photos
 * 
 * This helps optimize extraction strategy and user flow.
 */
class ScreenshotClassifier {
    
    companion object {
        private const val TAG = "ScreenshotClassifier"
        
        // Popular coupon/deal apps in India
        private val APP_IDENTIFIERS = listOf(
            // E-commerce
            "amazon.in", "amazon", "flipkart", "myntra", "ajio", "meesho",
            "shopclues", "snapdeal", "paytm mall", "tata cliq",
            
            // Payment/Wallet apps with offers
            "phonepe", "paytm", "google pay", "gpay", "mobikwik",
            "freecharge", "amazon pay",
            
            // Food delivery
            "swiggy", "zomato", "uber eats", "dunzo",
            
            // Grocery/Quick commerce
            "bigbasket", "grofers", "blinkit", "zepto", "instamart",
            
            // Travel/Booking
            "makemytrip", "goibibo", "cleartrip", "yatra", "oyo", "airbnb"
        ).map { it.lowercase() }
        
        // Multi-coupon indicators (when these appear multiple times)
        private val MULTI_COUPON_INDICATORS = listOf(
            "collect now", "get offer", "claim", "redeem",
            "save now", "grab deal", "avail offer",
            "% off", "cashback", "discount", "offer"
        ).map { it.lowercase() }
        
        // Screenshot metadata indicators
        private val SCREENSHOT_PATTERNS = listOf(
            // Status bar elements
            Regex("""\d{1,2}:\d{2}\s*(?:AM|PM)?""", RegexOption.IGNORE_CASE),
            Regex("""\d+%"""), // Battery percentage
            
            // UI chrome
            Regex("""\b(?:home|back|recent|menu)\b""", RegexOption.IGNORE_CASE),
            
            // Navigation indicators
            Regex("""[←→↑↓⬅➡⬆⬇]"""),
            
            // App-specific UI
            Regex("""\btap to\b""", RegexOption.IGNORE_CASE),
            Regex("""\bview all\b""", RegexOption.IGNORE_CASE),
            Regex("""\bsee details\b""", RegexOption.IGNORE_CASE)
        )
    }
    
    /**
     * Screenshot type classification
     */
    enum class ScreenshotType {
        MULTI_COUPON_APP,    // App screenshot with multiple coupons (3+)
        SINGLE_SCREENSHOT,   // Screenshot with 1-2 coupons or single offer
        CAMERA_CAPTURE       // Photo taken with camera
    }
    
    /**
     * Classification result with confidence
     */
    data class ClassificationResult(
        val type: ScreenshotType,
        val confidence: Float,
        val indicators: Map<String, Any>
    )
    
    /**
     * Classify image based on bitmap and OCR text
     */
    fun classify(bitmap: Bitmap, ocrText: String): ClassificationResult {
        Log.d(TAG, "Classifying image: ${bitmap.width}x${bitmap.height}, OCR length: ${ocrText.length}")
        
        val normalizedText = ocrText.lowercase()
        val indicators = mutableMapOf<String, Any>()
        
        // Check 1: Multiple coupon indicators
        val couponIndicatorCount = countMultipleCouponIndicators(normalizedText)
        indicators["couponIndicatorCount"] = couponIndicatorCount
        
        // Check 2: App identifier presence
        val appIdentified = identifyApp(normalizedText)
        indicators["appName"] = appIdentified ?: "unknown"
        
        // Check 3: Screenshot metadata
        val hasScreenshotMetadata = hasScreenshotMarkers(normalizedText)
        indicators["hasScreenshotMetadata"] = hasScreenshotMetadata
        
        // Check 4: Aspect ratio (screenshots are typically portrait, 16:9 or taller)
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        indicators["aspectRatio"] = aspectRatio
        val isPortrait = aspectRatio > 1.3f
        indicators["isPortrait"] = isPortrait
        
        // Check 5: Image quality (screenshots are usually higher quality)
        val hasHighDensity = bitmap.width >= 720 && bitmap.height >= 1280
        indicators["hasHighDensity"] = hasHighDensity
        
        // Classification logic
        val (type, confidence) = when {
            // Multi-coupon app screenshot: multiple indicators + app name + portrait
            couponIndicatorCount >= 3 && appIdentified != null && isPortrait -> {
                Pair(ScreenshotType.MULTI_COUPON_APP, 0.9f)
            }
            
            // Strong multi-coupon indicators even without app name
            couponIndicatorCount >= 4 && hasScreenshotMetadata -> {
                Pair(ScreenshotType.MULTI_COUPON_APP, 0.85f)
            }
            
            // Single screenshot: has metadata + portrait but fewer coupons
            (hasScreenshotMetadata || appIdentified != null) && isPortrait -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.8f)
            }
            
            // Screenshot characteristics without strong coupon signals
            hasScreenshotMetadata && hasHighDensity -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.7f)
            }
            
            // Camera capture: landscape or missing screenshot markers
            !isPortrait || (!hasScreenshotMetadata && appIdentified == null) -> {
                Pair(ScreenshotType.CAMERA_CAPTURE, 0.75f)
            }
            
            // Default to single screenshot with low confidence
            else -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.5f)
            }
        }
        
        Log.d(TAG, "Classification: $type (confidence: $confidence)")
        Log.d(TAG, "Indicators: $indicators")
        
        return ClassificationResult(type, confidence, indicators)
    }
    
    /**
     * Count occurrences of multi-coupon indicators
     */
    private fun countMultipleCouponIndicators(text: String): Int {
        var count = 0
        for (indicator in MULTI_COUPON_INDICATORS) {
            val pattern = Regex("""\b$indicator\b""", RegexOption.IGNORE_CASE)
            val matches = pattern.findAll(text).count()
            if (matches > 0) {
                count += matches
            }
        }
        return count
    }
    
    /**
     * Identify app from OCR text
     */
    private fun identifyApp(text: String): String? {
        for (app in APP_IDENTIFIERS) {
            if (text.contains(app, ignoreCase = true)) {
                return app
            }
        }
        return null
    }
    
    /**
     * Check for screenshot-specific markers
     */
    private fun hasScreenshotMarkers(text: String): Boolean {
        return SCREENSHOT_PATTERNS.any { pattern ->
            pattern.containsMatchIn(text)
        }
    }
    
    /**
     * Quick check if text suggests multiple coupons (for fast filtering)
     */
    fun hasMultipleCouponIndicators(text: String): Boolean {
        val count = countMultipleCouponIndicators(text.lowercase())
        return count >= 3
    }
}


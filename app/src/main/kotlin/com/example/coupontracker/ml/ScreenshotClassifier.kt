package com.example.coupontracker.ml

import android.graphics.Bitmap
import android.util.Log

/**
 * Screenshot Classifier
 * Classifies images to determine if they are:
 * - Multi-coupon app screenshots from any merchant or rewards app.
 * - Single screenshot captures
 * - Camera-captured photos
 * 
 * This helps optimize extraction strategy and user flow.
 */
class ScreenshotClassifier {
    
    companion object {
        private const val TAG = "ScreenshotClassifier"
        
        // Multi-coupon indicators (when these appear multiple times)
        private val MULTI_COUPON_INDICATORS = listOf(
            "collect now", "get offer", "claim", "redeem",
            "save now", "grab deal", "avail offer",
            "% off", "cashback", "discount",
            "limited time", "exclusive", "hot deal"
        )
        
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
        val couponIndicatorStats = analyzeCouponIndicators(normalizedText)
        indicators["couponIndicatorCount"] = couponIndicatorStats.totalCount
        indicators["couponIndicatorUniqueCount"] = couponIndicatorStats.uniqueCount
        indicators["couponIndicatorMatches"] = couponIndicatorStats.matchedIndicators
        
        // Check 2: Screenshot metadata
        val hasScreenshotMetadata = hasScreenshotMarkers(normalizedText)
        indicators["hasScreenshotMetadata"] = hasScreenshotMetadata
        
        // Check 3: Aspect ratio (screenshots are typically portrait, 16:9 or taller)
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        indicators["aspectRatio"] = aspectRatio
        val isPortrait = aspectRatio > 1.3f
        indicators["isPortrait"] = isPortrait
        
        // Check 4: Image quality (screenshots are usually higher quality)
        val hasHighDensity = bitmap.width >= 720 && bitmap.height >= 1280
        indicators["hasHighDensity"] = hasHighDensity
        
        // Classification logic
        val strongCouponSignals = couponIndicatorStats.uniqueCount >= 5 && couponIndicatorStats.totalCount >= 7
        val moderateCouponSignals = couponIndicatorStats.uniqueCount >= 4 && couponIndicatorStats.totalCount >= 6

        val singleCouponSignals = collectSingleCouponSignals(ocrText)
        indicators["codeCount"] = singleCouponSignals.codeMatches
        indicators["sectionMarkerCount"] = singleCouponSignals.sectionMarkers
        indicators["ctaTokenCount"] = singleCouponSignals.ctaTokens
        indicators["uniqueCodeCount"] = singleCouponSignals.uniqueCodes

        var (type, confidence) = when {
            // Strong coupon signals with screenshot metadata (even without app name)
            strongCouponSignals && hasScreenshotMetadata && isPortrait -> {
                Pair(ScreenshotType.MULTI_COUPON_APP, 0.88f)
            }

            // Very strong repeated coupon language is enough without known-app lists.
            strongCouponSignals && isPortrait -> {
                Pair(ScreenshotType.MULTI_COUPON_APP, 0.84f)
            }

            // Moderate coupon signals + metadata
            moderateCouponSignals && hasScreenshotMetadata && isPortrait -> {
                Pair(ScreenshotType.MULTI_COUPON_APP, 0.82f)
            }

            // Single screenshot: typical screenshot characteristics but limited coupon signals
            hasScreenshotMetadata && isPortrait -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.8f)
            }

            // Screenshot characteristics without strong coupon signals
            hasScreenshotMetadata && hasHighDensity -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.7f)
            }

            // Camera capture: landscape or missing screenshot markers
            !isPortrait || !hasScreenshotMetadata -> {
                Pair(ScreenshotType.CAMERA_CAPTURE, 0.75f)
            }

            // Default to single screenshot with low confidence
            else -> {
                Pair(ScreenshotType.SINGLE_SCREENSHOT, 0.5f)
            }
        }

        if (type == ScreenshotType.MULTI_COUPON_APP) {
            val insufficientIndicators = couponIndicatorStats.uniqueCount < 4 || couponIndicatorStats.totalCount < 7
            val strongSingleSignals = singleCouponSignals.codeMatches <= 1 &&
                singleCouponSignals.sectionMarkers <= 2 &&
                singleCouponSignals.ctaTokens >= 1

            if (insufficientIndicators || strongSingleSignals || isLikelySingleCoupon(ocrText)) {
                type = ScreenshotType.SINGLE_SCREENSHOT
                confidence = 0.7f
            }
        }
        
        Log.d(TAG, "Classification: $type (confidence: $confidence)")
        Log.d(TAG, "Indicators: $indicators")
        
        return ClassificationResult(type, confidence, indicators)
    }
    
    /**
     * Count occurrences of multi-coupon indicators
     */
    private fun analyzeCouponIndicators(text: String): IndicatorStats {
        var total = 0
        val matched = mutableSetOf<String>()

        for (indicator in MULTI_COUPON_INDICATORS) {
            val pattern = Regex("""\b${Regex.escape(indicator)}\b""", RegexOption.IGNORE_CASE)
            val matches = pattern.findAll(text).count()
            if (matches > 0) {
                matched += indicator.lowercase()
                total += matches
            }
        }

        return IndicatorStats(
            uniqueCount = matched.size,
            totalCount = total,
            matchedIndicators = matched
        )
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
        val stats = analyzeCouponIndicators(text.lowercase())
        return stats.uniqueCount >= 4 && stats.totalCount >= 6
    }

    /**
     * Heuristic to detect a single prominent coupon so we can bypass multi-coupon flows.
     */
    fun isLikelySingleCoupon(ocrText: String): Boolean {
        if (ocrText.isBlank()) {
            return true
        }

        val normalized = ocrText.lowercase()
        val indicatorStats = analyzeCouponIndicators(normalized)
        val signals = collectSingleCouponSignals(ocrText)

        if (signals.uniqueCodes > 1 || signals.codeMatches > 1) {
            return false
        }

        if (indicatorStats.uniqueCount >= 5 && indicatorStats.totalCount >= 9) {
            return false
        }

        val looksLikeCouponList = signals.sectionMarkers >= 3 || signals.shortOfferHeadings >= 3
        if (looksLikeCouponList) {
            return false
        }

        if (signals.ctaTokens >= 1 && indicatorStats.totalCount <= 10) {
            return true
        }

        return signals.emphasisTokens <= 3 && indicatorStats.totalCount <= 8
    }

    private data class IndicatorStats(
        val uniqueCount: Int,
        val totalCount: Int,
        val matchedIndicators: Set<String>
    )

    private data class SingleCouponSignals(
        val codeMatches: Int,
        val uniqueCodes: Int,
        val emphasisTokens: Int,
        val sectionMarkers: Int,
        val ctaTokens: Int,
        val shortOfferHeadings: Int
    )

    private fun collectSingleCouponSignals(text: String): SingleCouponSignals {
        val codePattern = Regex("""\bcode\s*[:\-]\s*([A-Z0-9]{3,})\b""", RegexOption.IGNORE_CASE)
        val codeMatches = codePattern.findAll(text).toList()
        val codes = codeMatches.mapNotNull { it.groupValues.getOrNull(1)?.uppercase()?.trim() }.toSet()
        val codeCount = codes.size
        val totalCodeMatches = codeMatches.size

        val emphasisTokens = Regex("""\b(?:flat\s+\d+%|\d+%\s+off|cashback|save\s+\d+|copy\s+code|apply\s+code|use\s+code)\b""",
            RegexOption.IGNORE_CASE)
            .findAll(text).count()

        val sectionMarkers = Regex("""\b(?:offer details|about\s+\w+|terms\s+and\s+conditions)\b""", RegexOption.IGNORE_CASE)
            .findAll(text).count()

        val ctaTokens = Regex("""\b(?:copy|copy code|avail now|subscribe now|scratch card|tap to copy|tap to apply)\b""", RegexOption.IGNORE_CASE)
            .findAll(text).count()

        val shortOfferHeadings = text.lines()
            .map { it.trim() }
            .count { it.isNotEmpty() && it.length <= 24 && it.contains("offer", ignoreCase = true) }

        return SingleCouponSignals(
            codeMatches = totalCodeMatches,
            uniqueCodes = codeCount,
            emphasisTokens = emphasisTokens,
            sectionMarkers = sectionMarkers,
            ctaTokens = ctaTokens,
            shortOfferHeadings = shortOfferHeadings
        )
    }
}

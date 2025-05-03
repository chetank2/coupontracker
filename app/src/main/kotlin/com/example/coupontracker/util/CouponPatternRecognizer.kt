package com.example.coupontracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import java.util.Date
import com.example.coupontracker.util.CouponInfo

/**
 * Recognizes coupon elements using patterns learned from training data
 */
class CouponPatternRecognizer(private val context: Context) {
    companion object {
        private const val TAG = "CouponPatternRecognizer"
        private const val PATTERNS_FILE = "coupon_model/coupon_patterns.txt"
    }

    // Tesseract OCR helper
    private val tesseractOCRHelper = TesseractOCRHelper(context)

    // Patterns for different coupon elements
    private val patterns: Map<String, List<Rect>> by lazy { loadPatterns() }

    // Initialization flag
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initialize the recognizer
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized.get()) {
            return@withContext true
        }

        try {
            // Initialize Tesseract
            val success = tesseractOCRHelper.initialize()

            if (success) {
                Log.d(TAG, "CouponPatternRecognizer initialized successfully")
                isInitialized.set(true)
            } else {
                Log.e(TAG, "Failed to initialize CouponPatternRecognizer")
            }

            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CouponPatternRecognizer", e)
            return@withContext false
        }
    }

    /**
     * Load patterns from the patterns file
     */
    private fun loadPatterns(): Map<String, List<Rect>> {
        val result = mutableMapOf<String, MutableList<Rect>>()

        try {
            val inputStream = context.assets.open(PATTERNS_FILE)
            val reader = inputStream.bufferedReader()

            reader.lines().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) {
                    // Skip blank lines and comments
                    return@forEach
                }

                if (line.contains(":")) {
                    val parts = line.split(":")
                    if (parts.size == 2) {
                        val type = parts[0]
                        val coords = parts[1].split(",")
                        if (coords.size == 4) {
                            val rect = Rect(
                                coords[0].toInt(),
                                coords[1].toInt(),
                                coords[2].toInt(),
                                coords[3].toInt()
                            )

                            if (!result.containsKey(type)) {
                                result[type] = mutableListOf()
                            }
                            result[type]!!.add(rect)
                        }
                    }
                }
            }

            reader.close()
            inputStream.close()

            Log.d(TAG, "Loaded ${result.size} pattern types with ${result.values.sumOf { it.size }} total patterns")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading patterns", e)
        }

        return result
    }

    /**
     * Recognize coupon elements in an image with enhanced processing
     */
    suspend fun recognizeElements(bitmap: Bitmap): Map<String, String> = withContext(Dispatchers.IO) {
        if (!isInitialized.get() && !initialize()) {
            return@withContext emptyMap<String, String>()
        }

        val result = mutableMapOf<String, String>()
        val confidenceScores = mutableMapOf<String, Float>()

        try {
            // Scale the patterns to match the bitmap dimensions
            val scaledPatterns = scalePatterns(bitmap.width, bitmap.height)

            // Process each pattern type
            for ((type, rects) in scaledPatterns) {
                // Track the best result for each type
                var bestText = ""
                var bestConfidence = 0.0f

                for (rect in rects) {
                    // Ensure rect is within bitmap bounds
                    val safeRect = ensureRectWithinBounds(rect, bitmap.width, bitmap.height)

                    // Skip if region is too small
                    if (safeRect.width() < 20 || safeRect.height() < 20) {
                        continue
                    }

                    // Extract region from bitmap
                    val regionBitmap = Bitmap.createBitmap(
                        bitmap,
                        safeRect.left,
                        safeRect.top,
                        safeRect.width(),
                        safeRect.height()
                    )

                    // Apply specialized preprocessing based on element type
                    val processedBitmap = preprocessImageForType(regionBitmap, type)

                    // Process with Tesseract using specialized configuration
                    val text = processWithSpecializedConfig(processedBitmap, type)

                    // Skip if no text was found
                    if (text.isBlank()) {
                        continue
                    }

                    // Calculate confidence score based on text quality and validation
                    val confidence = calculateConfidenceScore(text, type)

                    // If this is the best result so far for this type, save it
                    if (confidence > bestConfidence) {
                        bestText = text
                        bestConfidence = confidence
                    }
                }

                // If we found a good result for this type, add it
                if (bestText.isNotBlank() && bestConfidence > 0.3f) {
                    // Apply post-processing to clean up the text
                    val processedText = postProcessText(bestText, type)
                    result[type] = processedText
                    confidenceScores[type] = bestConfidence
                    Log.d(TAG, "Recognized $type: $processedText (confidence: $bestConfidence)")
                }
            }

            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error recognizing elements", e)
            return@withContext emptyMap<String, String>()
        }
    }

    /**
     * Preprocess image based on element type
     */
    private fun preprocessImageForType(bitmap: Bitmap, type: String): Bitmap {
        try {
            // Create a mutable copy of the bitmap
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val width = mutableBitmap.width
            val height = mutableBitmap.height

            // Create a canvas to draw on the bitmap
            val canvas = Canvas(mutableBitmap)

            // Create a paint object for drawing
            val paint = Paint()

            when (type) {
                "store" -> {
                    // For store names, enhance contrast and sharpen
                    val pixels = IntArray(width * height)
                    mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Apply contrast enhancement
                    val factor = 1.5f  // Contrast factor
                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val a = Color.alpha(pixel)
                        var r = Color.red(pixel)
                        var g = Color.green(pixel)
                        var b = Color.blue(pixel)

                        // Apply contrast formula
                        r = (((r / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)
                        g = (((g / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)
                        b = (((b / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)

                        pixels[i] = Color.argb(a, r, g, b)
                    }

                    mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                }

                "code" -> {
                    // For coupon codes, binarize and remove noise
                    val pixels = IntArray(width * height)
                    mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Convert to grayscale and apply threshold
                    val threshold = 128
                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val a = Color.alpha(pixel)
                        val r = Color.red(pixel)
                        val g = Color.green(pixel)
                        val b = Color.blue(pixel)

                        // Convert to grayscale
                        val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                        // Apply threshold
                        val binarized = if (gray > threshold) 255 else 0

                        pixels[i] = Color.argb(a, binarized, binarized, binarized)
                    }

                    mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

                    // Draw a border around the image to help Tesseract
                    paint.color = Color.BLACK
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
                }

                "amount" -> {
                    // For amounts, enhance digits and symbols
                    val pixels = IntArray(width * height)
                    mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Apply adaptive threshold
                    val blockSize = 15
                    val c = 5

                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            val i = y * width + x
                            val pixel = pixels[i]
                            val r = Color.red(pixel)
                            val g = Color.green(pixel)
                            val b = Color.blue(pixel)

                            // Convert to grayscale
                            val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                            // Calculate local mean
                            var sum = 0
                            var count = 0

                            for (by in -blockSize/2..blockSize/2) {
                                for (bx in -blockSize/2..blockSize/2) {
                                    val nx = x + bx
                                    val ny = y + by

                                    if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                        val nPixel = pixels[ny * width + nx]
                                        val nGray = (0.299 * Color.red(nPixel) + 0.587 * Color.green(nPixel) + 0.114 * Color.blue(nPixel)).toInt()
                                        sum += nGray
                                        count++
                                    }
                                }
                            }

                            val mean = if (count > 0) sum / count else 0

                            // Apply threshold
                            val binarized = if (gray < mean - c) 0 else 255

                            pixels[i] = Color.argb(Color.alpha(pixel), binarized, binarized, binarized)
                        }
                    }

                    mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                }

                "expiry" -> {
                    // For expiry dates, enhance digits and date separators
                    val pixels = IntArray(width * height)
                    mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Apply sharpening
                    val kernel = floatArrayOf(
                        0f, -1f, 0f,
                        -1f, 5f, -1f,
                        0f, -1f, 0f
                    )

                    val result = IntArray(width * height)

                    for (y in 1 until height - 1) {
                        for (x in 1 until width - 1) {
                            val i = y * width + x

                            var sumR = 0f
                            var sumG = 0f
                            var sumB = 0f

                            for (ky in -1..1) {
                                for (kx in -1..1) {
                                    val pixel = pixels[(y + ky) * width + (x + kx)]
                                    val kernelIndex = (ky + 1) * 3 + (kx + 1)

                                    sumR += Color.red(pixel) * kernel[kernelIndex]
                                    sumG += Color.green(pixel) * kernel[kernelIndex]
                                    sumB += Color.blue(pixel) * kernel[kernelIndex]
                                }
                            }

                            val r = sumR.toInt().coerceIn(0, 255)
                            val g = sumG.toInt().coerceIn(0, 255)
                            val b = sumB.toInt().coerceIn(0, 255)

                            result[i] = Color.argb(Color.alpha(pixels[i]), r, g, b)
                        }
                    }

                    // Copy the border pixels
                    for (y in 0 until height) {
                        for (x in 0 until width) {
                            if (y == 0 || y == height - 1 || x == 0 || x == width - 1) {
                                result[y * width + x] = pixels[y * width + x]
                            }
                        }
                    }

                    mutableBitmap.setPixels(result, 0, width, 0, 0, width, height)
                }

                "description" -> {
                    // For descriptions, enhance text readability
                    val pixels = IntArray(width * height)
                    mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                    // Apply mild contrast enhancement
                    val factor = 1.2f
                    for (i in pixels.indices) {
                        val pixel = pixels[i]
                        val a = Color.alpha(pixel)
                        var r = Color.red(pixel)
                        var g = Color.green(pixel)
                        var b = Color.blue(pixel)

                        // Apply contrast formula
                        r = (((r / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)
                        g = (((g / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)
                        b = (((b / 255f - 0.5f) * factor + 0.5f) * 255f).toInt().coerceIn(0, 255)

                        pixels[i] = Color.argb(a, r, g, b)
                    }

                    mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                }
            }

            return mutableBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error preprocessing image for $type", e)
            return bitmap
        }
    }

    /**
     * Process image with specialized Tesseract configuration based on element type
     */
    private suspend fun processWithSpecializedConfig(bitmap: Bitmap, type: String): String {
        return when (type) {
            "store" -> {
                // For store names, use single line mode
                tesseractOCRHelper.configure(
                    segMode = TesseractOCRHelper.PageSegMode.SINGLE_LINE,
                    enablePreprocessing = false,  // We've already preprocessed
                    charWhitelist = "0123456789 &-."
                )
                tesseractOCRHelper.processImageFromBitmap(bitmap)
            }

            "code" -> {
                // For coupon codes, optimize for alphanumeric characters
                tesseractOCRHelper.configure(
                    segMode = TesseractOCRHelper.PageSegMode.SINGLE_WORD,
                    enablePreprocessing = false,
                    charWhitelist = "0123456789"
                )
                tesseractOCRHelper.processImageFromBitmap(bitmap).uppercase()
            }

            "amount" -> {
                // For amounts, optimize for currency and percentage symbols
                tesseractOCRHelper.configure(
                    segMode = TesseractOCRHelper.PageSegMode.SINGLE_LINE,
                    enablePreprocessing = false,
                    charWhitelist = "0123456789.,%₹$"
                )
                tesseractOCRHelper.processImageFromBitmap(bitmap)
            }

            "expiry" -> {
                // For expiry dates, optimize for date formats
                tesseractOCRHelper.configure(
                    segMode = TesseractOCRHelper.PageSegMode.SINGLE_LINE,
                    enablePreprocessing = false,
                    charWhitelist = "0123456789/-."
                )
                tesseractOCRHelper.processImageFromBitmap(bitmap)
            }

            "description" -> {
                // For descriptions, use auto segmentation
                tesseractOCRHelper.configure(
                    segMode = TesseractOCRHelper.PageSegMode.AUTO,
                    enablePreprocessing = false,
                    charWhitelist = "0123456789 .,;:!?%₹$()-_"
                )
                tesseractOCRHelper.processImageFromBitmap(bitmap)
            }

            else -> {
                // Default configuration
                tesseractOCRHelper.processImageFromBitmap(bitmap)
            }
        }
    }

    /**
     * Calculate confidence score based on text quality and validation
     */
    private fun calculateConfidenceScore(text: String, type: String): Float {
        if (text.isBlank()) {
            return 0.0f
        }

        // Base score starts at 0.5
        var score = 0.5f

        when (type) {
            "store" -> {
                // Store names should be capitalized words
                if (text.split(" ").any { it.length > 1 && it[0].isUpperCase() }) {
                    score += 0.2f
                }

                // Store names shouldn't be too long
                if (text.length < 20) {
                    score += 0.1f
                }

                // Store names shouldn't contain too many numbers
                if (text.count { it.isDigit() } < text.length / 3) {
                    score += 0.1f
                }

                // Common store name patterns
                if (text.matches(Regex("(?i).*(store|shop|mart|market|outlet).*"))) {
                    score += 0.1f
                }
            }

            "code" -> {
                // Coupon codes are typically uppercase alphanumeric
                if (text.all { it.isLetterOrDigit() || it.isWhitespace() }) {
                    score += 0.2f
                }

                // Coupon codes usually have a minimum length
                if (text.replace(" ", "").length >= 5) {
                    score += 0.1f
                }

                // Coupon codes often have a mix of letters and numbers
                if (text.any { it.isLetter() } && text.any { it.isDigit() }) {
                    score += 0.1f
                }

                // Coupon codes shouldn't contain too many special characters
                if (text.count { !it.isLetterOrDigit() && !it.isWhitespace() } <= 1) {
                    score += 0.1f
                }
            }

            "amount" -> {
                // Amounts typically contain digits
                if (text.any { it.isDigit() }) {
                    score += 0.2f
                }

                // Amounts often contain currency symbols or percentage
                if (text.contains(Regex("[₹$%]"))) {
                    score += 0.2f
                }

                // Common amount patterns
                if (text.matches(Regex("(?i).*(off|discount|cashback|save).*"))) {
                    score += 0.1f
                }

                // Check for percentage pattern
                if (text.matches(Regex(".*\\d+\\s*%.*"))) {
                    score += 0.2f
                }

                // Check for currency pattern
                if (text.matches(Regex(".*(₹|Rs\\.?)\\s*\\d+.*"))) {
                    score += 0.2f
                }
            }

            "expiry" -> {
                // Expiry dates typically contain digits
                if (text.any { it.isDigit() }) {
                    score += 0.2f
                }

                // Expiry dates often contain date separators
                if (text.contains(Regex("[/.-]"))) {
                    score += 0.1f
                }

                // Common expiry date patterns
                if (text.matches(Regex("(?i).*(expir|valid|till|until).*"))) {
                    score += 0.2f
                }

                // Check for date pattern
                if (text.matches(Regex(".*\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}.*"))) {
                    score += 0.3f
                }
            }

            "description" -> {
                // Descriptions are typically longer text
                if (text.length > 10) {
                    score += 0.1f
                }

                // Descriptions often contain multiple words
                if (text.split(" ").size > 3) {
                    score += 0.1f
                }

                // Descriptions shouldn't be just numbers
                if (text.any { it.isLetter() }) {
                    score += 0.1f
                }

                // Common description patterns
                if (text.matches(Regex("(?i).*(get|use|apply|offer|deal|discount).*"))) {
                    score += 0.1f
                }
            }
        }

        // Cap the score at 1.0
        return score.coerceAtMost(1.0f)
    }

    /**
     * Post-process text based on element type
     */
    private fun postProcessText(text: String, type: String): String {
        if (text.isBlank()) {
            return text
        }

        return when (type) {
            "store" -> {
                // Clean up store name
                text.trim()
                    .replace(Regex("\\s+"), " ")  // Normalize whitespace
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { word ->
                        if (word.length > 1) {
                            word[0].uppercase() + word.substring(1).lowercase()
                        } else {
                            word.uppercase()
                        }
                    }
            }

            "code" -> {
                // Clean up coupon code
                text.trim()
                    .replace(Regex("[^A-Z0-9]"), "")  // Remove non-alphanumeric characters
                    .uppercase()
            }

            "amount" -> {
                // Clean up amount
                var processed = text.trim()
                    .replace(Regex("\\s+"), " ")  // Normalize whitespace

                // Ensure proper spacing around percentage symbol
                processed = processed.replace(Regex("(\\d)%"), "$1 %")

                // Ensure proper spacing around currency symbols
                processed = processed.replace(Regex("(₹|Rs\\.?)(\\d)"), "$1 $2")

                processed
            }

            "expiry" -> {
                // Clean up expiry date
                var processed = text.trim()
                    .replace(Regex("\\s+"), " ")  // Normalize whitespace

                // Try to extract date if it contains a date pattern
                val datePattern = Regex("(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})")
                val dateMatcher = datePattern.find(processed)

                if (dateMatcher != null) {
                    processed = dateMatcher.groupValues[1]
                }

                processed
            }

            "description" -> {
                // Clean up description
                text.trim()
                    .replace(Regex("\\s+"), " ")  // Normalize whitespace
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString() }  // Capitalize first letter
            }

            else -> text.trim()
        }
    }

    /**
     * Scale patterns to match the bitmap dimensions
     */
    private fun scalePatterns(width: Int, height: Int): Map<String, List<Rect>> {
        val result = mutableMapOf<String, MutableList<Rect>>()

        // Reference dimensions from training data
        val refWidth = 1080
        val refHeight = 1920

        // Scale factors
        val scaleX = width.toFloat() / refWidth
        val scaleY = height.toFloat() / refHeight

        for ((type, rects) in patterns) {
            result[type] = mutableListOf()

            for (rect in rects) {
                val scaledRect = Rect(
                    (rect.left * scaleX).toInt(),
                    (rect.top * scaleY).toInt(),
                    (rect.right * scaleX).toInt(),
                    (rect.bottom * scaleY).toInt()
                )

                result[type]!!.add(scaledRect)
            }
        }

        return result
    }

    /**
     * Ensure a rectangle is within the bounds of the bitmap
     */
    private fun ensureRectWithinBounds(rect: Rect, width: Int, height: Int): Rect {
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        val right = rect.right.coerceIn(left + 1, width)
        val bottom = rect.bottom.coerceIn(top + 1, height)

        return Rect(left, top, right, bottom)
    }

    /**
     * Convert recognized elements to CouponInfo
     */
    fun convertToCouponInfo(elements: Map<String, String>): CouponInfo {
        val storeName = elements["store"] ?: ""
        val description = elements["description"] ?: ""
        val expiryDateStr = elements["expiry"]
        val redeemCode = elements["code"]
        val amountStr = elements["amount"]

        // Parse expiry date
        val expiryDate = if (expiryDateStr != null) {
            parseExpiryDate(expiryDateStr)
        } else {
            null
        }

        // Parse cashback amount
        val cashbackAmount = if (amountStr != null) {
            parseCashbackAmount(amountStr)
        } else {
            null
        }

        // Determine discount type
        val discountType = if (amountStr != null && amountStr.contains("%")) {
            "PERCENTAGE"
        } else {
            "AMOUNT"
        }

        return CouponInfo(
            storeName = storeName,
            description = description,
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode,
            discountType = discountType
        )
    }

    /**
     * Parse expiry date from string
     */
    private fun parseExpiryDate(dateStr: String): Date? {
        val textExtractor = TextExtractor()
        return textExtractor.parseExpiryDate(dateStr)
    }

    /**
     * Parse cashback amount from string
     */
    private fun parseCashbackAmount(amountStr: String): Double? {
        return try {
            when {
                // Percentage discount
                amountStr.contains("%") -> {
                    val percentStr = amountStr.replace("[^0-9.]".toRegex(), "")
                    percentStr.toDoubleOrNull()
                }
                // Fixed amount discount
                amountStr.contains("₹") || amountStr.contains("Rs") -> {
                    val amountNumStr = amountStr.replace("[^0-9.]".toRegex(), "")
                    amountNumStr.toDoubleOrNull()
                }
                // Try to parse as a number
                else -> {
                    amountStr.replace("[^0-9.]".toRegex(), "").toDoubleOrNull()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing cashback amount: $amountStr", e)
            null
        }
    }
}

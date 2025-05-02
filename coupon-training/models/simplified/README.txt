
Coupon Recognition Model

This directory contains a simplified model for coupon recognition.

Files:
- coupon_patterns.txt: Contains patterns for recognizing different elements in coupons

To use this model in your Android app:
1. Copy the coupon_patterns.txt file to your app's assets directory
2. Create a CouponPatternRecognizer class that uses these patterns to identify regions in coupon images
3. Use the existing Tesseract OCR engine to extract text from these regions

Example implementation:

```kotlin
class CouponPatternRecognizer(context: Context) {
    private val patterns: Map<String, List<Rect>> = loadPatterns(context)

    private fun loadPatterns(context: Context): Map<String, List<Rect>> {
        val result = mutableMapOf<String, MutableList<Rect>>()

        try {
            val inputStream = context.assets.open("coupon_patterns.txt")
            val reader = inputStream.bufferedReader()

            var currentType = ""
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
        } catch (e: Exception) {
            Log.e("CouponPatternRecognizer", "Error loading patterns", e)
        }

        return result
    }

    fun recognizeElements(bitmap: Bitmap): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val tesseractOCR = TesseractOCRHelper(context)

        // Initialize Tesseract
        tesseractOCR.initialize()

        // Process each pattern type
        for ((type, rects) in patterns) {
            for (rect in rects) {
                // Extract region from bitmap
                val regionBitmap = Bitmap.createBitmap(
                    bitmap,
                    rect.left,
                    rect.top,
                    rect.width(),
                    rect.height()
                )

                // Process with Tesseract
                val text = tesseractOCR.processImageFromBitmap(regionBitmap)

                // If we got text and this type isn't in the result yet, add it
                if (text.isNotBlank() && !result.containsKey(type)) {
                    result[type] = text
                }
            }
        }

        return result
    }
}
```

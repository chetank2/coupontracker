#!/usr/bin/env python3
import os
import json
import argparse
from pathlib import Path

def create_coupon_patterns_file(annotated_dir, output_dir):
    """Create a patterns file for coupon recognition"""
    # Ensure output directory exists
    os.makedirs(output_dir, exist_ok=True)

    # Get all annotation files
    annotation_paths = list(Path(annotated_dir).glob("*.json"))

    if not annotation_paths:
        print(f"No annotation files found in {annotated_dir}")
        return False

    print(f"Found {len(annotation_paths)} annotation files")

    # Extract patterns from annotations
    store_patterns = set()
    description_patterns = set()
    expiry_patterns = set()
    code_patterns = set()
    amount_patterns = set()

    for annotation_path in annotation_paths:
        try:
            # Load the annotation
            with open(annotation_path, 'r') as f:
                annotation = json.load(f)

            # Extract patterns from annotations
            if 'annotations' in annotation:
                for region in annotation['annotations']:
                    if 'type' not in region or not all(k in region for k in ['left', 'top', 'width', 'height']):
                        continue

                    # Convert from left,top,width,height to left,top,right,bottom
                    left = region['left']
                    top = region['top']
                    right = left + region['width']
                    bottom = top + region['height']

                    pattern = f"{region['type']}:{left},{top},{right},{bottom}"

                    if region['type'] == 'store':
                        store_patterns.add(pattern)
                    elif region['type'] == 'description':
                        description_patterns.add(pattern)
                    elif region['type'] == 'expiry':
                        expiry_patterns.add(pattern)
                    elif region['type'] == 'code':
                        code_patterns.add(pattern)
                    elif region['type'] == 'amount':
                        amount_patterns.add(pattern)
            # Legacy format support
            elif 'regions' in annotation:
                for region_name, region_coords in annotation['regions'].items():
                    if not region_coords:
                        continue

                    if region_name == "store_name":
                        store_patterns.add(f"store:{region_coords[0]},{region_coords[1]},{region_coords[2]},{region_coords[3]}")
                    elif region_name == "coupon_code":
                        code_patterns.add(f"code:{region_coords[0]},{region_coords[1]},{region_coords[2]},{region_coords[3]}")
                    elif region_name == "expiry_date":
                        expiry_patterns.add(f"expiry:{region_coords[0]},{region_coords[1]},{region_coords[2]},{region_coords[3]}")
                    elif region_name == "amount":
                        amount_patterns.add(f"amount:{region_coords[0]},{region_coords[1]},{region_coords[2]},{region_coords[3]}")
        except Exception as e:
            print(f"Error processing {annotation_path}: {e}")

    # Create patterns file
    patterns_file = os.path.join(output_dir, "coupon_patterns.txt")
    with open(patterns_file, 'w') as f:
        f.write("# Coupon recognition patterns\n\n")

        f.write("# Store name patterns\n")
        for pattern in store_patterns:
            f.write(f"{pattern}\n")

        f.write("\n# Description patterns\n")
        for pattern in description_patterns:
            f.write(f"{pattern}\n")

        f.write("\n# Coupon code patterns\n")
        for pattern in code_patterns:
            f.write(f"{pattern}\n")

        f.write("\n# Expiry date patterns\n")
        for pattern in expiry_patterns:
            f.write(f"{pattern}\n")

        f.write("\n# Amount patterns\n")
        for pattern in amount_patterns:
            f.write(f"{pattern}\n")

    print(f"Created patterns file: {patterns_file}")

    # Create a README file
    readme_file = os.path.join(output_dir, "README.txt")
    with open(readme_file, 'w') as f:
        f.write("""
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
""")

    print(f"Created README file: {readme_file}")
    return True

def main():
    parser = argparse.ArgumentParser(description="Create a simplified model for coupon recognition")
    parser.add_argument("--annotated-dir", default="../data/annotated", help="Directory containing annotated images")
    parser.add_argument("--output-dir", default="../models/simplified", help="Directory to save the simplified model")

    args = parser.parse_args()

    # Create the simplified model
    success = create_coupon_patterns_file(args.annotated_dir, args.output_dir)

    if success:
        print("\n=== Simplified Model Creation Complete ===")
        print("Next steps:")
        print("1. Copy the coupon_patterns.txt file to your Android app's assets directory")
        print("2. Create a CouponPatternRecognizer class in your app")
        print("3. Use the existing Tesseract OCR engine with the pattern recognizer")
    else:
        print("\nSimplified model creation failed. Please check the error messages above.")

if __name__ == "__main__":
    main()

# Improved OCR Text Extraction for Coupon Tracker

This document outlines the enhanced text extraction system implemented in the Coupon Tracker app, specifically focused on optimizing the Google Cloud Vision API for extracting coupon information from payment app screenshots.

## System Components

The improved text extraction system consists of the following components:

### 1. Image Preprocessing Module (`ImagePreprocessor.kt`)

This component enhances image quality before sending to OCR engines:

- **Resizing**: Scales images to optimal dimensions for OCR processing
- **Advanced Image Enhancement**: Uses OpenCV for adaptive thresholding and noise reduction
- **Multiple Processing Variants**: Creates different image variants optimized for different types of text recognition
- **Edge Enhancement**: Improves detection of text against busy backgrounds
- **Contrast Normalization**: Makes text more distinct from background

### 2. Enhanced Google Vision API Integration (`EnhancedGoogleVisionHelper.kt`)

Provides an optimized interface to Google Cloud Vision API:

- **Multiple API Modes**: Tests both document text and regular text detection modes
- **Language Hints**: Provides appropriate language configuration for improved accuracy
- **Retry Mechanism**: Implements automatic retries with different preprocessing variants
- **Parallel Processing**: Tests different image variants simultaneously for best results

### 3. Field Extraction System (`CouponFieldExtractor.kt`)

Post-processes OCR text to extract structured information:

- **Specialized Regex Patterns**: Targeted patterns for common coupon formats
- **Confidence Scoring**: Assigns confidence levels to extracted fields
- **Multiple Extraction Attempts**: Tries different patterns when extraction fails
- **Merchant-Specific Logic**: Special handling for common coupon issuers (Myntra, etc.)
- **Field Validation**: Checks for likely valid coupon codes and amounts

### 4. Coupon Data Model (`CouponData.kt`)

Structured representation of coupon information:

- **Field Validation**: Verifies that extracted information is likely valid
- **Expiry Parsing**: Interprets various date formats for expiration verification
- **Confidence Tracking**: Maintains extraction quality metrics for each field
- **Overall Quality Score**: Provides a 0-100 score indicating extraction confidence

### 5. Centralized OCR Pipeline (`AdvancedOCRPipeline.kt`)

Orchestrates the entire extraction process:

- **Multi-Engine Approach**: Combines results from Google Vision API and ML Kit
- **Fallback Mechanisms**: Gracefully handles extraction failures
- **Best Result Selection**: Chooses the highest quality result from multiple attempts
- **Parallel Processing**: Runs different OCR methods concurrently for faster results

## Implementation Notes

### Dependencies

The system relies on several key dependencies:

- Google Cloud Vision API (v3.27.0)
- ML Kit Text Recognition (v16.0.0)
- OpenCV for Android (v4.8.0)
- RenderScript for image processing

### Image Preprocessing Techniques

1. **Adaptive Thresholding**: Improves text/background separation in varying light conditions
2. **Bilateral Filtering**: Reduces noise while preserving text edges
3. **Morphological Operations**: Cleans up text regions for better recognition
4. **Contrast Enhancement**: Makes text more distinct in low-contrast images

### Coupon Field Extraction Patterns

The system uses specialized regex patterns targeting:

1. **Merchant Names**: Identifies store logos and brand names
2. **Coupon Codes**: Recognizes various code formats and placements
3. **Discount Amounts**: Extracts numerical values with currency symbols (₹)
4. **Expiry Dates**: Identifies dates in multiple formats
5. **Descriptions**: Extracts offer details and conditions

## Usage Examples

```kotlin
// Initialize the pipeline with context and API key
val ocrPipeline = AdvancedOCRPipeline(
    context = context,
    googleCloudVisionApiKey = apiKey
)

// Process an image URI
val couponData = ocrPipeline.processCouponImage(imageUri)

// Check extraction quality
if (couponData.extractionScore > 70) {
    // High confidence result
    saveCoupon(couponData)
} else {
    // Low confidence - ask user to verify
    promptUserVerification(couponData)
}
```

## Performance Considerations

- Image preprocessing is optimized for mid-range Android devices
- API calls are made efficiently to minimize data usage
- Multiple preprocessing variants are only used when initial extraction fails
- OpenCV operations fall back to standard Android image processing when not available

## Future Improvements

1. **Merchant-Specific Models**: Train specialized extraction models for major coupon issuers
2. **AI-Assisted Field Extraction**: Use AI to improve extraction of unstructured fields
3. **User Feedback Loop**: Learn from user corrections to improve future extractions
4. **On-Device Text Recognition**: Reduce API dependency for common coupon formats 
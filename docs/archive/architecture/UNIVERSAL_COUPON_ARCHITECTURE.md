# Universal Coupon Extraction Architecture

## Current Problem: Brand-Specific Hardcoding Anti-Pattern

Our current system has fallen into the trap of hardcoding rules for each brand:
- 10+ hardcoded brand patterns in BrandAwareCouponValidator
- Brand-specific regex patterns in EnhancedOCRHelper  
- Maintenance nightmare: each new app requires code changes

## Universal Solution: Self-Learning Pattern Recognition

### 1. Pattern Learning Engine
Instead of hardcoding patterns, learn them from data:

```kotlin
class UniversalPatternLearner {
    // Learn patterns from successful extractions
    fun learnFromSuccess(extractedData: CouponData, originalText: String) {
        patternDatabase.recordPattern(
            field = "code", 
            pattern = extractPattern(extractedData.code, originalText),
            confidence = calculateConfidence(extractedData)
        )
    }
    
    // Apply learned patterns to new text
    fun extractUsingLearnedPatterns(text: String): List<ExtractionCandidate>
}
```

### 2. Universal Field Detection
Generic field detection based on visual and textual cues:

```kotlin
class UniversalFieldDetector {
    fun detectFields(image: Bitmap, ocrText: String): Map<FieldType, List<Candidate>> {
        return mapOf(
            FieldType.STORE_NAME to detectStoreNames(image, ocrText),
            FieldType.COUPON_CODE to detectCouponCodes(image, ocrText), 
            FieldType.AMOUNT to detectAmounts(image, ocrText),
            FieldType.EXPIRY to detectExpiryDates(image, ocrText)
        )
    }
    
    private fun detectCouponCodes(image: Bitmap, text: String): List<Candidate> {
        return listOf(
            // Visual cues: text in boxes, different fonts, highlighted
            detectVisuallyDistinctText(image),
            // Textual cues: alphanumeric patterns, context words
            detectAlphanumericPatterns(text),
            // Position cues: near "code:", "use:", "apply"
            detectContextualCodes(text)
        ).flatten()
    }
}
```

### 3. Self-Improving Confidence Scoring
Learn what works instead of hardcoding confidence rules:

```kotlin
class AdaptiveConfidenceScorer {
    private val successHistory = mutableMapOf<String, SuccessRate>()
    
    fun scoreCandidate(candidate: ExtractionCandidate): Double {
        val features = extractFeatures(candidate)
        return neuralNetwork.predict(features) // or simpler ML model
    }
    
    fun learnFromUserFeedback(candidate: ExtractionCandidate, wasCorrect: Boolean) {
        trainingData.add(extractFeatures(candidate) to wasCorrect)
        retrain()
    }
}
```

### 4. Template-Free Layout Understanding
Instead of hardcoded layouts, understand coupon structure:

```kotlin
class UniversalLayoutAnalyzer {
    fun analyzeCouponStructure(image: Bitmap): CouponLayout {
        return CouponLayout(
            logoRegion = detectLogoRegion(image),
            titleRegion = detectTitleRegion(image),  
            codeRegion = detectCodeRegion(image),
            amountRegion = detectAmountRegion(image),
            expiryRegion = detectExpiryRegion(image),
            termsRegion = detectTermsRegion(image)
        )
    }
    
    private fun detectCodeRegion(image: Bitmap): Region? {
        // Use visual cues: boxes, highlighting, distinct fonts
        // Use positional cues: center, bottom, after "code:"
        // Use ML: trained on thousands of coupon layouts
    }
}
```

### 5. Continuous Learning Pipeline
System improves automatically:

```kotlin
class ContinuousLearningPipeline {
    fun processNewCoupon(image: Bitmap, userCorrections: CouponData?) {
        // 1. Extract with current models
        val extraction = universalExtractor.extract(image)
        
        // 2. Learn from user corrections
        userCorrections?.let { correct ->
            patternLearner.learnFromCorrection(extraction, correct)
            confidenceScorer.updateFromFeedback(extraction, correct)
        }
        
        // 3. Update models (online learning or batch)
        if (shouldRetrain()) {
            retrain()
        }
    }
}
```

## Implementation Strategy

### Phase 1: Universal Base Layer (2 weeks)
1. Replace brand-specific patterns with generic pattern detection
2. Implement visual-cue based field detection  
3. Add pattern learning database
4. Create confidence scoring based on features, not hardcoded rules

### Phase 2: ML-Based Improvements (4 weeks)  
1. Train layout analyzer on diverse coupon images
2. Implement online learning for pattern recognition
3. Add user feedback loop for continuous improvement
4. Create A/B testing framework

### Phase 3: Self-Improving System (6 weeks)
1. Implement neural network for confidence scoring
2. Add automatic pattern discovery
3. Create synthetic coupon generator for training
4. Build evaluation metrics and monitoring

## Benefits

### Scalability
- ✅ New coupon apps work automatically (no code changes)
- ✅ System improves with each coupon processed
- ✅ Handles format changes automatically

### Maintainability  
- ✅ No hardcoded brand patterns to maintain
- ✅ Single universal algorithm
- ✅ Self-correcting system

### Accuracy
- ✅ Learns from real usage patterns
- ✅ Adapts to new coupon formats
- ✅ Improves over time instead of degrading

## Migration Plan

### Step 1: Identify Universal Patterns
```kotlin
// Instead of: brand-specific hardcoding
when (brand) {
    "myntra" -> hardcodedPattern
    "amazon" -> anotherHardcodedPattern
}

// Use: universal pattern recognition
val patterns = patternLearner.getRelevantPatterns(
    visualCues = detectVisualCues(image),
    textualCues = detectTextualCues(ocrText),
    contextCues = detectContextCues(ocrText)
)
```

### Step 2: Replace Hardcoded Rules
```kotlin
// Instead of: hardcoded confidence rules
val confidence = if (brand == "myntra" && code.startsWith("MY")) 0.9 else 0.5

// Use: learned confidence scoring  
val confidence = confidenceScorer.score(
    candidate = code,
    context = extractionContext,
    visualFeatures = visualFeatures
)
```

### Step 3: Add Learning Loop
```kotlin
// After each extraction, learn and improve
extractionPipeline.onComplete { result ->
    // Learn from successful extractions
    if (result.confidence > 0.8) {
        patternLearner.recordSuccess(result)
    }
    
    // Learn from user corrections
    userInterface.onUserCorrection { correction ->
        patternLearner.learnFromCorrection(result, correction)
    }
}
```

This universal approach will:
1. **Scale automatically** to new coupon apps
2. **Improve continuously** from usage
3. **Reduce maintenance** burden dramatically
4. **Handle edge cases** better than hardcoded rules

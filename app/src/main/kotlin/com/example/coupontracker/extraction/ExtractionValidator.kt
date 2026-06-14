package com.example.coupontracker.extraction

import android.util.Log
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.util.DescriptionUtils

/**
 * Post-extraction validator for coupon data
 * Validates extracted coupons before returning to UI/database
 * Provides actionable feedback for low-quality extractions
 */
class ExtractionValidator(
    private val confidenceScorer: ConfidenceScorer = ConfidenceScorer()
) {
    
    companion object {
        private const val TAG = "ExtractionValidator"
        
        // Minimum confidence thresholds for acceptance
        private const val MIN_OVERALL_CONFIDENCE = 0.50f
        private const val MIN_STORE_NAME_CONFIDENCE = 0.60f
        private const val MIN_CODE_CONFIDENCE = 0.50f
    }
    
    /**
     * Comprehensive validation result with detailed feedback
     */
    data class DetailedValidationResult(
        val validationResult: ValidationResult,
        val fieldConfidences: Map<FieldType, FieldConfidence>,
        val extractionQuality: ExtractionQuality,
        val actionableRecommendations: List<String>
    )
    
    /**
     * Overall quality assessment
     */
    enum class ExtractionQuality {
        EXCELLENT,    // >=0.85 confidence, all fields extracted
        GOOD,         // >=0.70 confidence, required fields present
        ACCEPTABLE,   // >=0.50 confidence, minimal required fields
        POOR,         // <0.50 confidence or missing critical fields
        FAILED        // Unable to extract meaningful data
    }
    
    /**
     * Validate extracted coupon and build confidence map
     */
    fun validate(
        coupon: Coupon,
        fieldConfidenceMap: Map<FieldType, FieldConfidence>? = null
    ): DetailedValidationResult {
        
        Log.d(TAG, "Validating coupon: store='${coupon.storeName}', code='${coupon.redeemCode}'")
        
        // Build confidence map if not provided
        val confidences = fieldConfidenceMap ?: buildConfidenceMap(coupon)
        
        // Validate required fields
        val validationResult = confidenceScorer.validateRequiredFields(confidences)
        
        // Assess overall quality
        val quality = assessExtractionQuality(validationResult, confidences)
        
        // Generate recommendations
        val recommendations = generateRecommendations(validationResult, confidences, coupon)
        
        Log.d(TAG, "Validation complete: quality=$quality, confidence=${validationResult.overallConfidence}, action=${validationResult.suggestedAction}")
        
        return DetailedValidationResult(
            validationResult = validationResult,
            fieldConfidences = confidences,
            extractionQuality = quality,
            actionableRecommendations = recommendations
        )
    }
    
    /**
     * Build confidence map from Coupon object
     */
    private fun buildConfidenceMap(coupon: Coupon): Map<FieldType, FieldConfidence> {
        val confidences = mutableMapOf<FieldType, FieldConfidence>()
        
        // Score each field that has a value
        if (coupon.storeName.isNotBlank() && coupon.storeName != com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE) {
            confidences[FieldType.STORE_NAME] = confidenceScorer.scoreField(
                FieldType.STORE_NAME,
                coupon.storeName,
                com.example.coupontracker.universal.ExtractionContext()
            )
        }
        
        if (coupon.redeemCode?.isNotBlank() == true && coupon.redeemCode != "N/A") {
            confidences[FieldType.COUPON_CODE] = confidenceScorer.scoreField(
                FieldType.COUPON_CODE,
                coupon.redeemCode!!,
                com.example.coupontracker.universal.ExtractionContext()
            )
        }
        
        if (coupon.description.isNotBlank() && coupon.description != "No description") {
            confidences[FieldType.DESCRIPTION] = confidenceScorer.scoreField(
                FieldType.DESCRIPTION,
                coupon.description,
                com.example.coupontracker.universal.ExtractionContext()
            )
        }
        
        DescriptionUtils.extractCashbackLine(coupon.description)?.let { cashbackLine ->
            confidences[FieldType.AMOUNT] = confidenceScorer.scoreField(
                FieldType.AMOUNT,
                cashbackLine,
                com.example.coupontracker.universal.ExtractionContext()
            )
        }
        
        if (coupon.expiryDate != null) {
            confidences[FieldType.EXPIRY_DATE] = confidenceScorer.scoreField(
                FieldType.EXPIRY_DATE,
                coupon.expiryDate.toString(),
                com.example.coupontracker.universal.ExtractionContext()
            )
        }
        
        return confidences
    }
    
    /**
     * Assess overall extraction quality
     */
    private fun assessExtractionQuality(
        validationResult: ValidationResult,
        confidences: Map<FieldType, FieldConfidence>
    ): ExtractionQuality {
        val confidence = validationResult.overallConfidence
        val hasRequiredFields = validationResult.missingRequiredFields.isEmpty()
        val hasStoreAndCode = confidences.containsKey(FieldType.STORE_NAME) && 
                             confidences.containsKey(FieldType.COUPON_CODE)
        
        return when {
            confidence >= 0.85f && hasRequiredFields && hasStoreAndCode -> ExtractionQuality.EXCELLENT
            confidence >= 0.70f && hasRequiredFields -> ExtractionQuality.GOOD
            confidence >= 0.50f && hasRequiredFields -> ExtractionQuality.ACCEPTABLE
            confidence >= 0.30f -> ExtractionQuality.POOR
            else -> ExtractionQuality.FAILED
        }
    }
    
    /**
     * Generate actionable recommendations for improvement
     */
    private fun generateRecommendations(
        validationResult: ValidationResult,
        confidences: Map<FieldType, FieldConfidence>,
        coupon: Coupon
    ): List<String> {
        val recommendations = mutableListOf<String>()
        
        // Missing required fields
        if (validationResult.missingRequiredFields.isNotEmpty()) {
            recommendations.add(
                "Missing required fields: ${validationResult.missingRequiredFields.joinToString()}"
            )
        }
        
        // Low confidence on store name
        confidences[FieldType.STORE_NAME]?.let { storeConfidence ->
            if (storeConfidence.confidence < MIN_STORE_NAME_CONFIDENCE) {
                recommendations.add(
                    "Store name '${coupon.storeName}' has low confidence (${String.format("%.2f", storeConfidence.confidence)}). " +
                    "Consider manual verification. Notes: ${storeConfidence.validationNotes.joinToString()}"
                )
            }
        }
        
        // Low confidence on coupon code
        confidences[FieldType.COUPON_CODE]?.let { codeConfidence ->
            if (codeConfidence.confidence < MIN_CODE_CONFIDENCE) {
                recommendations.add(
                    "Coupon code '${coupon.redeemCode}' has low confidence (${String.format("%.2f", codeConfidence.confidence)}). " +
                    "Verify manually. Notes: ${codeConfidence.validationNotes.joinToString()}"
                )
            }
        }
        
        // Strategy recommendations
        when (validationResult.suggestedAction) {
            ValidationAction.RETRY_OCR -> {
                recommendations.add("Consider retrying with different OCR engine or image preprocessing")
            }
            ValidationAction.RETRY_STRATEGY -> {
                recommendations.add("Try different extraction strategy (HYBRID or LLM_FIRST)")
            }
            ValidationAction.REJECT -> {
                recommendations.add("Extraction quality too low. Image may be unclear or not a coupon")
            }
            ValidationAction.REVIEW -> {
                recommendations.add("Manual review recommended before using coupon")
            }
            else -> {}
        }
        
        // Check for common issues
        if (coupon.storeName == com.example.coupontracker.data.model.Coupon.Defaults.UNKNOWN_STORE || coupon.storeName.length < 3) {
            recommendations.add("Store name appears to be placeholder or too short")
        }
        
        if (coupon.redeemCode.isNullOrBlank() || coupon.redeemCode == "N/A") {
            recommendations.add("No coupon code extracted - may be an offer without a code")
        }
        
        if (coupon.expiryDate == null) {
            recommendations.add("No expiry date found - coupon validity unknown")
        }
        
        return recommendations
    }
    
    /**
     * Quick check if coupon meets minimum quality threshold
     */
    fun meetsMinimumQuality(coupon: Coupon, threshold: Float = MIN_OVERALL_CONFIDENCE): Boolean {
        val result = validate(coupon)
        return result.validationResult.overallConfidence >= threshold &&
               result.extractionQuality != ExtractionQuality.FAILED
    }
    
    /**
     * Check if coupon should be flagged for manual review
     */
    fun requiresManualReview(coupon: Coupon): Boolean {
        val result = validate(coupon)
        return result.validationResult.suggestedAction == ValidationAction.REVIEW ||
               result.extractionQuality == ExtractionQuality.POOR
    }
}

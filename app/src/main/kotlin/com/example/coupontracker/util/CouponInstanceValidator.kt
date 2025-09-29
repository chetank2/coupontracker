package com.example.coupontracker.util

import android.graphics.RectF
import android.util.Log
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.ml.FieldDetection
import com.example.coupontracker.ml.FieldType

/**
 * Validates coupon instances to ensure proper tile-to-coupon mapping
 * Addresses the issue where field extractions mix data from different coupons
 */
object CouponInstanceValidator {
    private const val TAG = "CouponInstanceValidator"
    
    // Threshold for considering a field as "inside" a coupon boundary
    private const val FIELD_CONTAINMENT_THRESHOLD = 0.8f
    
    // Maximum allowed overlap between coupon instances
    private const val MAX_COUPON_OVERLAP = 0.3f
    
    /**
     * Validate and clean coupon instances to prevent cross-contamination
     */
    fun validateAndCleanInstances(instances: List<CouponInstance>): List<CouponInstance> {
        if (instances.size <= 1) {
            // Single coupon - no cross-contamination possible
            return instances
        }
        
        Log.d(TAG, "Validating ${instances.size} coupon instances for cross-contamination")
        
        val validatedInstances = mutableListOf<CouponInstance>()
        
        for ((index, instance) in instances.withIndex()) {
            try {
                // 1. Filter fields that belong to this coupon instance
                val ownFields = filterFieldsForCoupon(instance, instances)
                
                // 2. Validate field distribution
                val validationResult = validateFieldDistribution(instance, ownFields)
                
                // 3. Create clean instance
                val cleanInstance = instance.copy(
                    id = "coupon_validated_${System.currentTimeMillis()}_$index",
                    fields = ownFields
                )
                
                if (validationResult.isValid) {
                    validatedInstances.add(cleanInstance)
                    Log.d(TAG, "Coupon $index validated: ${ownFields.size} fields, confidence: ${validationResult.confidence}")
                } else {
                    Log.w(TAG, "Coupon $index rejected: ${validationResult.reason}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error validating coupon instance $index", e)
            }
        }
        
        Log.i(TAG, "Validation complete: ${validatedInstances.size}/${instances.size} instances passed")
        return validatedInstances
    }
    
    /**
     * Filter fields to only include those that belong to this specific coupon
     */
    private fun filterFieldsForCoupon(
        targetCoupon: CouponInstance,
        allCoupons: List<CouponInstance>
    ): List<FieldDetection> {
        
        val ownFields = mutableListOf<FieldDetection>()
        val targetBounds = targetCoupon.boundingBox
        
        for (field in targetCoupon.fields) {
            // Calculate how much of this field is contained within the target coupon
            val containmentRatio = calculateContainmentRatio(field.boundingBox, targetBounds)
            
            // Check if this field is closer to any other coupon
            val isClosestToTarget = isFieldClosestToCoupon(field, targetCoupon, allCoupons)
            
            // Include field if it's mostly contained and closest to this coupon
            if (containmentRatio >= FIELD_CONTAINMENT_THRESHOLD && isClosestToTarget) {
                ownFields.add(field)
            } else {
                Log.v(TAG, "Filtered out field '${field.fieldType}' (containment: $containmentRatio, closest: $isClosestToTarget)")
            }
        }
        
        return ownFields
    }
    
    /**
     * Calculate what ratio of field is contained within coupon bounds
     */
    private fun calculateContainmentRatio(fieldBounds: RectF, couponBounds: RectF): Float {
        // Calculate intersection
        val intersection = RectF()
        if (!intersection.setIntersect(fieldBounds, couponBounds)) {
            return 0f
        }
        
        // Calculate areas
        val fieldArea = fieldBounds.width() * fieldBounds.height()
        val intersectionArea = intersection.width() * intersection.height()
        
        return if (fieldArea > 0) intersectionArea / fieldArea else 0f
    }
    
    /**
     * Check if field is closest to the target coupon vs other coupons
     */
    private fun isFieldClosestToCoupon(
        field: FieldDetection,
        targetCoupon: CouponInstance,
        allCoupons: List<CouponInstance>
    ): Boolean {
        
        val fieldCenter = RectF(field.boundingBox).let { 
            Pair(it.centerX(), it.centerY()) 
        }
        
        val targetDistance = calculateDistance(fieldCenter, targetCoupon.boundingBox)
        
        // Check if any other coupon is closer
        for (otherCoupon in allCoupons) {
            if (otherCoupon.id == targetCoupon.id) continue
            
            val otherDistance = calculateDistance(fieldCenter, otherCoupon.boundingBox)
            if (otherDistance < targetDistance) {
                return false
            }
        }
        
        return true
    }
    
    /**
     * Calculate distance from point to rectangle center
     */
    private fun calculateDistance(point: Pair<Float, Float>, rect: RectF): Float {
        val rectCenter = Pair(rect.centerX(), rect.centerY())
        val dx = point.first - rectCenter.first
        val dy = point.second - rectCenter.second
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
    
    /**
     * Validate that field distribution makes sense for a coupon
     */
    private fun validateFieldDistribution(
        instance: CouponInstance,
        fields: List<FieldDetection>
    ): ValidationResult {
        
        // Check minimum field count
        if (fields.isEmpty()) {
            return ValidationResult(false, 0f, "No fields detected")
        }
        
        // Check for essential field types
        val fieldTypes = fields.map { it.fieldType }.toSet()
        val hasCode = fieldTypes.contains(FieldType.CODE_REGION)
        val hasStore = fieldTypes.contains(FieldType.APP_REGION)
        val hasAmount = fieldTypes.contains(FieldType.BENEFIT_REGION)
        
        // Calculate confidence based on field presence and distribution
        var confidence = 0.5f
        
        if (hasCode) confidence += 0.2f
        if (hasStore) confidence += 0.15f
        if (hasAmount) confidence += 0.15f
        
        // Bonus for good field count
        when (fields.size) {
            in 3..6 -> confidence += 0.1f
            in 1..2 -> confidence -= 0.1f
            0 -> confidence = 0f
        }
        
        // Check for field clustering (fields should be reasonably close to each other)
        val clusteringScore = calculateClusteringScore(fields)
        confidence += clusteringScore * 0.1f
        
        val isValid = confidence >= 0.6f
        val reason = if (isValid) "Valid distribution" else "Low confidence distribution"
        
        return ValidationResult(isValid, confidence, reason)
    }
    
    /**
     * Calculate how well fields cluster together (good coupons have clustered fields)
     */
    private fun calculateClusteringScore(fields: List<FieldDetection>): Float {
        if (fields.size <= 1) return 1f
        
        // Calculate average distance between all field pairs
        var totalDistance = 0f
        var pairCount = 0
        
        for (i in fields.indices) {
            for (j in i + 1 until fields.size) {
                val field1Center = fields[i].boundingBox.let { Pair(it.centerX(), it.centerY()) }
                val field2Center = fields[j].boundingBox.let { Pair(it.centerX(), it.centerY()) }
                
                val distance = calculateDistance(field1Center, RectF().apply {
                    set(field2Center.first, field2Center.second, field2Center.first, field2Center.second)
                })
                
                totalDistance += distance
                pairCount++
            }
        }
        
        val averageDistance = if (pairCount > 0) totalDistance / pairCount else 0f
        
        // Convert to score (lower distance = higher score)
        // Normalize based on typical coupon dimensions
        val normalizedDistance = averageDistance / 500f // Assume 500px is max reasonable distance
        return kotlin.math.max(0f, 1f - normalizedDistance)
    }
    
    /**
     * Detect overlapping coupon instances that might indicate detection errors
     */
    fun detectOverlappingCoupons(instances: List<CouponInstance>): List<OverlapWarning> {
        val warnings = mutableListOf<OverlapWarning>()
        
        for (i in instances.indices) {
            for (j in i + 1 until instances.size) {
                val overlap = calculateOverlapRatio(instances[i].boundingBox, instances[j].boundingBox)
                
                if (overlap > MAX_COUPON_OVERLAP) {
                    warnings.add(
                        OverlapWarning(
                            coupon1Id = instances[i].id,
                            coupon2Id = instances[j].id,
                            overlapRatio = overlap
                        )
                    )
                }
            }
        }
        
        return warnings
    }
    
    /**
     * Calculate overlap ratio between two rectangles
     */
    private fun calculateOverlapRatio(rect1: RectF, rect2: RectF): Float {
        val intersection = RectF()
        if (!intersection.setIntersect(rect1, rect2)) {
            return 0f
        }
        
        val intersectionArea = intersection.width() * intersection.height()
        val rect1Area = rect1.width() * rect1.height()
        val rect2Area = rect2.width() * rect2.height()
        val unionArea = rect1Area + rect2Area - intersectionArea
        
        return if (unionArea > 0) intersectionArea / unionArea else 0f
    }
}

/**
 * Validation result for a coupon instance
 */
data class ValidationResult(
    val isValid: Boolean,
    val confidence: Float,
    val reason: String
)

/**
 * Warning about overlapping coupon detections
 */
data class OverlapWarning(
    val coupon1Id: String,
    val coupon2Id: String,
    val overlapRatio: Float
)

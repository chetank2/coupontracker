package com.example.coupontracker.model

import android.util.Log
import com.example.coupontracker.util.ConfidenceLevel
import com.example.coupontracker.util.ExtractedField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Model representing structured coupon data with validation methods
 */
data class CouponData(
    val merchantName: String,
    val code: String?,
    val amount: String?,
    val expiryDate: String?,
    val description: String?,
    val terms: String?,
    // Confidence levels for each field
    val confidenceLevels: Map<String, ConfidenceLevel> = emptyMap(),
    // Overall extraction score (0-100)
    val extractionScore: Int = 0
) {
    companion object {
        private const val TAG = "CouponData"
        
        /**
         * Create a CouponData object from map of extracted fields
         */
        fun fromExtractedFields(fields: Map<String, ExtractedField>): CouponData {
            // Extract values
            val merchantName = fields["merchantName"]?.value ?: "Unknown Store"
            val code = fields["code"]?.value
            val amount = fields["amount"]?.value
            val expiryDate = fields["expiryDate"]?.value
            val description = fields["description"]?.value
            val terms = fields["terms"]?.value
            
            // Extract confidence levels
            val confidenceLevels = fields.mapValues { it.value.confidence }
            
            // Calculate overall extraction score
            val extractionScore = calculateExtractionScore(fields)
            
            return CouponData(
                merchantName = merchantName,
                code = code,
                amount = amount,
                expiryDate = expiryDate,
                description = description,
                terms = terms,
                confidenceLevels = confidenceLevels,
                extractionScore = extractionScore
            )
        }
        
        /**
         * Calculate overall extraction score based on field confidence levels and presence
         * @return Score from 0-100
         */
        private fun calculateExtractionScore(fields: Map<String, ExtractedField>): Int {
            var score = 0
            
            // Required fields (merchant name and either code or amount)
            val hasMerchantName = fields.containsKey("merchantName")
            val hasCode = fields.containsKey("code")
            val hasAmount = fields.containsKey("amount")
            
            // Required fields get higher weight
            if (hasMerchantName) {
                score += when (fields["merchantName"]?.confidence) {
                    ConfidenceLevel.HIGH -> 30
                    ConfidenceLevel.MEDIUM -> 20
                    ConfidenceLevel.LOW -> 10
                    ConfidenceLevel.SYNTHETIC -> 5
                    null -> 0
                }
            }
            
            // Code confidence
            if (hasCode) {
                score += when (fields["code"]?.confidence) {
                    ConfidenceLevel.HIGH -> 25
                    ConfidenceLevel.MEDIUM -> 15
                    ConfidenceLevel.LOW -> 10
                    ConfidenceLevel.SYNTHETIC -> 5
                    null -> 0
                }
            }
            
            // Amount confidence
            if (hasAmount) {
                score += when (fields["amount"]?.confidence) {
                    ConfidenceLevel.HIGH -> 25
                    ConfidenceLevel.MEDIUM -> 15
                    ConfidenceLevel.LOW -> 10
                    ConfidenceLevel.SYNTHETIC -> 5
                    null -> 0
                }
            }
            
            // Expiry date confidence
            if (fields.containsKey("expiryDate")) {
                score += when (fields["expiryDate"]?.confidence) {
                    ConfidenceLevel.HIGH -> 10
                    ConfidenceLevel.MEDIUM -> 7
                    ConfidenceLevel.LOW -> 5
                    ConfidenceLevel.SYNTHETIC -> 2
                    null -> 0
                }
            }
            
            // Description confidence
            if (fields.containsKey("description")) {
                score += when (fields["description"]?.confidence) {
                    ConfidenceLevel.HIGH -> 10
                    ConfidenceLevel.MEDIUM -> 7
                    ConfidenceLevel.LOW -> 5
                    ConfidenceLevel.SYNTHETIC -> 2
                    null -> 0
                }
            }
            
            // Terms confidence
            if (fields.containsKey("terms")) {
                score += when (fields["terms"]?.confidence) {
                    ConfidenceLevel.HIGH -> 5
                    ConfidenceLevel.MEDIUM -> 3
                    ConfidenceLevel.LOW -> 2
                    ConfidenceLevel.SYNTHETIC -> 1
                    null -> 0
                }
            }
            
            // Special case: good to have at least code or amount
            if (!hasCode && !hasAmount) {
                score = (score * 0.7).toInt() // Reduce score if missing both
            }
            
            // Cap at 100
            return minOf(score, 100)
        }
    }
    
    /**
     * Check if this coupon has enough valid information to be useful
     */
    fun isValid(): Boolean {
        // Must have a merchant name
        if (merchantName.isBlank() || merchantName == "Unknown Store") {
            return false
        }
        
        // Must have either a code or an amount
        if (code.isNullOrBlank() && (amount.isNullOrBlank() || amount == "₹0")) {
            return false
        }
        
        return true
    }
    
    /**
     * Determine if the coupon is likely expired
     * @return true if likely expired, false if not, null if expiry unknown
     */
    fun isExpired(): Boolean? {
        if (expiryDate.isNullOrBlank()) {
            return null
        }
        
        return try {
            // Try parsing common date formats
            val dateFormats = listOf(
                "dd/MM/yyyy", "dd-MM-yyyy", "MM/dd/yyyy", "MM-dd-yyyy",
                "dd/MM/yy", "dd-MM-yy", "MM/dd/yy", "MM-dd-yy",
                "dd MMM yyyy", "MMM dd yyyy", "dd MMMM yyyy", "MMMM dd yyyy"
            )
            
            var parsed = false
            var expiryMillis = 0L
            
            for (format in dateFormats) {
                try {
                    val sdf = SimpleDateFormat(format, Locale.getDefault())
                    val date = sdf.parse(expiryDate)
                    if (date != null) {
                        expiryMillis = date.time
                        parsed = true
                        break
                    }
                } catch (e: Exception) {
                    // Try next format
                }
            }
            
            // If we found a valid date, check if it's expired
            if (parsed) {
                return System.currentTimeMillis() > expiryMillis
            }
            
            // Check for "valid for X days" format
            val daysPattern = "(\\d+)\\s+days".toRegex()
            val match = daysPattern.find(expiryDate)
            
            if (match != null) {
                val days = match.groupValues[1].toIntOrNull() ?: return null
                val now = Date().time
                val oneDay = TimeUnit.DAYS.toMillis(1)
                val daysInMillis = days * oneDay
                
                // We don't know when the coupon was issued, so assume recently (within 1 day)
                val approximateIssueDate = now - oneDay
                val approximateExpiryDate = approximateIssueDate + daysInMillis
                
                return now > approximateExpiryDate
            }
            
            null // Couldn't parse the date
        } catch (e: Exception) {
            Log.e(TAG, "Error checking expiry date", e)
            null
        }
    }
    
    /**
     * Get a numerical discount amount
     * @return Numeric value or null if not available
     */
    fun getNumericAmount(): Float? {
        if (amount.isNullOrBlank()) {
            return null
        }
        
        return try {
            // Extract numbers from the amount string
            val numberPattern = "(\\d+(?:\\.\\d+)?)".toRegex()
            val match = numberPattern.find(amount)
            
            match?.groupValues?.get(1)?.toFloatOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing numeric amount", e)
            null
        }
    }
    
    /**
     * Get a printable summary of the coupon
     */
    fun getSummary(): String {
        val parts = mutableListOf<String>()
        
        // Always include merchant name
        parts.add(merchantName)
        
        // Add discount amount if available
        if (!amount.isNullOrBlank() && amount != "₹0") {
            parts.add(amount)
        }
        
        // Add code if available
        if (!code.isNullOrBlank()) {
            parts.add("Code: $code")
        }
        
        // Add expiry if available
        if (!expiryDate.isNullOrBlank()) {
            parts.add("Expires: $expiryDate")
        }
        
        return parts.joinToString(" • ")
    }
} 
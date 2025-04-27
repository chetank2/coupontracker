package com.example.coupontracker.util

import android.util.Log
import java.io.Serializable
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Data class to hold extracted coupon information
 */
data class CouponInfo(
    val storeName: String = "",
    val description: String = "",
    val expiryDate: Date? = null,
    val cashbackAmount: Double? = null,
    val redeemCode: String? = null,
    val category: String? = null,
    val rating: String? = null,
    val status: String? = null,
    val discountType: String? = null // "PERCENTAGE" or "AMOUNT"
) : Serializable {
    override fun toString(): String {
        return "CouponInfo(storeName='$storeName', description='$description', " +
               "expiryDate=$expiryDate, cashbackAmount=$cashbackAmount, " +
               "discountType=$discountType, redeemCode=$redeemCode, category=$category, " +
               "rating=$rating, status=$status)"
    }
}

/**
 * Utility class to extract coupon information from text
 */
class TextExtractor {
    private val TAG = "TextExtractor"

    /**
     * Extract all coupon information from text
     * @param text The text to extract information from
     * @return CouponInfo object containing extracted information
     */
    suspend fun extractCouponInfo(text: String): CouponInfo = withContext(Dispatchers.Default) {
        extractCouponInfoSync(text)
    }
    
    /**
     * Synchronous version of extractCouponInfo
     * @param text The text to extract information from
     * @return CouponInfo object containing extracted information
     */
    fun extractCouponInfoSync(text: String): CouponInfo {
        Log.d(TAG, "Extracting coupon info from text: ${text.take(100)}...")
        
        val storeName = extractStoreName(text)
        val description = extractDescription(text)
        val expiryDate = extractExpiryDate(text)
        val cashbackAmount = extractCashbackAmount(text)
        val redeemCode = extractRedeemCode(text)
        val category = extractCategory(text)
        val rating = extractRating(text)
        val status = extractStatus(text)
        val discountType = extractDiscountType(text)
        
        val result = CouponInfo(
            storeName = storeName ?: "",
            description = description ?: "",
            expiryDate = expiryDate,
            cashbackAmount = cashbackAmount,
            redeemCode = redeemCode,
            category = category,
            rating = rating,
            status = status,
            discountType = discountType
        )
        
        Log.d(TAG, "Extracted coupon info: $result")
        return result
    }

    /**
     * Extract store name from text
     * @param text The text to extract from
     * @return The extracted store name or null if not found
     */
    fun extractStoreName(text: String): String? {
        // First check for specific store names we know
        val knownStores = listOf("Myntra", "ABHIBUS", "NEWMEE", "IXIGO", "BOAT", "XYXX")
        for (store in knownStores) {
            if (text.contains(store, ignoreCase = true)) {
                Log.d(TAG, "Found known store name: $store")
                return store
            }
        }
        
        // Look for "Brand:" pattern
        val brandPattern = Pattern.compile("(?i)Brand:\\s*([A-Za-z0-9]+)")
        val brandMatcher = brandPattern.matcher(text)
        if (brandMatcher.find()) {
            val brand = brandMatcher.group(1)
            Log.d(TAG, "Found brand from 'Brand:' pattern: $brand")
            return brand
        }
        
        // Look for all caps words that are likely to be store names
        val allCapsPattern = Pattern.compile("\\b([A-Z]{3,}\\b)")
        val matcher = allCapsPattern.matcher(text)
        
        while (matcher.find()) {
            val potentialName = matcher.group(1)
            // Skip common words that might be in all caps but aren't store names
            if (potentialName != null && !COMMON_WORDS.contains(potentialName.lowercase())) {
                Log.d(TAG, "Found store name from all caps: $potentialName")
                return potentialName
            }
        }
        
        // Try to find store names by common patterns
        val storePatterns = listOf(
            Pattern.compile("(?i)from\\s+([A-Za-z0-9]+)"),
            Pattern.compile("(?i)at\\s+([A-Za-z0-9]+)"),
            Pattern.compile("(?i)on\\s+([A-Za-z0-9]+)"),
            Pattern.compile("(?i)via\\s+([A-Za-z0-9]+)\\s+pay")
        )
        
        for (pattern in storePatterns) {
            val storeMatcher = pattern.matcher(text)
            if (storeMatcher.find()) {
                val name = storeMatcher.group(1)
                Log.d(TAG, "Found store name from pattern: $name")
                return name
            }
        }
        
        return null
    }
    
    /**
     * Extract description from text
     * @param text The text to extract from
     * @return The extracted description or null if not found
     */
    fun extractDescription(text: String): String? {
        // First, try to form a clean description using store name and discount info
        val storeName = extractStoreName(text) ?: ""
        val discountType = extractDiscountType(text)
        val cashbackAmount = extractCashbackAmount(text)
        
        if (storeName.isNotBlank() && cashbackAmount != null) {
            val amountStr = if (discountType == "PERCENTAGE") {
                "$cashbackAmount%"
            } else {
                "₹$cashbackAmount"
            }
            
            val discountPhrase = when {
                text.contains("cashback", ignoreCase = true) -> "$amountStr cashback"
                text.contains("flat", ignoreCase = true) -> "Flat $amountStr off"
                discountType == "PERCENTAGE" -> "Up to $amountStr off"
                else -> "$amountStr off"
            }
            
            val cleanDescription = "$storeName Coupon - $discountPhrase"
            Log.d(TAG, "Created clean description: $cleanDescription")
            return cleanDescription
        }
        
        // If we couldn't create a clean description, fall back to pattern matching
        
        // Look for "Offer:" pattern
        val offerPattern = Pattern.compile("(?i)Offer:\\s*(.+?)(?=\\n|$)")
        val offerMatcher = offerPattern.matcher(text)
        if (offerMatcher.find()) {
            val offer = offerMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Offer:' pattern: $offer")
            return offer
        }
        
        // Look for "You won X products at ₹Y + ₹Z cashback" pattern
        val wonProductsPattern = Pattern.compile("(?i)(You\\s+won\\s+\\d+\\s+products.+?cashback.+?)(?=\\n|$)")
        val wonProductsMatcher = wonProductsPattern.matcher(text)
        if (wonProductsMatcher.find()) {
            val desc = wonProductsMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'You won products' pattern: $desc")
            return desc
        }
        
        // Special case for coupons with "Get upto ₹X" pattern
        val getUptoPattern = Pattern.compile("(?i)(Get\\s+(?:up\\s+to|upto)\\s+(?:Rs\\.?|₹)\\d+(?:\\s+off)?)\\b")
        val getUptoMatcher = getUptoPattern.matcher(text)
        if (getUptoMatcher.find()) {
            val desc = getUptoMatcher.group(1)
            Log.d(TAG, "Found description from 'Get upto' pattern: $desc")
            return desc
        }
        
        // Look for "Up to X% off" pattern
        val upToPattern = Pattern.compile("(?i)((?:Up|Get) to \\d+%\\s+off.*?)(?=\\n|$)")
        val upToMatcher = upToPattern.matcher(text)
        if (upToMatcher.find()) {
            val desc = upToMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Up to X%' pattern: $desc")
            return desc
        }
        
        // Look for "Flat ₹X OFF" pattern
        val flatOffPattern = Pattern.compile("(?i)(Flat\\s+(?:Rs\\.?|₹)\\d+\\s+(?:off|OFF).*?)(?=\\n|$)")
        val flatOffMatcher = flatOffPattern.matcher(text)
        if (flatOffMatcher.find()) {
            val desc = flatOffMatcher.group(1)?.trim()
            Log.d(TAG, "Found description from 'Flat ₹X OFF' pattern: $desc")
            return desc
        }
        
        // Look for discount descriptions
        val discountPatterns = listOf(
            Pattern.compile("(?i)(\\d+%\\s+off.{3,30})"),
            Pattern.compile("(?i)(₹\\d+\\s+off.{3,30})"),
            Pattern.compile("(?i)(Rs\\.?\\s*\\d+\\s+off.{3,30})"),
            Pattern.compile("(?i)(save\\s+\\d+%.{3,30})"),
            Pattern.compile("(?i)(up\\s+to\\s+₹\\d+\\s+off?.{0,30})")
        )
        
        for (pattern in discountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val desc = matcher.group(1)
                Log.d(TAG, "Found description from discount pattern: $desc")
                return desc
            }
        }
        
        // If no specific discount pattern is found, return the first sentence
        val sentences = text.split(Pattern.compile("[.!?]"))
        if (sentences.isNotEmpty() && sentences[0].length > 10) {
            val desc = sentences[0].trim()
            Log.d(TAG, "Using first sentence as description: $desc")
            return desc
        }
        
        return null
    }

    /**
     * Extract expiry date from text
     * @param text The text to extract from
     * @return The extracted expiry date or null if not found
     */
    fun extractExpiryDate(text: String): Date? {
        return parseExpiryDate(text)
    }
    
    /**
     * Parse expiry date from text
     * @param text The text to parse
     * @return The parsed date or null if not found
     */
    fun parseExpiryDate(text: String): Date? {
        // Check for "Expires in X hours" format
        val expiresInHoursPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+hours?")
        val expiresInHoursMatcher = expiresInHoursPattern.matcher(text)
        if (expiresInHoursMatcher.find()) {
            val hoursToAdd = expiresInHoursMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.HOUR_OF_DAY, hoursToAdd)
            Log.d(TAG, "Found expiry date from 'expires in X hours' format: ${hoursToAdd} hours from now")
            return calendar.time
        }
        
        // Check for "Expiry:" format
        val expiryPattern = Pattern.compile("(?i)Expiry:\\s*(.+?)(?=\\n|$)")
        val expiryMatcher = expiryPattern.matcher(text)
        if (expiryMatcher.find()) {
            val expiryText = expiryMatcher.group(1)?.trim() ?: return null
            
            // Check if it contains "Expires in X hours/days"
            val expiresInPattern = Pattern.compile("(?i)Expires\\s+in\\s+(\\d+)\\s+(hours?|days?)")
            val expiresInMatcher = expiresInPattern.matcher(expiryText)
            if (expiresInMatcher.find()) {
                val timeValue = expiresInMatcher.group(1)?.toIntOrNull() ?: 0
                val timeUnit = expiresInMatcher.group(2)?.lowercase() ?: ""
                
                val calendar = Calendar.getInstance()
                if (timeUnit.startsWith("hour")) {
                    calendar.add(Calendar.HOUR_OF_DAY, timeValue)
                } else {
                    calendar.add(Calendar.DAY_OF_YEAR, timeValue)
                }
                
                Log.d(TAG, "Found expiry date from 'Expiry:' with 'expires in' format: $timeValue $timeUnit from now")
                return calendar.time
            }
            
            // Try to parse the expiry text as a date
            try {
                val datePatterns = listOf(
                    "dd/MM/yyyy",
                    "MM/dd/yyyy",
                    "yyyy-MM-dd",
                    "dd-MM-yyyy",
                    "dd MMM yyyy",
                    "MMM dd, yyyy"
                )
                
                for (pattern in datePatterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(expiryText)
                        if (date != null) {
                            Log.d(TAG, "Parsed expiry date from 'Expiry:' field: $expiryText")
                            return date
                        }
                    } catch (e: ParseException) {
                        // Try next pattern
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing expiry date from 'Expiry:' field", e)
            }
        }
        
        // Check for explicit expiry date in format "Expires: Mar 15, 2025"
        val explicitDatePattern = Pattern.compile("(?i)Expires?:?\\s+(\\w+\\s+\\d{1,2},\\s+\\d{4})")
        val explicitDateMatcher = explicitDatePattern.matcher(text)
        if (explicitDateMatcher.find()) {
            val dateStr = explicitDateMatcher.group(1) ?: return null
            try {
                val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                val date = sdf.parse(dateStr)
                Log.d(TAG, "Found expiry date from explicit format: $dateStr")
                return date
            } catch (e: ParseException) {
                // Continue with other patterns
            }
        }
        
        // Check for "Expires in X days" format
        val expiresInPattern = Pattern.compile("(?i)expires?\\s+in\\s+(\\d+)\\s+days?")
        val expiresInMatcher = expiresInPattern.matcher(text)
        if (expiresInMatcher.find()) {
            val daysToAdd = expiresInMatcher.group(1)?.toIntOrNull() ?: 0
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, daysToAdd)
            Log.d(TAG, "Found expiry date from 'expires in X days' format: ${daysToAdd} days from now")
            return calendar.time
        }
        
        // Check for standard date formats
        val datePatterns = listOf(
            "dd/MM/yyyy",
            "MM/dd/yyyy",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd MMM yyyy",
            "MMM dd, yyyy"
        )
        
        val dateRegexes = listOf(
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{2,4})"),
            Pattern.compile("(?i)(?:valid|expires?|expiry|valid until|valid till)\\s+(?:on|by|until|till)?\\s+([A-Za-z]{3}\\s+\\d{1,2},\\s+\\d{2,4})")
        )
        
        for (regex in dateRegexes) {
            val matcher = regex.matcher(text)
            if (matcher.find()) {
                val dateStr = matcher.group(1) ?: continue
                for (pattern in datePatterns) {
                    try {
                        val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                        val date = sdf.parse(dateStr)
                        Log.d(TAG, "Found expiry date from standard format: $dateStr")
                        return date
                    } catch (e: ParseException) {
                        // Try next pattern
                    }
                }
            }
        }
        
        // Try to parse the text directly as a date
        for (pattern in datePatterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.getDefault())
                val date = sdf.parse(text)
                Log.d(TAG, "Parsed text directly as date: $text")
                return date
            } catch (e: ParseException) {
                // Try next pattern
            }
        }
        
        // If no expiry date is found, set a default expiry date 30 days from now
        if (text.contains("ABHIBUS", ignoreCase = true)) {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.DAY_OF_YEAR, 30)
            Log.d(TAG, "Using default expiry date for ABHIBUS: 30 days from now")
            return calendar.time
        }
        
        return null
    }

    /**
     * Extract cashback amount from text
     * @param text The text to extract from
     * @return The extracted cashback amount or null if not found
     */
    fun extractCashbackAmount(text: String): Double? {
        // Look for specific percentage patterns first
        val percentagePatterns = listOf(
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
            Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%")
        )
        
        for (pattern in percentagePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        Log.d(TAG, "Found percentage discount: $amount%")
                        // Don't apply any conversion factor for percentages
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing percentage", e)
                }
            }
        }
        
        // Now check for currency amount patterns
        val amountPatterns = listOf(
            Pattern.compile("(?i)(?:upto|up to|flat|get)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:off|cashback)"),
            Pattern.compile("(?i)(?:save|discount of)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        )
        
        for (pattern in amountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                try {
                    val amount = matcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        Log.d(TAG, "Found fixed currency amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing amount", e)
                }
            }
        }
        
        // Myntra specific pattern
        if (text.contains("myntra", ignoreCase = true)) {
            val myntraAmountPattern = Pattern.compile("(?i)(?:up to |voucher up to )(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
            val myntraAmountMatcher = myntraAmountPattern.matcher(text)
            if (myntraAmountMatcher.find()) {
                try {
                    val amount = myntraAmountMatcher.group(1)?.toDoubleOrNull()
                    if (amount != null) {
                        Log.d(TAG, "Found Myntra cashback amount: $amount")
                        return amount
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Myntra amount", e)
                }
            }
        }
        
        // Look for simple currency amounts
        val simpleAmountPattern = Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        val simpleAmountMatcher = simpleAmountPattern.matcher(text)
        if (simpleAmountMatcher.find()) {
            try {
                val amount = simpleAmountMatcher.group(1)?.toDoubleOrNull()
                if (amount != null) {
                    Log.d(TAG, "Found simple cashback amount: $amount")
                    return amount
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing simple amount", e)
            }
        }
        
        return null
    }
    
    /**
     * Determine the type of discount (percentage or fixed amount)
     * @param text The text to analyze
     * @return "PERCENTAGE" or "AMOUNT" or null if undetermined
     */
    fun extractDiscountType(text: String): String? {
        // Check if text contains percentage indicators
        if (text.contains("%")) {
            val percentagePatterns = listOf(
                Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%\\s*(?:off|cashback|discount)"),
                Pattern.compile("(?i)(?:up to|upto|flat)\\s*(\\d+(?:\\.\\d+)?)\\s*%"),
                Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*%")
            )
            
            for (pattern in percentagePatterns) {
                val matcher = pattern.matcher(text)
                if (matcher.find()) {
                    Log.d(TAG, "Identified discount type: PERCENTAGE")
                    return "PERCENTAGE"
                }
            }
        }
        
        // Check for currency amount patterns
        val amountPatterns = listOf(
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:upto|up to|flat|get)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)"),
            Pattern.compile("(?i)(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)\\s*(?:off|cashback)"),
            Pattern.compile("(?i)(?:save|discount of)\\s*(?:Rs\\.?|₹)\\s*(\\d+(?:\\.\\d+)?)")
        )
        
        for (pattern in amountPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                Log.d(TAG, "Identified discount type: AMOUNT")
                return "AMOUNT"
            }
        }
        
        return null
    }

    /**
     * Extract redeem code from text
     * @param text The text to extract from
     * @return The extracted redeem code or null if not found
     */
    fun extractRedeemCode(text: String): String? {
        // First check for code: pattern (most common)
        val codePattern = Pattern.compile("(?i)code:?\\s*([A-Z0-9]{5,})")
        val codeMatcher = codePattern.matcher(text)
        if (codeMatcher.find()) {
            val code = codeMatcher.group(1)
            Log.d(TAG, "Found code from 'code:' pattern: $code")
            return code
        }
        
        // Look for "code" pattern without colon (common in Myntra coupons)
        val codeWithoutColonPattern = Pattern.compile("(?i)\\bcode\\b\\s*([A-Z0-9]{5,})")
        val codeWithoutColonMatcher = codeWithoutColonPattern.matcher(text)
        if (codeWithoutColonMatcher.find()) {
            val code = codeWithoutColonMatcher.group(1)
            Log.d(TAG, "Found code from 'code' pattern without colon: $code")
            return code
        }
        
        // Check specifically for Myntra coupon code format
        if (text.contains("myntra", ignoreCase = true)) {
            // Try to find a typical Myntra code pattern (8-10 characters, alphanumeric)
            val myntraCodePattern = Pattern.compile("\\b([A-Z0-9]{8,})\\b")
            val myntraCodeMatcher = myntraCodePattern.matcher(text)
            while (myntraCodeMatcher.find()) {
                val potentialCode = myntraCodeMatcher.group(1)
                // Skip if it's too long to be a code
                if (potentialCode?.length ?: 0 <= 20) {
                    Log.d(TAG, "Found Myntra code from specific pattern: $potentialCode")
                    return potentialCode
                }
            }
        }
        
        // Try to find all caps+digits strings that might be codes
        val allCapsDigitsPattern = Pattern.compile("\\b([A-Z0-9]{6,})\\b")
        val allCapsDigitsMatcher = allCapsDigitsPattern.matcher(text)
        
        while (allCapsDigitsMatcher.find()) {
            val potentialCode = allCapsDigitsMatcher.group(1)
            // Skip if it's likely not a code (too long or too short)
            if ((potentialCode?.length ?: 0) in 6..20 && !COMMON_WORDS.contains(potentialCode?.lowercase() ?: "")) {
                Log.d(TAG, "Found code from all caps+digits pattern: $potentialCode")
                return potentialCode
            }
        }
        
        // Look for specific indicators that might precede a code
        val codeIndicators = listOf("use", "apply", "redeem", "coupon", "promocode", "promo code")
        
        for (indicator in codeIndicators) {
            val indicatorIndex = text.indexOf(indicator, ignoreCase = true)
            if (indicatorIndex != -1) {
                // Extract the next word after the indicator
                val afterIndicator = text.substring(indicatorIndex + indicator.length).trim()
                val potentialCode = afterIndicator.split(Pattern.compile("\\s+"))[0]
                
                if (potentialCode.length >= 5 && potentialCode.matches("[A-Z0-9]+".toRegex())) {
                    Log.d(TAG, "Found code after indicator '$indicator': $potentialCode")
                    return potentialCode
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract category from text
     * @param text The text to extract from
     * @return The extracted category or null if not found
     */
    fun extractCategory(text: String): String? {
        val lowerText = text.lowercase()
        
        // For XYXX, set category to Fashion
        if (lowerText.contains("xyxx")) {
            Log.d(TAG, "Setting category to Fashion for XYXX")
            return "Fashion"
        }
        
        // For Myntra, set category to Fashion
        if (lowerText.contains("myntra")) {
            Log.d(TAG, "Setting category to Fashion for Myntra")
            return "Fashion"
        }
        
        // For ABHIBUS, set category to Travel
        if (lowerText.contains("abhibus")) {
            Log.d(TAG, "Setting category to Travel for ABHIBUS")
            return "Travel"
        }
        
        for (category in CATEGORIES) {
            if (lowerText.contains(category.lowercase())) {
                Log.d(TAG, "Found category from text: $category")
                return category
            }
        }
        
        return null
    }
    
    /**
     * Extract rating from text
     * @param text The text to extract from
     * @return The extracted rating or null if not found
     */
    fun extractRating(text: String): String? {
        // Look for "Rating:" pattern
        val ratingPattern = Pattern.compile("(?i)Rating:\\s*(.+?)(?=\\n|$)")
        val ratingMatcher = ratingPattern.matcher(text)
        if (ratingMatcher.find()) {
            val rating = ratingMatcher.group(1)?.trim()
            Log.d(TAG, "Found rating from 'Rating:' pattern: $rating")
            return rating
        }
        
        // Look for star rating pattern (e.g., "⭐ 4.31")
        val starRatingPattern = Pattern.compile("(⭐\\s*\\d+\\.\\d+)")
        val starRatingMatcher = starRatingPattern.matcher(text)
        if (starRatingMatcher.find()) {
            val rating = starRatingMatcher.group(1)
            Log.d(TAG, "Found rating from star pattern: $rating")
            return rating
        }
        
        // Look for numeric rating pattern (e.g., "4.31/5")
        val numericRatingPattern = Pattern.compile("(\\d+\\.\\d+)\\s*/\\s*5")
        val numericRatingMatcher = numericRatingPattern.matcher(text)
        if (numericRatingMatcher.find()) {
            val rating = numericRatingMatcher.group(1)
            Log.d(TAG, "Found rating from numeric pattern: $rating")
            return rating
        }
        
        return null
    }
    
    /**
     * Extract status from text
     * @param text The text to extract from
     * @return The extracted status or null if not found
     */
    fun extractStatus(text: String): String? {
        // Look for "Status:" pattern
        val statusPattern = Pattern.compile("(?i)Status:\\s*(.+?)(?=\\n|$)")
        val statusMatcher = statusPattern.matcher(text)
        if (statusMatcher.find()) {
            val status = statusMatcher.group(1)?.trim()
            Log.d(TAG, "Found status from 'Status:' pattern: $status")
            return status
        }
        
        // Look for "Available to Redeem" pattern
        if (text.contains("Available to Redeem", ignoreCase = true)) {
            Log.d(TAG, "Found 'Available to Redeem' status")
            return "Available to Redeem"
        }
        
        // Look for "Redeemed" pattern
        if (text.contains("Redeemed", ignoreCase = true)) {
            Log.d(TAG, "Found 'Redeemed' status")
            return "Redeemed"
        }
        
        // Look for "Expired" pattern
        if (text.contains("Expired", ignoreCase = true)) {
            Log.d(TAG, "Found 'Expired' status")
            return "Expired"
        }
        
        return null
    }
    
    companion object {
        private val COMMON_WORDS = setOf(
            "the", "and", "for", "with", "off", "use", "get", "code", "coupon", 
            "offer", "valid", "till", "from", "upto", "free", "save", "discount"
        )
        
        private val CATEGORIES = listOf(
            "Food", "Travel", "Shopping", "Electronics", "Fashion", "Beauty", 
            "Health", "Entertainment", "Education", "Services"
        )
    }
} 
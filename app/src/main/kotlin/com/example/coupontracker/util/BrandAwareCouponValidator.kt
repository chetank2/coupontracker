package com.example.coupontracker.util

import android.util.Log

/**
 * Brand-aware coupon code validation and scoring
 * Implements the ranking approach rather than hard rejection
 */
object BrandAwareCouponValidator {
    private const val TAG = "BrandAwareCouponValidator"
    
    // Base regex for coupon codes
    private val BASE_REGEX = Regex("^[A-Z0-9][A-Z0-9_-]{3,15}$")
    
    /**
     * Brand-specific boost patterns
     */
    private fun getBoostRegexForBrand(brand: String?): Regex? {
        return when (brand?.lowercase()) {
            "myntra" -> Regex("^(MYNTRA|MY|MN|SAVE|FLAT|EXTRA)[A-Z0-9]{2,12}$")
            "mamaearth" -> Regex("^(MAMAEARTH|MAMA|ME|GLOW|NATURAL)[A-Z0-9]{2,12}$")
            "abhibus" -> Regex("^(ABHIBUS|ABHI|BUS|RIDE|TRAVEL)[A-Z0-9]{2,12}$|^[A-Z0-9]{8,12}$")
            "ixigo" -> Regex("^(IXIGO|IXI|FLY|TRAIN|TRIP)[A-Z0-9]{2,12}$")
            "boat" -> Regex("^(BOAT|BT|ROCKERZ|STORM|NIRVANA)[A-Z0-9]{2,12}$")
            "newme" -> Regex("^(NEWME|NEW|NM)[A-Z0-9]{2,12}$")
            "zomato" -> Regex("^(ZOMATO|ZOM|Z|FOOD|EATS)[A-Z0-9]{2,12}$")
            "swiggy" -> Regex("^(SWIGGY|SW|FOOD|DELIVERY)[A-Z0-9]{2,12}$")
            "flipkart" -> Regex("^(FLIPKART|FK|FLIP|BIG|SALE)[A-Z0-9]{2,12}$")
            "amazon" -> Regex("^(AMAZON|AMZ|PRIME|GREAT)[A-Z0-9]{2,12}$")
            else -> null
        }
    }
    
    /**
     * Rank coupon code candidates with brand awareness
     */
    fun rankCodes(brand: String?, rawTokens: List<String>): List<CodeCandidate> {
        val brandRegex = getBoostRegexForBrand(brand)
        
        return rawTokens
            .map { it.trim().uppercase() }
            .filter { it.length in 4..16 }
            .map { token ->
                val baseMatch = BASE_REGEX.matches(token)
                val brandBoost = brandRegex?.matches(token) == true
                val dashPenalty = if (token.count { it == '-' || it == '_' } > 3) -0.2 else 0.0
                val lengthBonus = when (token.length) {
                    in 6..10 -> 0.1  // Optimal length
                    in 4..5 -> 0.05  // Short but OK
                    in 11..12 -> 0.05 // Long but OK
                    else -> 0.0
                }
                
                val score = (if (baseMatch) 0.6 else 0.0) + 
                           (if (brandBoost) 0.35 else 0.0) + 
                           dashPenalty + lengthBonus
                
                CodeCandidate(token, baseMatch, brandBoost, score)
            }
            .filter { it.baseMatch } // Accept gate - only base matches
            .sortedByDescending { it.score }
    }
    
    /**
     * Get brand-specific cashback patterns
     */
    fun getBrandCashbackPatterns(brand: String?): List<Regex> {
        val basePatterns = listOf(
            Regex("(?:₹|INR|RS\\.?\\s*)\\s*([0-9]{2,5})(?:\\s*(?:CASHBACK|BACK|OFF))?", RegexOption.IGNORE_CASE),
            Regex("([1-9][0-9]?)\\s*%(?:\\s*(?:OFF|CASHBACK))?", RegexOption.IGNORE_CASE),
            Regex("(?:UP\\s*TO|Upto)\\s*(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
        )
        
        val brandSpecificPatterns = when (brand?.lowercase()) {
            "myntra" -> listOf(
                Regex("(?:Myntra|End of Reason|EORS).*?(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
            )
            "ixigo" -> listOf(
                Regex("(?:ixigo|flights?|trains?).*?(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
            )
            "abhibus" -> listOf(
                Regex("(?:AbhiBus|bus|sleeper|AC).*?(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
            )
            "boat" -> listOf(
                Regex("(?:boAt|audio|earbuds|Rockerz|Bassheads).*?(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
            )
            "mamaearth" -> listOf(
                Regex("(?:Mamaearth|skin|hair|face|serum).*?(₹\\s*[0-9]{2,5}|[1-9][0-9]?\\s*%)", RegexOption.IGNORE_CASE)
            )
            else -> emptyList()
        }
        
        return basePatterns + brandSpecificPatterns
    }
    
    /**
     * Normalize lookalike characters in codes
     */
    fun normalizeLookalikes(code: String): String {
        return code
            .replace('O', '0') // O -> 0
            .replace('I', '1') // I -> 1  
            .replace('S', '5') // S -> 5 (less common)
            .replace('B', '8') // B -> 8 (less common)
    }
    
    /**
     * Validate against common junk patterns
     */
    fun isJunkCode(code: String): Boolean {
        val junkPatterns = listOf(
            Regex("^(VOUCHER|COUPON|OFFER|DISCOUNT|CODE|USING|NEEDED|APPLY|USE)$"),
            Regex("^[A-Z]{1,2}$"), // Too short
            Regex("^[0-9]+$"), // Only numbers
            Regex("^(GET|SAVE|FLAT|EXTRA|UP|TO|OFF|BACK)$") // Common words only
        )
        
        return junkPatterns.any { it.matches(code.uppercase()) }
    }
    
    /**
     * Extract brand from store name or description
     */
    fun extractBrand(storeName: String?, description: String?): String? {
        val text = listOfNotNull(storeName, description).joinToString(" ").lowercase()
        
        val brandPatterns = mapOf(
            "myntra" to listOf("myntra"),
            "mamaearth" to listOf("mamaearth", "mama earth"),
            "abhibus" to listOf("abhibus", "abhi bus"),
            "ixigo" to listOf("ixigo"),
            "boat" to listOf("boat", "imagine marketing"),
            "newme" to listOf("newme", "new me"),
            "zomato" to listOf("zomato"),
            "swiggy" to listOf("swiggy"),
            "flipkart" to listOf("flipkart"),
            "amazon" to listOf("amazon")
        )
        
        for ((brand, patterns) in brandPatterns) {
            if (patterns.any { pattern -> text.contains(pattern) }) {
                return brand
            }
        }
        
        return null
    }
}

/**
 * Data class for code candidates with scoring
 */
data class CodeCandidate(
    val text: String,
    val baseMatch: Boolean,
    val brandBoost: Boolean,
    val score: Double
) {
    override fun toString(): String = "$text (score: ${"%.2f".format(score)})"
}

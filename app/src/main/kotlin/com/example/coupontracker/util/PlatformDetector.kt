package com.example.coupontracker.util

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class to detect platforms from coupon text or images
 */
class PlatformDetector {
    companion object {
        private val PLATFORM_KEYWORDS = mapOf(
            "Food Delivery" to listOf(
                "swiggy", "zomato", "foodpanda", "ubereats", "food delivery", 
                "restaurant", "dining", "meal", "order food"
            ),
            "E-commerce" to listOf(
                "amazon", "flipkart", "myntra", "ajio", "snapdeal", "meesho",
                "shopping", "purchase", "buy online", "shop now"
            ),
            "Payment" to listOf(
                "phonepe", "google pay", "gpay", "paytm", "cred", "mobikwik",
                "payment", "transaction", "wallet", "upi", "bank", "credit card"
            ),
            "Travel" to listOf(
                "makemytrip", "goibibo", "cleartrip", "ixigo", "easemytrip",
                "flight", "hotel", "booking", "travel", "trip", "journey"
            ),
            "Entertainment" to listOf(
                "netflix", "amazon prime", "hotstar", "sony liv", "zee5",
                "movie", "show", "stream", "watch", "entertainment"
            )
        )
        
        /**
         * Detect platform type from text
         * @param text The text to analyze
         * @return The detected platform type or null if not detected
         */
        fun detectPlatformFromText(text: String): String? {
            val lowercaseText = text.lowercase()
            
            for ((platform, keywords) in PLATFORM_KEYWORDS) {
                for (keyword in keywords) {
                    if (lowercaseText.contains(keyword)) {
                        return platform
                    }
                }
            }
            
            return null
        }
        
        /**
         * Detect platform from image using logo recognition
         * This is a placeholder for more advanced logo recognition
         * @param bitmap The image to analyze
         * @return The detected platform or null if not detected
         */
        suspend fun detectPlatformFromImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
            // In a real implementation, this would use ML Kit or a custom model
            // to detect logos and map them to platforms
            
            // For now, we'll return null as this requires more advanced implementation
            null
        }
    }
}

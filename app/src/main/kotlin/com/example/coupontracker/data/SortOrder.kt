package com.example.coupontracker.data

/**
 * Enum representing different sort orders for coupons
 */
enum class SortOrder(val displayName: String) {
    EXPIRY_DATE("Expiry date"),
    NAME("Store name"),
    CREATED_DATE("Recently added")
}

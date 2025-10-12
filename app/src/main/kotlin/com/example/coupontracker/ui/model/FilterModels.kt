package com.example.coupontracker.ui.model

/**
 * Centralised filter models so ViewModels and composables stay in sync.
 */
data class FilterState(
    val selectedStores: Set<String> = emptySet(),
    val selectedCategories: Set<String> = emptySet(),
    val status: CouponStatusFilter = CouponStatusFilter.ALL,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val expiryRange: ExpiryRange = ExpiryRange.ALL
)

fun FilterState.hasActiveFilters(includeSearchQuery: Boolean = false, searchQuery: String = ""): Boolean {
    val base = selectedStores.isNotEmpty() ||
        selectedCategories.isNotEmpty() ||
        status != CouponStatusFilter.ALL ||
        minValue != null ||
        maxValue != null ||
        expiryRange != ExpiryRange.ALL
    return if (includeSearchQuery) base || searchQuery.isNotBlank() else base
}

enum class CouponStatusFilter(val displayName: String) {
    ALL("All"),
    ACTIVE("Active"),
    EXPIRING_SOON("Expiring soon"),
    EXPIRED("Expired")
}

enum class ExpiryRange(val daysAhead: Int?, val displayName: String) {
    ALL(null, "Any time"),
    THIRTY_DAYS(30, "Next 30 days"),
    SEVEN_DAYS(7, "Next 7 days"),
    THREE_DAYS(3, "Next 3 days"),
    TODAY(0, "Expires today")
}


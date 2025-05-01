package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject

/**
 * Data class for coupon filters
 */
data class CouponFilters(
    val status: String = "All",
    val platform: String = "All",
    val sortBy: String = "Expiry",
    val priorityOnly: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val couponRepository: CouponRepository
) : ViewModel() {

    // Filter state
    private val _filters = MutableStateFlow(CouponFilters())
    val filters: StateFlow<CouponFilters> = _filters.asStateFlow()

    // Get all coupons as a StateFlow with filters applied
    val coupons: StateFlow<List<Coupon>> = combine(
        couponRepository.getAllCoupons(),
        _filters
    ) { coupons, filters ->
        applyCouponFilters(coupons, filters)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Get priority coupons
    val priorityCoupons: StateFlow<List<Coupon>> = couponRepository.getPriorityCoupons()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Get expiring coupons (next 7 days)
    val expiringCoupons: StateFlow<List<Coupon>> = couponRepository.getExpiringCoupons(
        getExpiryThresholdDate()
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    /**
     * Update the coupon filters
     */
    fun updateFilters(newFilters: CouponFilters) {
        _filters.value = newFilters
    }

    /**
     * Apply filters to the coupon list
     */
    private fun applyCouponFilters(coupons: List<Coupon>, filters: CouponFilters): List<Coupon> {
        var filteredCoupons = coupons

        // Apply status filter
        if (filters.status != "All") {
            filteredCoupons = filteredCoupons.filter { it.status == filters.status }
        }

        // Apply platform filter
        if (filters.platform != "All") {
            filteredCoupons = filteredCoupons.filter { it.platformType == filters.platform }
        }

        // Apply priority filter
        if (filters.priorityOnly) {
            filteredCoupons = filteredCoupons.filter { it.isPriority }
        }

        // Apply sorting
        filteredCoupons = when (filters.sortBy) {
            "Expiry" -> filteredCoupons.sortedBy { it.expiryDate }
            "Value" -> filteredCoupons.sortedByDescending { it.cashbackAmount }
            "Store" -> filteredCoupons.sortedBy { it.storeName }
            else -> filteredCoupons
        }

        return filteredCoupons
    }

    /**
     * Get the date threshold for expiring coupons (7 days from now)
     */
    private fun getExpiryThresholdDate(): Date {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 7)
        return calendar.time
    }
}
package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.SortOrder
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.ui.components.FilterOption
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.util.*
import javax.inject.Inject

/**
 * Data class for coupon filters
 */
data class CouponFilters(
    val filterOption: FilterOption = FilterOption.ALL,
    val sortOrder: SortOrder = SortOrder.EXPIRY_DATE
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
        val now = Date()
        val calendar = Calendar.getInstance()

        // Apply filter option
        filteredCoupons = when (filters.filterOption) {
            FilterOption.ALL -> coupons
            FilterOption.ACTIVE -> coupons.filter { coupon ->
                val expiry = coupon.expiryDate
                expiry == null || expiry.after(now)
            }
            FilterOption.EXPIRING_SOON -> {
                calendar.time = now
                calendar.add(Calendar.DAY_OF_YEAR, 7)
                val sevenDaysFromNow = calendar.time
                coupons.filter { coupon ->
                    val expiry = coupon.expiryDate ?: return@filter false
                    expiry.after(now) && expiry.before(sevenDaysFromNow)
                }
            }
            FilterOption.EXPIRED -> coupons.filter { coupon ->
                val expiry = coupon.expiryDate ?: return@filter false
                expiry.before(now)
            }
            FilterOption.HIGH_VALUE -> coupons.filter { it.cashbackAmount >= 100 } // Assuming 100 is high value
            FilterOption.FOOD -> coupons.filter { it.category?.contains("Food", ignoreCase = true) == true ||
                                                 it.category?.contains("Dining", ignoreCase = true) == true ||
                                                 it.storeName.contains("Food", ignoreCase = true) ||
                                                 it.storeName.contains("Restaurant", ignoreCase = true) }
            FilterOption.SHOPPING -> coupons.filter { it.category?.contains("Shopping", ignoreCase = true) == true ||
                                                     it.storeName.contains("Shop", ignoreCase = true) ||
                                                     it.storeName.contains("Store", ignoreCase = true) ||
                                                     it.storeName.contains("Mart", ignoreCase = true) }
            FilterOption.TRAVEL -> coupons.filter { it.category?.contains("Travel", ignoreCase = true) == true ||
                                                   it.storeName.contains("Travel", ignoreCase = true) ||
                                                   it.storeName.contains("Flight", ignoreCase = true) ||
                                                   it.storeName.contains("Hotel", ignoreCase = true) }
            FilterOption.ENTERTAINMENT -> coupons.filter { it.category?.contains("Entertainment", ignoreCase = true) == true ||
                                                          it.storeName.contains("Movie", ignoreCase = true) ||
                                                          it.storeName.contains("Game", ignoreCase = true) ||
                                                          it.storeName.contains("Play", ignoreCase = true) }
            FilterOption.OTHER -> coupons.filter { it.category == null || it.category == "Other" }
        }

        // Apply sorting
        filteredCoupons = when (filters.sortOrder) {
            SortOrder.EXPIRY_DATE -> filteredCoupons.sortedWith(
                compareBy<Coupon> { it.expiryDate == null }.thenBy { it.expiryDate }
            )
            SortOrder.NAME -> filteredCoupons.sortedBy { it.storeName }
            SortOrder.AMOUNT -> filteredCoupons.sortedByDescending { it.cashbackAmount }
            SortOrder.CREATED_DATE -> filteredCoupons.sortedByDescending { it.createdAt }
        }

        return filteredCoupons
    }

    /**
     * Update the sort order
     */
    fun updateSortOrder(sortOrder: SortOrder) {
        _filters.value = _filters.value.copy(sortOrder = sortOrder)
    }

    /**
     * Update the filter option
     */
    fun updateFilterOption(filterOption: FilterOption) {
        _filters.value = _filters.value.copy(filterOption = filterOption)
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
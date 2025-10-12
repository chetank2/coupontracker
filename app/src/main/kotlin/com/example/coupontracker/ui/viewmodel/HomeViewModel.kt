package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.SortOrder
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.ui.model.CouponStatusFilter
import com.example.coupontracker.ui.model.ExpiryRange
import com.example.coupontracker.ui.model.FilterState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Data class for coupon filters
 */
data class CouponFilters(
    val filterState: FilterState = FilterState(),
    val sortOrder: SortOrder = SortOrder.EXPIRY_DATE,
    val searchQuery: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val couponRepository: CouponRepository
) : ViewModel() {

    private val allCouponsFlow: StateFlow<List<Coupon>> = couponRepository.getAllCoupons()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Filter state
    private val _filters = MutableStateFlow(CouponFilters())
    val filters: StateFlow<CouponFilters> = _filters.asStateFlow()

    // Get all coupons as a StateFlow with filters applied
    val coupons: StateFlow<List<Coupon>> = combine(
        allCouponsFlow,
        _filters
    ) { coupons, filters ->
        applyCouponFilters(coupons, filters)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Surface original data for chips and other UI
    val allCoupons: StateFlow<List<Coupon>> = allCouponsFlow

    val availableStores: StateFlow<List<String>> = allCouponsFlow
        .map { coupons ->
            coupons
                .map { it.storeName.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
                .sortedBy { it.lowercase(Locale.getDefault()) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val availableCategories: StateFlow<List<String>> = allCouponsFlow
        .map { coupons ->
            coupons
                .mapNotNull { it.category?.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.getDefault()) }
                .sortedBy { it.lowercase(Locale.getDefault()) }
        }
        .stateIn(
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
        val state = filters.filterState
        val now = Date()

        // Search filter first
        var filteredCoupons = coupons.filter { coupon ->
            val query = filters.searchQuery.trim()
            if (query.isBlank()) {
                true
            } else {
                val lowerQuery = query.lowercase(Locale.getDefault())
                coupon.storeName.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                    coupon.description.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                    (coupon.redeemCode?.lowercase(Locale.getDefault())?.contains(lowerQuery) == true)
            }
        }

        // Store filter
        if (state.selectedStores.isNotEmpty()) {
            filteredCoupons = filteredCoupons.filter { coupon ->
                state.selectedStores.any { store ->
                    coupon.storeName.equals(store, ignoreCase = true)
                }
            }
        }

        // Category filter
        if (state.selectedCategories.isNotEmpty()) {
            filteredCoupons = filteredCoupons.filter { coupon ->
                val category = coupon.category ?: return@filter false
                state.selectedCategories.any { selected ->
                    category.contains(selected, ignoreCase = true)
                }
            }
        }

        // Status filter
        filteredCoupons = when (state.status) {
            CouponStatusFilter.ALL -> filteredCoupons
            CouponStatusFilter.ACTIVE -> filteredCoupons.filter { coupon ->
                val expiry = coupon.expiryDate
                expiry == null || expiry.after(now)
            }
            CouponStatusFilter.EXPIRING_SOON -> {
                filterByExpiryWindow(filteredCoupons, now, 7)
            }
            CouponStatusFilter.EXPIRED -> filteredCoupons.filter { coupon ->
                val expiry = coupon.expiryDate ?: return@filter false
                expiry.before(now)
            }
        }

        // Value filter (numeric cashback)
        filteredCoupons = filteredCoupons.filter { coupon ->
            val value = coupon.getCashbackNumericValue()
            val minOk = state.minValue?.let { value >= it } ?: true
            val maxOk = state.maxValue?.let { value <= it } ?: true
            minOk && maxOk
        }

        // Expiry range filter
        filteredCoupons = when (state.expiryRange) {
            ExpiryRange.ALL -> filteredCoupons
            ExpiryRange.THIRTY_DAYS -> filterByExpiryWindow(filteredCoupons, now, 30)
            ExpiryRange.SEVEN_DAYS -> filterByExpiryWindow(filteredCoupons, now, 7)
            ExpiryRange.THREE_DAYS -> filterByExpiryWindow(filteredCoupons, now, 3)
            ExpiryRange.TODAY -> filteredCoupons.filter { coupon ->
                val expiry = coupon.expiryDate ?: return@filter false
                val calendar = Calendar.getInstance()
                calendar.time = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val startOfDay = calendar.time
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                val endOfDay = calendar.time
                !expiry.before(startOfDay) && expiry.before(endOfDay)
            }
        }

        // Apply sorting
        filteredCoupons = when (filters.sortOrder) {
            SortOrder.EXPIRY_DATE -> filteredCoupons.sortedWith(
                compareBy<Coupon> { it.expiryDate == null }.thenBy { it.expiryDate }
            )
            SortOrder.NAME -> filteredCoupons.sortedBy { it.storeName.lowercase() }
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
     * Update the filter state
     */
    fun updateFilterState(transform: (FilterState) -> FilterState) {
        _filters.value = _filters.value.copy(filterState = transform(_filters.value.filterState))
    }

    fun setFilterState(filterState: FilterState) {
        _filters.value = _filters.value.copy(filterState = filterState)
    }

    /**
     * Update search query
     */
    fun updateSearchQuery(query: String) {
        _filters.value = _filters.value.copy(searchQuery = query)
    }

    fun resetFilters() {
        _filters.value = CouponFilters()
    }

    /**
     * Get the date threshold for expiring coupons (7 days from now)
     */
    private fun getExpiryThresholdDate(): Date {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 7)
        return calendar.time
    }

    private fun filterByExpiryWindow(coupons: List<Coupon>, now: Date, days: Int): List<Coupon> {
        val calendar = Calendar.getInstance()
        calendar.time = now
        calendar.add(Calendar.DAY_OF_YEAR, days)
        val windowEnd = calendar.time
        return coupons.filter { coupon ->
            val expiry = coupon.expiryDate ?: return@filter false
            expiry.after(now) && expiry.before(windowEnd)
        }
    }
}
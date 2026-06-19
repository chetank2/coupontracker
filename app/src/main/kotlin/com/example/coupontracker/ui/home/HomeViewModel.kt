package com.example.coupontracker.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.SortOrder
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.debug.ExtractionDebugRepository
import com.example.coupontracker.debug.ExtractionDebugSnapshot
import com.example.coupontracker.ui.model.CouponStatusFilter
import com.example.coupontracker.ui.model.ExpiryRange
import com.example.coupontracker.ui.model.FilterState
import com.example.coupontracker.ui.theme.BrandSpacing
import com.example.coupontracker.ui.modelsettings.ModelImportUiState
import com.example.coupontracker.ui.modelsettings.ModelImportViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/**
 * Data class for coupon filters
 */
data class CouponFilters(
    val filterState: FilterState = FilterState(),
    val sortOrder: SortOrder = SortOrder.EXPIRY_DATE,
    val searchQuery: String = ""
)

enum class ModelAvailabilityStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    INSTALLED,
    ERROR
}

data class HomeUiState(
    val coupons: List<Coupon> = emptyList(),
    val filters: CouponFilters = CouponFilters(),
    val modelStatus: ModelAvailabilityStatus = ModelAvailabilityStatus.NOT_INSTALLED,
    val modelProgress: Int = 0,
    val modelMessage: String = "",
    val showModelCard: Boolean = true,
    val modelError: String? = null,
    val extractionDebug: Map<Long, ExtractionDebugSnapshot> = emptyMap()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val couponRepository: CouponRepository,
    private val modelImportManager: com.example.coupontracker.model.ModelImportManager,
    private val securePreferencesManager: SecurePreferencesManager,
    private val debugRepository: ExtractionDebugRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val allCouponsFlow: StateFlow<List<Coupon>> = couponRepository.getAllCoupons()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val modelStatusFlow = MutableStateFlow(ModelAvailabilityStatus.NOT_INSTALLED)
    private val modelProgressFlow = MutableStateFlow(0)
    private val modelMessageFlow = MutableStateFlow("")

    // Filter state
    private val _filters = MutableStateFlow(CouponFilters())
    val filters: StateFlow<CouponFilters> = _filters.asStateFlow()

    init {
        observeModelStatus()
        observeCoupons()
    }

    private fun observeModelStatus() {
        viewModelScope.launch {
            modelStatusFlow.value = determineModelStatus()
            _uiState.value = _uiState.value.copy(
                modelStatus = modelStatusFlow.value,
                modelProgress = modelProgressFlow.value,
                modelMessage = modelMessageFlow.value,
                modelError = if (modelStatusFlow.value == ModelAvailabilityStatus.ERROR) modelMessageFlow.value else null,
                showModelCard = shouldShowModelCard(modelStatusFlow.value)
            )
        }
    }

    private fun observeCoupons() {
        combine(
            allCouponsFlow,
            modelStatusFlow,
            _filters,
            debugRepository.snapshots
        ) { coupons, modelStatus, filters, debugSnapshots ->
            val filteredCoupons = applyCouponFilters(coupons, filters)
            _uiState.value.copy(
                coupons = filteredCoupons,
                filters = filters,
                modelStatus = modelStatus,
                modelProgress = modelProgressFlow.value,
                modelMessage = modelMessageFlow.value,
                modelError = if (modelStatus == ModelAvailabilityStatus.ERROR) modelMessageFlow.value else null,
                showModelCard = shouldShowModelCard(modelStatus),
                extractionDebug = debugSnapshots
            )
        }.onEach { updated ->
            _uiState.value = updated
        }.launchIn(viewModelScope)
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            val status = determineModelStatus()
            modelStatusFlow.value = status
            _uiState.value = _uiState.value.copy(
                modelStatus = status,
                modelProgress = modelProgressFlow.value,
                modelMessage = modelMessageFlow.value,
                modelError = if (status == ModelAvailabilityStatus.ERROR) modelMessageFlow.value else null,
                showModelCard = shouldShowModelCard(status)
            )
        }
    }

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

    private fun determineModelStatus(): ModelAvailabilityStatus {
        return if (securePreferencesManager.getLlmModelDownloaded()) {
            ModelAvailabilityStatus.INSTALLED
        } else {
            ModelAvailabilityStatus.NOT_INSTALLED
        }
    }

    private fun shouldShowModelCard(status: ModelAvailabilityStatus): Boolean {
        return status != ModelAvailabilityStatus.INSTALLED
    }
}

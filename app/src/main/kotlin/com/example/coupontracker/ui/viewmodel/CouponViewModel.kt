package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.SortOrder
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class CouponViewModel @Inject constructor(
    private val repository: CouponRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _coupons = MutableStateFlow<List<Coupon>>(emptyList())
    
    @OptIn(ExperimentalCoroutinesApi::class)
    val coupons: StateFlow<List<Coupon>> = combine(
        _searchQuery,
        settingsRepository.getSettings()
    ) { query, settings ->
        Pair(query, settings.sortOrder)
    }.flatMapLatest { (query, sortOrder) ->
        when {
            query.isNotBlank() -> repository.searchCoupons(query)
            sortOrder == SortOrder.EXPIRY_DATE -> repository.getAllCoupons()
            sortOrder == SortOrder.NAME -> repository.getCouponsByName()
            sortOrder == SortOrder.AMOUNT -> repository.getCouponsByAmount()
            else -> repository.getAllCoupons()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    
    init {
        viewModelScope.launch {
            repository.getAllCoupons().collect {
                _coupons.value = it
            }
        }
    }
    
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun addCoupon(coupon: Coupon) {
        viewModelScope.launch {
            repository.insertCoupon(coupon)
        }
    }

    fun updateCoupon(coupon: Coupon) {
        viewModelScope.launch {
            repository.updateCoupon(coupon)
        }
    }

    fun deleteCoupon(coupon: Coupon) {
        viewModelScope.launch {
            repository.deleteCoupon(coupon)
        }
    }

    fun getExpiringCoupons(days: Int): List<Coupon> {
        val expiryDate = Date(System.currentTimeMillis() + (days * 24 * 60 * 60 * 1000L))
        return _coupons.value.filter { it.expiryDate <= expiryDate }
    }
} 
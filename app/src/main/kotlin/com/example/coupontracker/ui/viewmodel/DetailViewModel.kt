package com.example.coupontracker.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: CouponRepository
) : ViewModel() {

    private val _coupon = MutableStateFlow<Coupon?>(null)
    val coupon: StateFlow<Coupon?> = _coupon

    private var couponId: Long = 0

    fun loadCoupon(id: Long) {
        couponId = id
        viewModelScope.launch {
            val couponData = repository.getCouponById(id)
            _coupon.value = couponData
        }
    }

    fun deleteCoupon() {
        viewModelScope.launch {
            _coupon.value?.let { coupon ->
                repository.deleteCoupon(coupon)
            }
        }
    }
} 
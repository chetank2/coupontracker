package com.example.coupontracker.data.repository

import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date

interface CouponRepository {
    fun getAllCoupons(): Flow<List<Coupon>>
    suspend fun getCouponById(couponId: Long): Coupon?
    fun searchCoupons(query: String): Flow<List<Coupon>>
    fun getCouponsByAmount(): Flow<List<Coupon>>
    fun getCouponsByName(): Flow<List<Coupon>>
    fun getExpiringCoupons(date: Date): Flow<List<Coupon>>
    suspend fun insertCoupon(coupon: Coupon): Long
    suspend fun updateCoupon(coupon: Coupon)
    suspend fun deleteCoupon(coupon: Coupon)
    suspend fun deleteAllCoupons()
} 
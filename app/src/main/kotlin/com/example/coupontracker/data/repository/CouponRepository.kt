package com.example.coupontracker.data.repository

import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date

interface CouponRepository {
    // Existing methods
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

    // New methods
    fun getPriorityCoupons(): Flow<List<Coupon>>
    fun getCouponsByPlatform(platformType: String): Flow<List<Coupon>>
    fun getCouponsWithReminders(): Flow<List<Coupon>>
    fun getCouponsExpiringBetween(startDate: Date, endDate: Date): Flow<List<Coupon>>
    suspend fun updateCouponUsageCount(couponId: Long)
    suspend fun updateCouponPriority(couponId: Long, isPriority: Boolean)
    suspend fun updateCouponReminder(couponId: Long, reminderDate: Date?)
    suspend fun updateCouponStatus(couponId: Long, status: String)
}
package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CouponRepositoryImpl @Inject constructor(
    private val couponDao: CouponDao
) : CouponRepository {
    // Existing methods
    override fun getAllCoupons(): Flow<List<Coupon>> = couponDao.getAllCoupons()

    override suspend fun getCouponById(couponId: Long): Coupon? = couponDao.getCouponById(couponId)

    override fun searchCoupons(query: String): Flow<List<Coupon>> = couponDao.searchCoupons(query)

    override fun getCouponsByAmount(): Flow<List<Coupon>> = couponDao.getCouponsByAmount()

    override fun getCouponsByName(): Flow<List<Coupon>> = couponDao.getCouponsByName()

    override fun getExpiringCoupons(date: Date): Flow<List<Coupon>> = couponDao.getExpiringCoupons(date)

    override suspend fun insertCoupon(coupon: Coupon): Long = couponDao.insertCoupon(coupon)

    override suspend fun updateCoupon(coupon: Coupon) = couponDao.updateCoupon(coupon)

    override suspend fun deleteCoupon(coupon: Coupon) = couponDao.deleteCoupon(coupon)

    override suspend fun deleteAllCoupons() = couponDao.deleteAllCoupons()

    // New methods
    override fun getPriorityCoupons(): Flow<List<Coupon>> = couponDao.getPriorityCoupons()

    override fun getCouponsByPlatform(platformType: String): Flow<List<Coupon>> =
        couponDao.getCouponsByPlatform(platformType)

    override fun getCouponsWithReminders(): Flow<List<Coupon>> = couponDao.getCouponsWithReminders()

    override fun getCouponsExpiringBetween(startDate: Date, endDate: Date): Flow<List<Coupon>> =
        couponDao.getCouponsExpiringBetween(startDate, endDate)

    override suspend fun updateCouponUsageCount(couponId: Long) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        val updatedCoupon = coupon.copy(
            usageCount = coupon.usageCount + 1,
            updatedAt = Date()
        )
        couponDao.updateCoupon(updatedCoupon)
    }

    override suspend fun updateCouponPriority(couponId: Long, isPriority: Boolean) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        val updatedCoupon = coupon.copy(
            isPriority = isPriority,
            updatedAt = Date()
        )
        couponDao.updateCoupon(updatedCoupon)
    }

    override suspend fun updateCouponReminder(couponId: Long, reminderDate: Date?) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        val updatedCoupon = coupon.copy(
            reminderDate = reminderDate,
            updatedAt = Date()
        )
        couponDao.updateCoupon(updatedCoupon)
    }

    override suspend fun updateCouponStatus(couponId: Long, status: String) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        val updatedCoupon = coupon.copy(
            status = status,
            updatedAt = Date()
        )
        couponDao.updateCoupon(updatedCoupon)
    }
}
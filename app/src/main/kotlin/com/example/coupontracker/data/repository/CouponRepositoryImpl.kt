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
} 
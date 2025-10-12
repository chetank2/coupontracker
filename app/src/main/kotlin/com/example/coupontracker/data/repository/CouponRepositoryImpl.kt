package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date
import kotlin.math.max
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

    override suspend fun replaceAllCoupons(coupons: List<Coupon>): Int {
        val sanitizedCoupons = coupons.map { coupon ->
            // Force Room to generate fresh IDs so we do not rely on the source database
            coupon.copy(id = 0)
        }
        return couponDao.replaceAllCoupons(sanitizedCoupons).size
    }

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

    override suspend fun saveOrMergeCoupon(
        coupon: Coupon,
        normalizedDescription: String,
        imagePhash: String?,
        imageSignature: String?
    ): Long {
        val normalizedCoupon = coupon.copy(
            normalizedDescription = normalizedDescription,
            imagePhash = imagePhash,
            imageSignature = imageSignature
        )

        val existing = couponDao.findByStoreAndDescription(
            storeName = normalizedCoupon.storeName,
            normalizedDescription = normalizedDescription,
            imagePhash = imagePhash,
            imageSignature = imageSignature
        )

        return if (existing != null) {
            val merged = mergeCoupons(existing, normalizedCoupon)
            couponDao.updateCoupon(merged)
            existing.id
        } else {
            couponDao.insertCoupon(normalizedCoupon)
        }
    }

    private fun mergeCoupons(existing: Coupon, incoming: Coupon): Coupon {
        return incoming.copy(
            id = existing.id,
            expiryDate = incoming.expiryDate ?: existing.expiryDate,
            normalizedDescription = incoming.normalizedDescription ?: existing.normalizedDescription,
            redeemCode = incoming.redeemCode ?: existing.redeemCode,
            imageUri = incoming.imageUri ?: existing.imageUri,
            imagePhash = incoming.imagePhash ?: existing.imagePhash,
            imageSignature = incoming.imageSignature ?: existing.imageSignature,
            category = incoming.category ?: existing.category,
            status = incoming.status ?: existing.status,
            minimumPurchase = incoming.minimumPurchase ?: existing.minimumPurchase,
            maximumDiscount = incoming.maximumDiscount ?: existing.maximumDiscount,
            isPriority = incoming.isPriority || existing.isPriority,
            paymentMethod = incoming.paymentMethod ?: existing.paymentMethod,
            usageLimit = incoming.usageLimit ?: existing.usageLimit,
            usageCount = max(incoming.usageCount, existing.usageCount),
            reminderDate = incoming.reminderDate ?: existing.reminderDate,
            platformType = incoming.platformType ?: existing.platformType,
            rating = incoming.rating ?: existing.rating,
            createdAt = existing.createdAt,
            updatedAt = Date()
        )
    }
}

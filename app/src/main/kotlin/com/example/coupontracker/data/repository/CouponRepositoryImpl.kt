package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.util.CouponDedupUtils
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

    override suspend fun saveOrMergeCoupon(coupon: Coupon): CouponSaveResult {
        val preparedCoupon = prepareCouponForStorage(coupon)
        val redeemCode = preparedCoupon.redeemCode
        val duplicates = when {
            !redeemCode.isNullOrBlank() ->
                couponDao.findByStoreAndCode(preparedCoupon.storeName, redeemCode)
            !preparedCoupon.normalizedDescription.isNullOrBlank() ->
                couponDao.findByStoreAndDescription(
                    preparedCoupon.storeName,
                    preparedCoupon.normalizedDescription!!,
                    preparedCoupon.imagePhash,
                    preparedCoupon.imageSignature
                )
            else -> emptyList()
        }

        if (duplicates.isEmpty()) {
            val newId = couponDao.insertCoupon(preparedCoupon)
            return CouponSaveResult.Inserted(preparedCoupon.copy(id = newId))
        }

        val existing = duplicates.maxByOrNull { it.updatedAt.time } ?: duplicates.first()
        val (mergedCoupon, updatedFields) = mergeCoupons(existing, preparedCoupon)

        return if (updatedFields.isEmpty()) {
            CouponSaveResult.Duplicate(existing)
        } else {
            couponDao.updateCoupon(mergedCoupon)
            CouponSaveResult.Merged(mergedCoupon, updatedFields)
        }
    }

    private fun prepareCouponForStorage(coupon: Coupon): Coupon {
        val normalizedDescription = coupon.normalizedDescription
            ?: CouponDedupUtils.normalizeDescription(coupon.description)

        return coupon.copy(
            storeName = coupon.storeName.trim(),
            description = coupon.description.trim(),
            redeemCode = coupon.redeemCode?.trim()?.takeIf { it.isNotEmpty() },
            terms = coupon.terms?.trim()?.takeIf { it.isNotEmpty() },
            normalizedDescription = normalizedDescription,
            imagePhash = coupon.imagePhash?.takeIf { it.isNotBlank() },
            imageSignature = coupon.imageSignature?.takeIf { it.isNotBlank() }
        )
    }

    private fun mergeCoupons(existing: Coupon, incoming: Coupon): Pair<Coupon, Set<String>> {
        var merged = existing
        val updatedFields = mutableSetOf<String>()

        fun update(field: String, block: (Coupon) -> Coupon) {
            merged = block(merged)
            updatedFields.add(field)
        }

        if (CouponDedupUtils.shouldReplaceDescription(existing.description, incoming.description)) {
            update("description") {
                it.copy(
                    description = incoming.description,
                    normalizedDescription = incoming.normalizedDescription ?: it.normalizedDescription
                )
            }
        }

        if (merged.normalizedDescription.isNullOrBlank() && !incoming.normalizedDescription.isNullOrBlank()) {
            update("normalizedDescription") { it.copy(normalizedDescription = incoming.normalizedDescription) }
        }

        if (CouponDedupUtils.shouldReplaceTerms(existing.terms, incoming.terms)) {
            update("terms") { it.copy(terms = incoming.terms) }
        }

        if (incoming.cashbackAmount > existing.cashbackAmount) {
            update("cashbackAmount") { it.copy(cashbackAmount = incoming.cashbackAmount) }
        }

        if (incoming.expiryDate.after(existing.expiryDate)) {
            update("expiryDate") { it.copy(expiryDate = incoming.expiryDate) }
        }

        if (existing.category.isNullOrBlank() && !incoming.category.isNullOrBlank()) {
            update("category") { it.copy(category = incoming.category) }
        }

        if (existing.imageUri.isNullOrBlank() && !incoming.imageUri.isNullOrBlank()) {
            update("imageUri") { it.copy(imageUri = incoming.imageUri) }
        }

        if (existing.imagePhash.isNullOrBlank() && !incoming.imagePhash.isNullOrBlank()) {
            update("imagePhash") { it.copy(imagePhash = incoming.imagePhash) }
        }

        if (existing.imageSignature.isNullOrBlank() && !incoming.imageSignature.isNullOrBlank()) {
            update("imageSignature") { it.copy(imageSignature = incoming.imageSignature) }
        }

        if (existing.paymentMethod.isNullOrBlank() && !incoming.paymentMethod.isNullOrBlank()) {
            update("paymentMethod") { it.copy(paymentMethod = incoming.paymentMethod) }
        }

        if (existing.platformType.isNullOrBlank() && !incoming.platformType.isNullOrBlank()) {
            update("platformType") { it.copy(platformType = incoming.platformType) }
        }

        if (existing.minimumPurchase == null && incoming.minimumPurchase != null) {
            update("minimumPurchase") { it.copy(minimumPurchase = incoming.minimumPurchase) }
        }

        if (existing.maximumDiscount == null && incoming.maximumDiscount != null) {
            update("maximumDiscount") { it.copy(maximumDiscount = incoming.maximumDiscount) }
        }

        if (existing.usageLimit == null && incoming.usageLimit != null) {
            update("usageLimit") { it.copy(usageLimit = incoming.usageLimit) }
        }

        if (!existing.isPriority && incoming.isPriority) {
            update("isPriority") { it.copy(isPriority = true) }
        }

        if (existing.reminderDate == null && incoming.reminderDate != null) {
            update("reminderDate") { it.copy(reminderDate = incoming.reminderDate) }
        }

        if (existing.rating.isNullOrBlank() && !incoming.rating.isNullOrBlank()) {
            update("rating") { it.copy(rating = incoming.rating) }
        }

        if (updatedFields.isNotEmpty()) {
            merged = merged.copy(updatedAt = Date())
        }

        return merged to updatedFields
    }
}
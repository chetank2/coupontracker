package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CouponRepositoryImpl @Inject constructor(
    private val couponDao: CouponDao,
    private val reminderScheduler: CouponReminderScheduler
) : CouponRepository {
    // Existing methods
    override fun getAllCoupons(): Flow<List<Coupon>> = couponDao.getAllCoupons()

    override suspend fun getCouponById(couponId: Long): Coupon? = couponDao.getCouponById(couponId)

    override fun searchCoupons(query: String): Flow<List<Coupon>> = couponDao.searchCoupons(query)

    override fun getCouponsByName(): Flow<List<Coupon>> = couponDao.getCouponsByName()

    override fun getExpiringCoupons(date: Date): Flow<List<Coupon>> = couponDao.getExpiringCoupons(date)

    override suspend fun insertCoupon(coupon: Coupon): Long {
        val normalized = normalizeReminder(coupon)
        val id = couponDao.insertCoupon(normalized)
        if (id > 0) {
            val persisted = normalized.copy(id = id)
            if (persisted.reminderLeadTimeMinutes != null) {
                reminderScheduler.schedule(persisted, persisted.reminderDate)
            }
        }
        return id
    }

    override suspend fun updateCoupon(coupon: Coupon) {
        val normalized = normalizeReminder(coupon)
        couponDao.updateCoupon(normalized)
        if (normalized.reminderLeadTimeMinutes != null) {
            reminderScheduler.schedule(normalized, normalized.reminderDate)
        } else {
            reminderScheduler.cancel(normalized.id)
        }
    }

    override suspend fun deleteCoupon(coupon: Coupon) {
        reminderScheduler.cancel(coupon.id)
        couponDao.deleteCoupon(coupon)
    }

    override suspend fun deleteAllCoupons() = couponDao.deleteAllCoupons()

    override suspend fun replaceAllCoupons(coupons: List<Coupon>): Int {
        val sanitizedCoupons = coupons.map { coupon ->
            // Force Room to generate fresh IDs so we do not rely on the source database
            coupon.copy(id = 0)
        }
        val insertedIds = couponDao.replaceAllCoupons(sanitizedCoupons)
        coupons.zip(insertedIds).forEach { (coupon, id) ->
            val restored = normalizeReminder(coupon.copy(id = id))
            if (restored.reminderLeadTimeMinutes != null) {
                reminderScheduler.schedule(restored, restored.reminderDate)
            } else {
                reminderScheduler.cancel(id)
            }
        }
        return insertedIds.size
    }

    // New methods
    override fun getPriorityCoupons(): Flow<List<Coupon>> = couponDao.getPriorityCoupons()

    override fun getCouponsByPlatform(platformType: String): Flow<List<Coupon>> =
        couponDao.getCouponsByPlatform(platformType)

    override fun getCouponsWithReminders(): Flow<List<Coupon>> = couponDao.getCouponsWithReminders()

    override suspend fun getCouponsExpiringBetween(startDate: Date, endDate: Date): List<Coupon> =
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

    override suspend fun updateCouponReminder(
        couponId: Long,
        reminderDate: Date?,
        reminderLeadTimeMinutes: Int?
    ) {
        val coupon = couponDao.getCouponById(couponId) ?: return
        val updatedCoupon = normalizeReminder(
            coupon.copy(
                reminderDate = reminderDate,
                reminderLeadTimeMinutes = reminderLeadTimeMinutes,
                updatedAt = Date()
            )
        )
        couponDao.updateCoupon(updatedCoupon)
        if (updatedCoupon.reminderLeadTimeMinutes != null) {
            reminderScheduler.schedule(updatedCoupon, updatedCoupon.reminderDate)
        } else {
            reminderScheduler.cancel(couponId)
        }
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
            imageSignature = imageSignature,
            extractionConfidenceBreakdown = coupon.extractionConfidenceBreakdown
        )

        val existing = couponDao.findByStoreAndDescription(
            storeName = normalizedCoupon.storeName,
            normalizedDescription = normalizedDescription,
            imagePhash = imagePhash,
            imageSignature = imageSignature
        ) ?: normalizedCoupon.redeemCode
            ?.takeIf(::isReliableDuplicateCode)
            ?.let { code -> couponDao.findByRedeemCode(code) }

        return if (existing != null) {
            val merged = normalizeReminder(mergeCoupons(existing, normalizedCoupon))
            couponDao.updateCoupon(merged)
            if (merged.reminderLeadTimeMinutes != null) {
                reminderScheduler.schedule(merged, merged.reminderDate)
            } else {
                reminderScheduler.cancel(merged.id)
            }
            existing.id
        } else {
            insertCoupon(normalizeReminder(normalizedCoupon))
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
            reminderLeadTimeMinutes = incoming.reminderLeadTimeMinutes ?: existing.reminderLeadTimeMinutes,
            platformType = incoming.platformType ?: existing.platformType,
            extractionQualityScore = selectBestQualityScore(incoming, existing),
            extractionConfidenceBreakdown = selectConfidenceMap(incoming, existing),
            extractionStage = incoming.extractionStage ?: existing.extractionStage,
            extractionRunPath = incoming.extractionRunPath ?: existing.extractionRunPath,
            extractionTimestamp = incoming.extractionTimestamp ?: existing.extractionTimestamp,
            rating = incoming.rating ?: existing.rating,
            needsAttention = incoming.needsAttention || existing.needsAttention,
            createdAt = existing.createdAt,
            updatedAt = Date()
        )
    }

    private fun selectBestQualityScore(incoming: Coupon, existing: Coupon): Int? {
        val incomingScore = incoming.extractionQualityScore
        val existingScore = existing.extractionQualityScore
        return when {
            incomingScore == null -> existingScore
            existingScore == null -> incomingScore
            else -> max(incomingScore, existingScore)
        }
    }

    private fun selectConfidenceMap(incoming: Coupon, existing: Coupon): Map<String, Float> {
        return when {
            incoming.extractionConfidenceBreakdown.isNotEmpty() -> incoming.extractionConfidenceBreakdown
            existing.extractionConfidenceBreakdown.isNotEmpty() -> existing.extractionConfidenceBreakdown
            else -> emptyMap()
        }
    }

    private fun isReliableDuplicateCode(code: String): Boolean {
        val normalized = code.trim().uppercase()
        if (normalized.length < 6) return false
        if (normalized in unreliableDuplicateCodes) return false
        return normalized.any { it.isDigit() } && normalized.any { it.isLetter() }
    }

    private fun normalizeReminder(coupon: Coupon): Coupon {
        val leadTime = coupon.reminderLeadTimeMinutes
        val expiry = coupon.expiryDate

        if (leadTime == null) {
            return coupon.copy(reminderDate = null, reminderLeadTimeMinutes = null)
        }

        val computedReminder = when {
            coupon.reminderDate != null -> coupon.reminderDate
            expiry != null -> Date(expiry.time - TimeUnit.MINUTES.toMillis(leadTime.toLong()))
            else -> null
        }

        if (computedReminder == null) {
            return coupon.copy(reminderDate = null, reminderLeadTimeMinutes = null)
        }

        return coupon.copy(reminderDate = computedReminder)
    }

    companion object {
        private val unreliableDuplicateCodes = setOf(
            "ACTIVE",
            "COPY",
            "CODE",
            "COUPON",
            "NEEDED",
            "OFFER",
            "VOUCHER"
        )
    }
}

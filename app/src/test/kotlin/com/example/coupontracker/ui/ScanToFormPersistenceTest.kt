package com.example.coupontracker.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.ui.viewmodel.CouponFormViewModel
import com.example.coupontracker.ui.viewmodel.CouponSaveResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class ScanToFormPersistenceTest {

    @Test
    fun composeScanFlow_savesOnceWhenFormConfirms() = runTest {
        val repository = FakeCouponRepository()
        val application = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = CouponFormViewModel(
            application = application,
            context = application,
            couponRepository = repository,
            savedStateHandle = SavedStateHandle()
        )

        val normalizedDescription = CouponDedupUtils.normalizeDescription("10% Off")
        val existingCoupon = Coupon(
            id = 1,
            storeName = "Test Store",
            description = "10% Off",
            normalizedDescription = normalizedDescription,
            expiryDate = Date(0),
            cashbackAmount = 10.0,
            redeemCode = "SAVE10",
            imageUri = "content://test",
            status = "ACTIVE",
            createdAt = Date(0),
            updatedAt = Date(0)
        )
        repository.seed(existingCoupon)

        viewModel.saveCoupon(
            storeName = existingCoupon.storeName,
            description = existingCoupon.description,
            amount = existingCoupon.cashbackAmount,
            code = existingCoupon.redeemCode ?: "",
            expiryDate = Date(),
            category = existingCoupon.category ?: "",
            imageUri = existingCoupon.imageUri
        )

        advanceUntilIdle()

        assertEquals(1, repository.saveOrMergeCount)
        assertEquals(0, repository.directInsertCount)
        assertEquals(1, repository.mergeCount)
        assertSame(CouponSaveResult.ALREADY_SAVED, viewModel.uiState.value.saveResult)
        assertEquals(existingCoupon.id, repository.lastSavedId)
    }

    private class FakeCouponRepository : CouponRepository {
        private val coupons = linkedMapOf<Long, Coupon>()
        private var nextId = 2L

        var saveOrMergeCount = 0
        var mergeCount = 0
        var directInsertCount = 0
        var lastSavedId: Long? = null

        fun seed(coupon: Coupon) {
            coupons[coupon.id] = coupon
            if (coupon.id >= nextId) {
                nextId = coupon.id + 1
            }
        }

        override fun getAllCoupons(): Flow<List<Coupon>> = emptyFlow()

        override suspend fun getCouponById(couponId: Long): Coupon? = coupons[couponId]

        override fun searchCoupons(query: String): Flow<List<Coupon>> = emptyFlow()

        override fun getCouponsByAmount(): Flow<List<Coupon>> = emptyFlow()

        override fun getCouponsByName(): Flow<List<Coupon>> = emptyFlow()

        override fun getExpiringCoupons(date: Date): Flow<List<Coupon>> = emptyFlow()

        override suspend fun insertCoupon(coupon: Coupon): Long {
            directInsertCount++
            val id = nextId++
            coupons[id] = coupon.copy(id = id)
            return id
        }

        override suspend fun updateCoupon(coupon: Coupon) {
            coupons[coupon.id] = coupon
        }

        override suspend fun deleteCoupon(coupon: Coupon) {
            coupons.remove(coupon.id)
        }

        override suspend fun deleteAllCoupons() {
            coupons.clear()
        }

        override fun getPriorityCoupons(): Flow<List<Coupon>> = emptyFlow()

        override fun getCouponsByPlatform(platformType: String): Flow<List<Coupon>> = emptyFlow()

        override fun getCouponsWithReminders(): Flow<List<Coupon>> = emptyFlow()

        override fun getCouponsExpiringBetween(startDate: Date, endDate: Date): Flow<List<Coupon>> = emptyFlow()

        override suspend fun updateCouponUsageCount(couponId: Long) {
            // Not needed for tests
        }

        override suspend fun updateCouponPriority(couponId: Long, isPriority: Boolean) {
            // Not needed for tests
        }

        override suspend fun updateCouponReminder(couponId: Long, reminderDate: Date?) {
            // Not needed for tests
        }

        override suspend fun updateCouponStatus(couponId: Long, status: String) {
            val existing = coupons[couponId] ?: return
            coupons[couponId] = existing.copy(status = status)
        }

        override suspend fun saveOrMergeCoupon(
            coupon: Coupon,
            normalizedDescription: String,
            imagePhash: String?,
            imageSignature: String?
        ): Long {
            saveOrMergeCount++
            val existing = coupons.values.firstOrNull {
                it.storeName == coupon.storeName && it.normalizedDescription == normalizedDescription
            }
            return if (existing != null) {
                mergeCount++
                val merged = coupon.copy(
                    id = existing.id,
                    normalizedDescription = normalizedDescription,
                    createdAt = existing.createdAt,
                    updatedAt = Date()
                )
                coupons[existing.id] = merged
                lastSavedId = existing.id
                existing.id
            } else {
                val id = if (coupon.id != 0L) coupon.id else nextId++
                val stored = coupon.copy(id = id, normalizedDescription = normalizedDescription)
                coupons[id] = stored
                lastSavedId = id
                id
            }
        }
    }
}

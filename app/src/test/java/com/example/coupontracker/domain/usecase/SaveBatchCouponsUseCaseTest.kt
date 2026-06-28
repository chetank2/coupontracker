package com.example.coupontracker.domain.usecase

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SaveBatchCouponsUseCaseTest {

    @Test
    fun `inserts coupons in order`() = runTest {
        val repository = mockk<CouponRepository>()
        val useCase = SaveBatchCouponsUseCase(repository)
        val first = coupon("First")
        val second = coupon("Second")
        coEvery { repository.insertCoupon(first) } returns 1L
        coEvery { repository.insertCoupon(second) } returns 2L

        useCase(listOf(first, second))

        coVerifyOrder {
            repository.insertCoupon(first)
            repository.insertCoupon(second)
        }
    }

    private fun coupon(storeName: String): Coupon {
        return Coupon(
            storeName = storeName,
            description = "Offer",
            redeemCode = null,
            imageUri = null
        )
    }
}

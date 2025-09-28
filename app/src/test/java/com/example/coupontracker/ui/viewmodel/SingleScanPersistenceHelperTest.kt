package com.example.coupontracker.ui.viewmodel

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.CouponSaveResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Date

class SingleScanPersistenceHelperTest {

    private val repository: CouponRepository = mockk()
    private val helper = SingleScanPersistenceHelper(repository)

    @Test
    fun `duplicate single scan maps to AlreadySaved state`() = runBlocking {
        val existingCoupon = Coupon(
            id = 123,
            storeName = "Test Store",
            description = "Special deal",
            expiryDate = Date(),
            cashbackAmount = 5.0,
            redeemCode = "TEST-123",
            imageUri = null
        )

        val incoming = existingCoupon.copy(id = 0, cashbackAmount = 10.0, redeemCode = "TEST-123")

        coEvery {
            repository.saveOrMergeCoupon(
                coupon = incoming,
                normalizedDescription = any(),
                descriptionHash = any(),
                descriptionSignature = any()
            )
        } returns CouponSaveResult.AlreadySaved(existingCoupon)

        val result = helper.persistCoupon(
            coupon = incoming,
            extractedFields = mapOf(
                "storeName" to incoming.storeName,
                "description" to incoming.description,
                "code" to (incoming.redeemCode ?: "")
            )
        )

        val state = result.toScannerState(MiniCpmProgress.SUCCESS)

        assert(state is ScannerUiState.AlreadySaved)
        val alreadySaved = state as ScannerUiState.AlreadySaved
        assert(alreadySaved.coupon.id == existingCoupon.id)
    }
}

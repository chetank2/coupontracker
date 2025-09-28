package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.Date

class CouponRepositoryTest {

    private lateinit var couponDao: CouponDao
    private lateinit var repository: CouponRepository
    private lateinit var repositoryImpl: CouponRepositoryImpl

    @Before
    fun setup() {
        couponDao = mockk(relaxed = true)
        repositoryImpl = CouponRepositoryImpl(couponDao)
        repository = repositoryImpl
    }

    @Test
    fun `getAllCoupons returns flow from dao`() = runBlocking {
        // Given
        val coupons = listOf(
            Coupon(
                id = 1,
                storeName = "Test Store",
                description = "Test Description",
                expiryDate = Date(),
                cashbackAmount = 10.0,
                redeemCode = "TEST123",
                imageUri = null,
                category = "Test"
            )
        )
        coEvery { couponDao.getAllCoupons() } returns flowOf(coupons)

        // When
        val result = repository.getAllCoupons()

        // Then
        assert(result == couponDao.getAllCoupons())
    }

    @Test
    fun `insertCoupon calls dao insertCoupon`() = runBlocking {
        // Given
        val coupon = Coupon(
            id = 0,
            storeName = "Test Store",
            description = "Test Description",
            expiryDate = Date(),
            cashbackAmount = 10.0,
            redeemCode = "TEST123",
            imageUri = null,
            category = "Test"
        )
        coEvery { couponDao.insertCoupon(any()) } returns 1L

        // When
        val result = repository.insertCoupon(coupon)

        // Then
        coVerify { couponDao.insertCoupon(coupon) }
        assert(result == 1L)
    }

    @Test
    fun `deleteCoupon calls dao deleteCoupon`() = runBlocking {
        // Given
        val coupon = Coupon(
            id = 1,
            storeName = "Test Store",
            description = "Test Description",
            expiryDate = Date(),
            cashbackAmount = 10.0,
            redeemCode = "TEST123",
            imageUri = null,
            category = "Test"
        )

        // When
        repository.deleteCoupon(coupon)

        // Then
        coVerify { couponDao.deleteCoupon(coupon) }
    }

    @Test
    fun `saveOrMergeCoupon reuses coupon with existing code`() = runBlocking {
        val description = "Limited Time Offer!!!"
        val normalized = CouponDedupUtils.normalizeDescription(description)

        val existing = Coupon(
            id = 42,
            storeName = "Store",
            description = description,
            normalizedDescription = normalized,
            expiryDate = Date(),
            cashbackAmount = 10.0,
            redeemCode = "CODE123",
            imageUri = null,
            category = "Test",
            usageCount = 1,
            createdAt = Date(0),
            updatedAt = Date(0)
        )

        val incoming = existing.copy(
            id = 0,
            normalizedDescription = null,
            redeemCode = null,
            cashbackAmount = 20.0,
            usageCount = 5,
            createdAt = Date(),
            updatedAt = Date()
        )

        coEvery {
            couponDao.findByStoreAndDescription(
                storeName = existing.storeName,
                normalizedDescription = normalized,
                imagePhash = null,
                imageSignature = null
            )
        } returns existing
        coEvery { couponDao.updateCoupon(any()) } returns Unit

        val result = repositoryImpl.saveOrMergeCoupon(incoming, normalized, null, null)

        assert(result == existing.id)
        coVerify(exactly = 0) { couponDao.insertCoupon(any()) }
        coVerify {
            couponDao.updateCoupon(
                match {
                    it.id == existing.id &&
                        it.normalizedDescription == normalized &&
                        it.redeemCode == existing.redeemCode &&
                        it.cashbackAmount == incoming.cashbackAmount &&
                        it.usageCount == kotlin.math.max(existing.usageCount, incoming.usageCount) &&
                        it.createdAt == existing.createdAt
                }
            )
        }
    }
}

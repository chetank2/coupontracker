package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
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

    @Before
    fun setup() {
        couponDao = mockk(relaxed = true)
        repository = CouponRepositoryImpl(couponDao)
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
} 
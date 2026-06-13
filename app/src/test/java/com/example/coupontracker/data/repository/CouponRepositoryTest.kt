package com.example.coupontracker.data.repository

import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponReminderScheduler
import com.example.coupontracker.data.util.CouponDedupUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.Date

class CouponRepositoryTest {

    private lateinit var couponDao: CouponDao
    private lateinit var repository: CouponRepository
    private lateinit var repositoryImpl: CouponRepositoryImpl
    private lateinit var reminderScheduler: CouponReminderScheduler

    @Before
    fun setup() {
        couponDao = mockk(relaxed = true)
        reminderScheduler = mockk(relaxed = true)
        repositoryImpl = CouponRepositoryImpl(couponDao, reminderScheduler)
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
        verify(exactly = 0) { reminderScheduler.schedule(any(), any()) }
    }

    @Test
    fun `deleteCoupon calls dao deleteCoupon`() = runBlocking {
        // Given
        val coupon = Coupon(
            id = 1,
            storeName = "Test Store",
            description = "Test Description",
            expiryDate = Date(),
            redeemCode = "TEST123",
            imageUri = null,
            category = "Test"
        )

        // When
        repository.deleteCoupon(coupon)

        // Then
        coVerify { couponDao.deleteCoupon(coupon) }
        verify { reminderScheduler.cancel(coupon.id) }
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
            redeemCode = "CODE123",
            imageUri = null,
            category = "Test",
            usageCount = 1,
            reminderLeadTimeMinutes = 120,
            reminderDate = Date(System.currentTimeMillis() + 120 * 60 * 1000),
            createdAt = Date(0),
            updatedAt = Date(0)
        )

        val incoming = existing.copy(
            id = 0,
            normalizedDescription = null,
            redeemCode = null,
            usageCount = 5,
            expiryDate = null,
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
                        it.expiryDate == existing.expiryDate &&
                        it.usageCount == kotlin.math.max(existing.usageCount, incoming.usageCount) &&
                        it.createdAt == existing.createdAt
                }
            )
        }
        verify { reminderScheduler.schedule(any(), any()) }
    }

    @Test
    fun `saveOrMergeCoupon reuses reliable redeem code when text differs`() = runBlocking {
        val existing = Coupon(
            id = 7,
            storeName = "Trubebasics Flat",
            description = "Flat 150 off TRUEBASICS CLEAN YEAST PROTEIN",
            normalizedDescription = CouponDedupUtils.normalizeDescription("Flat 150 off TRUEBASICS CLEAN YEAST PROTEIN"),
            expiryDate = Date(),
            redeemCode = "PHTB2ATAQQ2DCY",
            imageUri = null,
            category = "Test",
            createdAt = Date(0),
            updatedAt = Date(0)
        )
        val incomingDescription = "GY071%\nCashback: ₹150 off"
        val incoming = existing.copy(
            id = 0,
            storeName = "TRUE BASICS",
            description = incomingDescription,
            normalizedDescription = null,
            createdAt = Date(),
            updatedAt = Date()
        )
        val normalized = CouponDedupUtils.normalizeDescription(incomingDescription)

        coEvery {
            couponDao.findByStoreAndDescription(
                storeName = incoming.storeName,
                normalizedDescription = normalized,
                imagePhash = null,
                imageSignature = null
            )
        } returns null
        coEvery { couponDao.findByRedeemCode("PHTB2ATAQQ2DCY") } returns existing
        coEvery { couponDao.updateCoupon(any()) } returns Unit

        val result = repositoryImpl.saveOrMergeCoupon(incoming, normalized, null, null)

        assert(result == existing.id)
        coVerify(exactly = 0) { couponDao.insertCoupon(any()) }
        coVerify {
            couponDao.updateCoupon(
                match {
                    it.id == existing.id &&
                        it.storeName == incoming.storeName &&
                        it.redeemCode == "PHTB2ATAQQ2DCY" &&
                        it.createdAt == existing.createdAt
                }
            )
        }
    }

    @Test
    fun `insertCoupon schedules reminder when enabled`() = runBlocking {
        val expiry = Date(System.currentTimeMillis() + 48 * 60 * 60 * 1000)
        val coupon = Coupon(
            id = 0,
            storeName = "Reminder Store",
            description = "Reminder Description",
            expiryDate = expiry,
            redeemCode = "REM123",
            imageUri = null,
            category = "Test",
            reminderLeadTimeMinutes = 1440
        )
        coEvery { couponDao.insertCoupon(any()) } returns 42L

        repository.insertCoupon(coupon)

        coVerify { couponDao.insertCoupon(any()) }
        verify { reminderScheduler.schedule(match { it.id == 42L }, any()) }
    }

    @Test
    fun `updateCouponReminder cancels when disabled`() = runBlocking {
        val coupon = Coupon(
            id = 5,
            storeName = "Store",
            description = "Desc",
            expiryDate = Date(),
            redeemCode = null,
            imageUri = null,
            category = "Cat",
            reminderLeadTimeMinutes = 60,
            reminderDate = Date()
        )
        coEvery { couponDao.getCouponById(5) } returns coupon
        coEvery { couponDao.updateCoupon(any()) } returns Unit

        repository.updateCouponReminder(5, null, null)

        verify { reminderScheduler.cancel(5) }
    }
}

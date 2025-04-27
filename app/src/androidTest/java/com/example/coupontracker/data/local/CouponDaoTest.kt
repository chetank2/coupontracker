package com.example.coupontracker.data.local

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class CouponDaoTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: CouponDatabase
    private lateinit var couponDao: CouponDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CouponDatabase::class.java
        ).allowMainThreadQueries().build()
        
        couponDao = database.couponDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndGetCoupon() = runBlocking {
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
        
        // When
        val id = couponDao.insertCoupon(coupon)
        val retrievedCoupon = couponDao.getCouponById(id)
        
        // Then
        assert(retrievedCoupon != null)
        retrievedCoupon?.let {
            assert(it.storeName == coupon.storeName)
            assert(it.description == coupon.description)
            assert(it.cashbackAmount == coupon.cashbackAmount)
            assert(it.redeemCode == coupon.redeemCode)
            assert(it.category == coupon.category)
        }
    }

    @Test
    fun getAllCoupons() = runBlocking {
        // Given
        val coupon1 = Coupon(
            id = 0,
            storeName = "Test Store 1",
            description = "Test Description 1",
            expiryDate = Date(),
            cashbackAmount = 10.0,
            redeemCode = "TEST123",
            imageUri = null,
            category = "Test"
        )
        
        val coupon2 = Coupon(
            id = 0,
            storeName = "Test Store 2",
            description = "Test Description 2",
            expiryDate = Date(),
            cashbackAmount = 20.0,
            redeemCode = "TEST456",
            imageUri = null,
            category = "Test"
        )
        
        // When
        couponDao.insertCoupon(coupon1)
        couponDao.insertCoupon(coupon2)
        val coupons = couponDao.getAllCoupons().first()
        
        // Then
        assert(coupons.size == 2)
    }

    @Test
    fun deleteCoupon() = runBlocking {
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
        
        // When
        val id = couponDao.insertCoupon(coupon)
        val retrievedCoupon = couponDao.getCouponById(id)
        retrievedCoupon?.let {
            couponDao.deleteCoupon(it)
        }
        val coupons = couponDao.getAllCoupons().first()
        
        // Then
        assert(coupons.isEmpty())
    }
} 
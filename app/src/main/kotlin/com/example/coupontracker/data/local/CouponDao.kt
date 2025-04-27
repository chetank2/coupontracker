package com.example.coupontracker.data.local

import androidx.room.*
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface CouponDao {
    @Query("SELECT * FROM coupons ORDER BY expiryDate ASC")
    fun getAllCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE id = :couponId")
    suspend fun getCouponById(couponId: Long): Coupon?

    @Query("SELECT * FROM coupons WHERE storeName LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%'")
    fun searchCoupons(query: String): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons ORDER BY cashbackAmount DESC")
    fun getCouponsByAmount(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons ORDER BY storeName ASC")
    fun getCouponsByName(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE expiryDate <= :date")
    fun getExpiringCoupons(date: Date): Flow<List<Coupon>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoupon(coupon: Coupon): Long

    @Update
    suspend fun updateCoupon(coupon: Coupon)

    @Delete
    suspend fun deleteCoupon(coupon: Coupon)

    @Query("DELETE FROM coupons")
    suspend fun deleteAllCoupons()
} 
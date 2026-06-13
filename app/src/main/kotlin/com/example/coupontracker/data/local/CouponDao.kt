package com.example.coupontracker.data.local

import androidx.room.*
import com.example.coupontracker.data.model.Coupon
import kotlinx.coroutines.flow.Flow
import java.util.Date

@Dao
interface CouponDao {
    // Existing queries
    @Query("SELECT * FROM coupons ORDER BY (expiryDate IS NULL), expiryDate ASC")
    fun getAllCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE id = :couponId")
    suspend fun getCouponById(couponId: Long): Coupon?

    @Query("SELECT * FROM coupons WHERE storeName LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%'")
    fun searchCoupons(query: String): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons ORDER BY storeName COLLATE NOCASE ASC")
    fun getCouponsByName(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE expiryDate <= :date")
    fun getExpiringCoupons(date: Date): Flow<List<Coupon>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoupon(coupon: Coupon): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCoupons(coupons: List<Coupon>): List<Long>

    @Update
    suspend fun updateCoupon(coupon: Coupon)

    @Delete
    suspend fun deleteCoupon(coupon: Coupon)

    @Query("DELETE FROM coupons")
    suspend fun deleteAllCoupons()

    @Transaction
    suspend fun replaceAllCoupons(coupons: List<Coupon>): List<Long> {
        deleteAllCoupons()
        if (coupons.isEmpty()) {
            return emptyList()
        }
        return insertCoupons(coupons)
    }

    // New queries
    @Query("SELECT * FROM coupons WHERE isPriority = 1 ORDER BY (expiryDate IS NULL), expiryDate ASC")
    fun getPriorityCoupons(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE platformType = :platformType ORDER BY (expiryDate IS NULL), expiryDate ASC")
    fun getCouponsByPlatform(platformType: String): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE reminderDate IS NOT NULL ORDER BY reminderDate ASC")
    fun getCouponsWithReminders(): Flow<List<Coupon>>

    @Query("SELECT * FROM coupons WHERE expiryDate BETWEEN :startDate AND :endDate ORDER BY (expiryDate IS NULL), expiryDate ASC")
    suspend fun getCouponsExpiringBetween(startDate: Date, endDate: Date): List<Coupon>

    @Query(
        """
        SELECT * FROM coupons
        WHERE storeName = :storeName
          AND normalizedDescription = :normalizedDescription
          AND (:imagePhash IS NULL OR imagePhash = :imagePhash)
          AND (:imageSignature IS NULL OR imageSignature = :imageSignature)
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun findByStoreAndDescription(
        storeName: String,
        normalizedDescription: String,
        imagePhash: String?,
        imageSignature: String?
    ): Coupon?

    @Query(
        """
        SELECT * FROM coupons
        WHERE redeemCode IS NOT NULL
          AND UPPER(redeemCode) = UPPER(:redeemCode)
        ORDER BY updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun findByRedeemCode(redeemCode: String): Coupon?
}

package com.example.coupontracker.widget

import android.content.Context
import com.example.coupontracker.data.repository.CouponRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.*

/**
 * Data provider for widget
 * Fetches coupon counts from repository
 */
object CouponWidgetDataProvider {
    
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun getCouponRepository(): CouponRepository
    }
    
    /**
     * Get coupon counts for widget display
     */
    suspend fun getCouponCounts(context: Context): WidgetCounts {
        return try {
            // Get repository via Hilt entry point
            val hiltEntryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                WidgetEntryPoint::class.java
            )
            val repository = hiltEntryPoint.getCouponRepository()
            
            // Get all coupons
            val allCoupons = repository.getAllCoupons().first()
            
            // Calculate expiring soon (next 7 days)
            val now = Date()
            val calendar = Calendar.getInstance()
            calendar.time = now
            calendar.add(Calendar.DAY_OF_YEAR, 7)
            val sevenDaysFromNow = calendar.time
            
            val expiringCoupons = allCoupons.filter { coupon ->
                val expiry = coupon.expiryDate ?: return@filter false
                expiry.after(now) && expiry.before(sevenDaysFromNow)
            }
            
            // Calculate active coupons
            val activeCoupons = allCoupons.filter { coupon ->
                val expiry = coupon.expiryDate
                expiry == null || expiry.after(now)
            }
            
            WidgetCounts(
                expiringCount = expiringCoupons.size,
                totalCount = allCoupons.size,
                activeCount = activeCoupons.size
            )
            
        } catch (e: Exception) {
            android.util.Log.e("WidgetDataProvider", "Failed to fetch coupon counts", e)
            // Return zero counts on error
            WidgetCounts(
                expiringCount = 0,
                totalCount = 0,
                activeCount = 0
            )
        }
    }
}


package com.example.coupontracker.worker

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.coupontracker.R
import com.example.coupontracker.data.repository.CouponRepository
import androidx.hilt.work.HiltWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Date

@HiltWorker
class CouponNotificationWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val repository: CouponRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            // Get all coupons
            val coupons = repository.getAllCoupons().first()
            
            // Check for coupons that are expiring soon
            val now = Date()
            val expiringCoupons = coupons.filter { coupon ->
                val daysUntilExpiry = (coupon.expiryDate.time - now.time) / (1000 * 60 * 60 * 24)
                daysUntilExpiry in 1..7 // Notify for coupons expiring in 1-7 days
            }
            
            // Create notifications for expiring coupons
            expiringCoupons.forEach { coupon ->
                val notification = NotificationCompat.Builder(context, "coupon_expiry")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("Coupon Expiring Soon")
                    .setContentText("${coupon.storeName} coupon for ${coupon.description} expires in ${(coupon.expiryDate.time - now.time) / (1000 * 60 * 60 * 24)} days")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(coupon.id.toInt(), notification)
            }
            
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
} 
package com.example.coupontracker.work

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.model.Coupon
import io.mockk.coEvery
import io.mockk.mockk
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CouponReminderWorkerTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    @Test
    fun reminderWorker_postsNotification() = runBlocking {
        val couponDao = mockk<CouponDao>()
        val expiry = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(12))
        val reminderDate = Date(System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1))
        val coupon = Coupon(
            id = 101,
            storeName = "Integration Store",
            description = "10% off",
            expiryDate = expiry,
            redeemCode = null,
            imageUri = null,
            category = "Test",
            reminderDate = reminderDate,
            reminderLeadTimeMinutes = 1440
        )
        coEvery { couponDao.getCouponsExpiringBetween(any(), any()) } returns listOf(coupon)

        val worker = TestListenableWorkerBuilder<CouponReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    return CouponReminderWorker(appContext, workerParameters, couponDao)
                }
            })
            .build()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val active = notificationManager.activeNotifications
        assertTrue(active.any { notification ->
            notification.id == coupon.id.hashCode() &&
                notification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)
                    ?.contains(coupon.storeName) == true
        })
    }
}

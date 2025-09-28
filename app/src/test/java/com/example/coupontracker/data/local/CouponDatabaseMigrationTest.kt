package com.example.coupontracker.data.local

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.data.util.CouponDedupUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CouponDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-test-db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate3To4_populatesNormalizedDescription_andSupportsDedupLookup() = runBlocking {
        val storeName = "Legacy Store"
        val description = "SAVE $10!!! Limited time"
        createLegacyDatabase(storeName, description)
        val normalized = CouponDedupUtils.normalizeDescription(description)

        val database = Room.databaseBuilder(context, CouponDatabase::class.java, databaseName)
            .addMigrations(CouponDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()

        val migratedCoupon = database.couponDao().findByStoreAndDescription(
            storeName = storeName,
            normalizedDescription = normalized,
            imagePhash = null,
            imageSignature = null
        )

        assertNotNull("Coupon should be found after migration", migratedCoupon)
        assertEquals(normalized, migratedCoupon?.normalizedDescription)
        assertNull(migratedCoupon?.imagePhash)
        assertNull(migratedCoupon?.imageSignature)

        database.close()
    }

    private fun createLegacyDatabase(storeName: String, description: String) {
        context.deleteDatabase(databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `coupons` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `storeName` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `expiryDate` INTEGER NOT NULL,
                            `cashbackAmount` REAL NOT NULL,
                            `redeemCode` TEXT,
                            `imageUri` TEXT,
                            `category` TEXT,
                            `status` TEXT,
                            `minimumPurchase` REAL,
                            `maximumDiscount` REAL,
                            `isPriority` INTEGER NOT NULL,
                            `paymentMethod` TEXT,
                            `usageLimit` INTEGER,
                            `usageCount` INTEGER NOT NULL,
                            `reminderDate` INTEGER,
                            `platformType` TEXT,
                            `rating` TEXT,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL
                        )
                        """
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // No-op for the test schema
                }
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = openHelper.writableDatabase
        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("storeName", storeName)
            put("description", description)
            put("expiryDate", now)
            put("cashbackAmount", 5.0)
            put("redeemCode", "LEGACY")
            put("imageUri", null as String?)
            put("category", "Legacy")
            put("status", null as String?)
            put("minimumPurchase", null as Double?)
            put("maximumDiscount", null as Double?)
            put("isPriority", 0)
            put("paymentMethod", null as String?)
            put("usageLimit", null as Int?)
            put("usageCount", 1)
            put("reminderDate", null as Long?)
            put("platformType", null as String?)
            put("rating", null as String?)
            put("createdAt", now)
            put("updatedAt", now)
        }

        db.insert("coupons", SupportSQLiteDatabase.CONFLICT_ABORT, values)
        db.close()
        openHelper.close()
    }
}

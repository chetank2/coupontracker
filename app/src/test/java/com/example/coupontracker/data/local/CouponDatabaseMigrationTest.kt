package com.example.coupontracker.data.local

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.data.model.Coupon
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
    fun migrate3To5_populatesNormalizedDescription_andSupportsDedupLookup() = runBlocking {
        val storeName = "Legacy Store"
        val description = "SAVE $10!!! Limited time"
        createLegacyDatabase(storeName, description)
        val normalized = CouponDedupUtils.normalizeDescription(description)

        val database = Room.databaseBuilder(context, CouponDatabase::class.java, databaseName)
            .addMigrations(
                CouponDatabase.MIGRATION_3_4,
                CouponDatabase.MIGRATION_4_5
            )
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

    @Test
    fun migrate4To5_allowsNullExpiryDates() = runBlocking {
        createVersion4Database()

        val database = Room.databaseBuilder(context, CouponDatabase::class.java, databaseName)
            .addMigrations(CouponDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val couponId = database.couponDao().insertCoupon(
            Coupon(
                storeName = "Null Expiry Store",
                description = "No expiry provided",
                expiryDate = null,
                cashbackAmount = 0.0,
                redeemCode = null,
                imageUri = null,
                status = "Active"
            )
        )

        val inserted = database.couponDao().getCouponById(couponId)
        assertNotNull(inserted)
        assertNull(inserted?.expiryDate)

        database.close()
    }

    @Test
    fun migrate5To6_classifiesPercentAndAmountCashbackCorrectly() = runBlocking {
        createVersion5DatabaseWithCashbackSamples()

        val database = Room.databaseBuilder(context, CouponDatabase::class.java, databaseName)
            .addMigrations(CouponDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()

        val percentCoupon = database.couponDao().getCouponById(1)
        val amountCoupon = database.couponDao().getCouponById(2)

        assertNotNull(percentCoupon)
        assertEquals("percent", percentCoupon?.cashbackType)
        assertEquals(20.0, percentCoupon?.cashbackValueNum ?: error("Expected percent cashback value"), 0.0)
        assertEquals("20% Off", percentCoupon?.offerText)

        assertNotNull(amountCoupon)
        assertEquals("amount", amountCoupon?.cashbackType)
        assertEquals(75.0, amountCoupon?.cashbackValueNum ?: error("Expected amount cashback value"), 0.0)
        assertEquals("₹75", amountCoupon?.offerText)
        assertEquals("INR", amountCoupon?.cashbackCurrency)

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

    private fun createVersion4Database() {
        context.deleteDatabase(databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `coupons` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `storeName` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `normalizedDescription` TEXT,
                            `expiryDate` INTEGER NOT NULL,
                            `cashbackAmount` REAL NOT NULL,
                            `redeemCode` TEXT,
                            `imageUri` TEXT,
                            `imagePhash` TEXT,
                            `imageSignature` TEXT,
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
            put("storeName", "Version 4 Store")
            put("description", "Existing expiry date")
            put("normalizedDescription", "existing-expiry-date")
            put("expiryDate", now)
            put("cashbackAmount", 10.0)
            put("redeemCode", "V4CODE")
            put("imageUri", null as String?)
            put("imagePhash", null as String?)
            put("imageSignature", null as String?)
            put("category", "Legacy")
            put("status", "Active")
            put("minimumPurchase", null as Double?)
            put("maximumDiscount", null as Double?)
            put("isPriority", 0)
            put("paymentMethod", null as String?)
            put("usageLimit", null as Int?)
            put("usageCount", 0)
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

    private fun createVersion5DatabaseWithCashbackSamples() {
        context.deleteDatabase(databaseName)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `coupons` (
                            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            `storeName` TEXT NOT NULL,
                            `description` TEXT NOT NULL,
                            `normalizedDescription` TEXT,
                            `expiryDate` INTEGER,
                            `cashbackAmount` REAL NOT NULL,
                            `redeemCode` TEXT,
                            `imageUri` TEXT,
                            `imagePhash` TEXT,
                            `imageSignature` TEXT,
                            `category` TEXT,
                            `status` TEXT,
                            `minimumPurchase` REAL,
                            `maximumDiscount` REAL,
                            `isPriority` INTEGER NOT NULL DEFAULT 0,
                            `paymentMethod` TEXT,
                            `usageLimit` INTEGER,
                            `usageCount` INTEGER NOT NULL DEFAULT 0,
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
                    // No-op for test schema
                }
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = openHelper.writableDatabase
        val now = System.currentTimeMillis()

        fun insertCoupon(id: Long, storeName: String, description: String, amount: Double) {
            val values = ContentValues().apply {
                put("id", id)
                put("storeName", storeName)
                put("description", description)
                put("normalizedDescription", CouponDedupUtils.normalizeDescription(description))
                put("expiryDate", now)
                put("cashbackAmount", amount)
                put("redeemCode", null as String?)
                put("imageUri", null as String?)
                put("imagePhash", null as String?)
                put("imageSignature", null as String?)
                put("category", "Test")
                put("status", "Active")
                put("minimumPurchase", null as Double?)
                put("maximumDiscount", null as Double?)
                put("isPriority", 0)
                put("paymentMethod", null as String?)
                put("usageLimit", null as Int?)
                put("usageCount", 0)
                put("reminderDate", null as Long?)
                put("platformType", null as String?)
                put("rating", null as String?)
                put("createdAt", now)
                put("updatedAt", now)
            }

            db.insert("coupons", SupportSQLiteDatabase.CONFLICT_ABORT, values)
        }

        insertCoupon(
            id = 1,
            storeName = "Percent Store",
            description = "Save 20% off your purchase",
            amount = 20.0
        )

        insertCoupon(
            id = 2,
            storeName = "Cashback Store",
            description = "Get ₹75 cashback on orders",
            amount = 75.0
        )

        db.close()
        openHelper.close()
    }
}

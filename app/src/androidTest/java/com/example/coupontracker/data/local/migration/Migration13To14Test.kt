package com.example.coupontracker.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.data.local.CouponDatabase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration13To14Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CouponDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate13To14() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL(
                "INSERT INTO coupons (storeName, description, createdAt, updatedAt, " +
                    "needsAttention, isPriority, usageCount) " +
                    "VALUES ('AJIO', 'Flat 50% off', 0, 0, 0, 0, 0)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB,
            14,
            true,
            CouponDatabase.MIGRATION_13_14
        )

        val cursor = migrated.query(
            "SELECT redeemCodes, primaryRedeemCode, storeUrl, offerType " +
                "FROM coupons WHERE storeName = 'AJIO'"
        )
        assertNotNull(cursor)
        cursor.use {
            assertTrue("Inserted row must survive migration", it.moveToFirst())
            assertTrue("redeemCodes column null on existing rows", it.isNull(0))
            assertTrue("primaryRedeemCode column null on existing rows", it.isNull(1))
            assertTrue("storeUrl column null on existing rows", it.isNull(2))
            assertTrue("offerType column null on existing rows", it.isNull(3))
        }
        migrated.close()
    }

    companion object {
        private const val TEST_DB = "migration-13-14-test"
    }
}

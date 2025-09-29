package com.example.coupontracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils

@Database(entities = [Coupon::class], version = 6)
@TypeConverters(Converters::class)
abstract class CouponDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao

    companion object {
        const val DATABASE_NAME = "coupon_database"

        // Migration from version 2 to 3 (adding new coupon fields)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns to the coupons table
                database.execSQL("ALTER TABLE coupons ADD COLUMN minimumPurchase REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN maximumDiscount REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN isPriority INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE coupons ADD COLUMN paymentMethod TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN usageLimit INTEGER")
                database.execSQL("ALTER TABLE coupons ADD COLUMN usageCount INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE coupons ADD COLUMN reminderDate INTEGER")
                database.execSQL("ALTER TABLE coupons ADD COLUMN platformType TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE coupons ADD COLUMN normalizedDescription TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN imagePhash TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN imageSignature TEXT")

                database.query("SELECT id, description FROM coupons").use { cursor ->
                    val updateStatement = database.compileStatement(
                        "UPDATE coupons SET normalizedDescription = ? WHERE id = ?"
                    )
                    try {
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val description = cursor.getString(1)
                            val normalized = CouponDedupUtils.normalizeDescription(description)
                            updateStatement.bindString(1, normalized)
                            updateStatement.bindLong(2, id)
                            updateStatement.executeUpdateDelete()
                            updateStatement.clearBindings()
                        }
                    } finally {
                        updateStatement.close()
                    }
                }

                // The legacy database only stored URIs to coupon images, so we cannot
                // backfill perceptual hashes or signatures for existing rows. The new
                // columns remain null until fresh image data is processed.
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `coupons_new` (
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
                    """.trimIndent()
                )

                database.execSQL(
                    """
                    INSERT INTO `coupons_new` (
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `imageUri`, `imagePhash`, `imageSignature`,
                        `category`, `status`, `minimumPurchase`, `maximumDiscount`, `isPriority`,
                        `paymentMethod`, `usageLimit`, `usageCount`, `reminderDate`, `platformType`,
                        `rating`, `createdAt`, `updatedAt`
                    )
                    SELECT
                        `id`, `storeName`, `description`, `normalizedDescription`, `expiryDate`,
                        `cashbackAmount`, `redeemCode`, `imageUri`, `imagePhash`, `imageSignature`,
                        `category`, `status`, `minimumPurchase`, `maximumDiscount`, `isPriority`,
                        `paymentMethod`, `usageLimit`, `usageCount`, `reminderDate`, `platformType`,
                        `rating`, `createdAt`, `updatedAt`
                    FROM `coupons`
                    """.trimIndent()
                )

                database.execSQL("DROP TABLE `coupons`")
                database.execSQL("ALTER TABLE `coupons_new` RENAME TO `coupons`")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new typed cashback fields
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackType TEXT")
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackValueNum REAL")
                database.execSQL("ALTER TABLE coupons ADD COLUMN cashbackCurrency TEXT DEFAULT 'INR'")
                database.execSQL("ALTER TABLE coupons ADD COLUMN offerText TEXT")

                // Migrate existing cashbackAmount data to typed fields
                // This query attempts to detect percentages vs amounts based on value and description
                database.execSQL("""
                    UPDATE coupons SET 
                        cashbackType = CASE 
                            WHEN cashbackAmount <= 100 AND (description LIKE '%off%' OR description LIKE '%%' OR description LIKE '%percent%') THEN 'percent'
                            ELSE 'amount'
                        END,
                        cashbackValueNum = cashbackAmount,
                        offerText = CASE
                            WHEN cashbackAmount <= 100 AND (description LIKE '%off%' OR description LIKE '%%') THEN 
                                CAST(CAST(cashbackAmount AS INTEGER) AS TEXT) || '% Off'
                            ELSE 
                                '₹' || CAST(CAST(cashbackAmount AS INTEGER) AS TEXT)
                        END
                    WHERE cashbackType IS NULL
                """)
            }
        }
    }
}

object Converters {
    @androidx.room.TypeConverter
    fun fromTimestamp(value: Long?): java.util.Date? {
        return value?.let { java.util.Date(it) }
    }

    @androidx.room.TypeConverter
    fun dateToTimestamp(date: java.util.Date?): Long? {
        return date?.time
    }
}
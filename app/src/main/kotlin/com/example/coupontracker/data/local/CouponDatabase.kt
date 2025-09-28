package com.example.coupontracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.util.CouponDedupUtils

@Database(entities = [Coupon::class], version = 4)
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
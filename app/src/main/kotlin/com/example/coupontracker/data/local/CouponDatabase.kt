package com.example.coupontracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.coupontracker.data.model.Coupon

@Database(entities = [Coupon::class], version = 3)
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
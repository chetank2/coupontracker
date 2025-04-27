package com.example.coupontracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.coupontracker.data.model.Coupon

@Database(entities = [Coupon::class], version = 2)
@TypeConverters(Converters::class)
abstract class CouponDatabase : RoomDatabase() {
    abstract fun couponDao(): CouponDao

    companion object {
        const val DATABASE_NAME = "coupon_database"
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
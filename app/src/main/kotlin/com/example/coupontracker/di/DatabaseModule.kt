package com.example.coupontracker.di

import android.content.Context
import androidx.room.Room
import com.example.coupontracker.data.local.CouponDatabase
import com.example.coupontracker.data.local.CouponDao
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.repository.CouponRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideCouponDatabase(
        @ApplicationContext context: Context
    ): CouponDatabase {
        return Room.databaseBuilder(
            context,
            CouponDatabase::class.java,
            "coupon_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideCouponDao(database: CouponDatabase) = database.couponDao()

    @Provides
    @Singleton
    fun provideCouponRepository(
        couponDao: CouponDao
    ): CouponRepository = CouponRepositoryImpl(couponDao)
} 
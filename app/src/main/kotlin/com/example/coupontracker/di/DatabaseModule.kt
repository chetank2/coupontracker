package com.example.coupontracker.di

import android.content.Context
import androidx.room.Room
import com.example.coupontracker.data.local.*
import com.example.coupontracker.data.repository.CouponReminderScheduler
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
            CouponDatabase.DATABASE_NAME
        )
            .addMigrations(
                CouponDatabase.MIGRATION_2_3,
                CouponDatabase.MIGRATION_3_4,
                CouponDatabase.MIGRATION_4_5,
                CouponDatabase.MIGRATION_5_6,
                CouponDatabase.MIGRATION_6_7,  // V2: Pattern learning and feedback tables
                CouponDatabase.MIGRATION_7_8,  // Drop deprecated offerText column
                CouponDatabase.MIGRATION_8_9,
                CouponDatabase.MIGRATION_9_10,
                CouponDatabase.MIGRATION_10_11,
                CouponDatabase.MIGRATION_11_12,
                CouponDatabase.MIGRATION_12_13,
                CouponDatabase.MIGRATION_13_14,
                CouponDatabase.MIGRATION_14_15,
                CouponDatabase.MIGRATION_15_16
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideCouponDao(database: CouponDatabase) = database.couponDao()
    
    // V2: Provide pattern DAO
    @Provides
    @Singleton
    fun provideLearnedPatternDao(database: CouponDatabase): LearnedPatternDao = 
        database.learnedPatternDao()
    
    // V2: Provide feedback DAO
    @Provides
    @Singleton
    fun provideExtractionFeedbackDao(database: CouponDatabase): ExtractionFeedbackDao =
        database.extractionFeedbackDao()

    @Provides
    @Singleton
    fun provideValidatorFeedbackDao(database: CouponDatabase): ValidatorFeedbackDao =
        database.validatorFeedbackDao()

    @Provides
    @Singleton
    fun provideCouponRepository(
        couponDao: CouponDao,
        reminderScheduler: CouponReminderScheduler
    ): CouponRepository = CouponRepositoryImpl(couponDao, reminderScheduler)
}

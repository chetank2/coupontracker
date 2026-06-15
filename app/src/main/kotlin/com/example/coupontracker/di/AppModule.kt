package com.example.coupontracker.di

import android.content.Context
import android.content.SharedPreferences
// ImageProcessor import removed - now provided by LlmModule
// SecurePreferencesManager import removed - now provided by LlmModule
import com.example.coupontracker.extraction.MultiCouponExtractionService
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.util.CouponInputManager
import com.example.coupontracker.util.ImageProcessor
import com.example.coupontracker.util.ThemeManager
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }

    // SecurePreferencesManager now provided by LlmModule

    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        // Keep this for backward compatibility, but new code should use SecurePreferencesManager
        return context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    // ImageProcessor now provided by LlmModule with proper dependencies

    @Provides
    @Singleton
    fun provideThemeManager(
        sharedPreferences: SharedPreferences
    ): ThemeManager {
        return ThemeManager(sharedPreferences)
    }

    @Provides
    @Singleton
    fun provideCouponInputManager(
        @ApplicationContext context: Context,
        imageProcessor: ImageProcessor,
        multiCouponExtractionService: MultiCouponExtractionService,
        ocrFirstCouponExtractor: OcrFirstCouponExtractor
    ): CouponInputManager {
        return CouponInputManager(
            context = context,
            imageProcessor = imageProcessor,
            multiCouponExtractionService = multiCouponExtractionService,
            ocrFirstCouponExtractor = ocrFirstCouponExtractor
        )
    }

    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager = WorkManager.getInstance(context)
}

package com.example.coupontracker.di

import android.content.Context
import android.content.SharedPreferences
import com.example.coupontracker.util.ImageProcessor
import com.example.coupontracker.util.SecurePreferencesManager
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
    fun provideSecurePreferencesManager(
        @ApplicationContext context: Context
    ): SecurePreferencesManager {
        val securePreferencesManager = SecurePreferencesManager(context)
        // Initialize the manager (migrate legacy preferences if needed)
        securePreferencesManager.initialize()
        return securePreferencesManager
    }

    @Provides
    @Singleton
    fun provideSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        // Keep this for backward compatibility, but new code should use SecurePreferencesManager
        return context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }

    @Provides
    @Singleton
    fun provideImageProcessor(
        @ApplicationContext context: Context
    ): ImageProcessor {
        // Create a new ImageProcessor that uses the model-based approach
        return ImageProcessor(context)
    }
}
package com.example.coupontracker.di

import android.content.Context
import android.content.SharedPreferences
import com.example.coupontracker.ui.screen.KEY_GOOGLE_CLOUD_VISION_API_KEY
import com.example.coupontracker.ui.screen.KEY_MISTRAL_API_KEY
import com.example.coupontracker.util.ImageProcessor
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
    fun provideSharedPreferences(
        @ApplicationContext context: Context
    ): SharedPreferences {
        return context.getSharedPreferences("coupon_tracker_prefs", Context.MODE_PRIVATE)
    }
    
    @Provides
    @Singleton
    fun provideImageProcessor(
        @ApplicationContext context: Context,
        sharedPreferences: SharedPreferences
    ): ImageProcessor {
        val googleCloudVisionApiKey = sharedPreferences.getString(KEY_GOOGLE_CLOUD_VISION_API_KEY, null)
        val mistralApiKey = sharedPreferences.getString(KEY_MISTRAL_API_KEY, null)
        
        // Create a new ImageProcessor that listens for preference changes
        return ImageProcessor(context, googleCloudVisionApiKey, mistralApiKey)
    }
} 
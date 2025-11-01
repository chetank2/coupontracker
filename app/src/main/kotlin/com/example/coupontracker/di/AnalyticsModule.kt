package com.example.coupontracker.di

import android.content.Context
import com.example.coupontracker.analytics.TelemetryClient
import com.example.coupontracker.util.AnalyticsTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for providing analytics-related dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalyticsModule {
    
    /**
     * Provides the AnalyticsTracker as a singleton
     */
    @Provides
    @Singleton
    fun provideAnalyticsTracker(@ApplicationContext context: Context): AnalyticsTracker {
        return AnalyticsTracker(context)
    }

    @Provides
    @Singleton
    fun provideTelemetryClient(
        @ApplicationContext context: Context,
        analyticsTracker: AnalyticsTracker
    ): TelemetryClient {
        return TelemetryClient.getInstance(context, analyticsTracker)
    }
}

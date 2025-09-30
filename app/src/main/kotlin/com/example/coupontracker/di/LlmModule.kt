package com.example.coupontracker.di

import android.content.Context
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.example.coupontracker.llm.ModelDownloadManager
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.ImageProcessor
import com.example.coupontracker.util.SecurePreferencesManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for LLM-related dependencies
 * Provides shared instances for proper resource management
 */
@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmRuntimeManager(
        @ApplicationContext context: Context
    ): LlmRuntimeManager {
        return LlmRuntimeManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideModelDownloadManager(
        @ApplicationContext context: Context
    ): ModelDownloadManager {
        return ModelDownloadManager(context)
    }

    @Provides
    @Singleton
    fun provideLlmTelemetryService(
        @ApplicationContext context: Context,
        analyticsTracker: com.example.coupontracker.util.AnalyticsTracker
    ): LlmTelemetryService {
        return LlmTelemetryService.getInstance(context, analyticsTracker)
    }

    @Provides
    @Singleton
    fun provideLocalLlmOcrService(
        @ApplicationContext context: Context,
        ocrEngine: com.example.coupontracker.ocr.OcrEngine,
        llmRuntimeManager: LlmRuntimeManager
    ): LocalLlmOcrService {
        return LocalLlmOcrService(context, ocrEngine, llmRuntimeManager)
    }

    @Provides
    @Singleton
    fun provideImageProcessor(
        @ApplicationContext context: Context,
        localLlmOcrService: LocalLlmOcrService
    ): ImageProcessor {
        return ImageProcessor(context, localLlmOcrService)
    }

    @Provides
    @Singleton
    fun provideSecurePreferencesManager(
        @ApplicationContext context: Context
    ): SecurePreferencesManager {
        return SecurePreferencesManager(context).apply {
            initialize()
        }
    }
}

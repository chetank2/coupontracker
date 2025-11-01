package com.example.coupontracker.di

import android.content.Context
import com.example.coupontracker.analytics.TelemetryClient
import com.example.coupontracker.feedback.ValidatorFeedbackRecorder
import com.example.coupontracker.llm.LlmRuntimeManager
import com.example.coupontracker.llm.LlmTelemetryService
import com.example.coupontracker.llm.ModelDownloadManager
import com.example.coupontracker.util.ImageProcessor
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.ExtractionTelemetryService
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
        @ApplicationContext context: Context,
        securePreferencesManager: SecurePreferencesManager
    ): ModelDownloadManager {
        return ModelDownloadManager(context, securePreferencesManager)
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
        llmRuntimeManager: LlmRuntimeManager,
        validatorFeedbackRecorder: ValidatorFeedbackRecorder,
        telemetryClient: TelemetryClient
    ): LocalLlmOcrService {
        return LocalLlmOcrService(
            context = context,
            ocrEngine = ocrEngine,
            injectedLlmRuntimeManager = llmRuntimeManager,
            validatorFeedbackRecorder = validatorFeedbackRecorder,
            injectedTelemetryClient = telemetryClient
        )
    }

    @Provides
    @Singleton
    fun provideImageProcessor(
        @ApplicationContext context: Context,
        ocrEngine: com.example.coupontracker.ocr.OcrEngine,
        telemetryService: ExtractionTelemetryService,
        localLlmOcrService: LocalLlmOcrService,
        progressiveExtractionService: com.example.coupontracker.extraction.ProgressiveExtractionService
    ): ImageProcessor {
        return ImageProcessor(context, ocrEngine, telemetryService, localLlmOcrService, progressiveExtractionService)
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
    
    @Provides
    @Singleton
    fun provideGgufModelLoader(
        @ApplicationContext context: Context
    ): com.example.coupontracker.llm.GgufModelLoader {
        return com.example.coupontracker.llm.GgufModelLoader(context)
    }
    
    @Provides
    @Singleton
    fun provideSecureModelDownloader(
        @ApplicationContext context: Context
    ): com.example.coupontracker.network.SecureModelDownloader {
        return com.example.coupontracker.network.SecureModelDownloader(context)
    }
}

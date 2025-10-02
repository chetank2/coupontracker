package com.example.coupontracker.di

import android.content.Context
import com.example.coupontracker.data.local.LearnedPatternDao
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.universal.AdaptiveConfidenceScorer
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.universal.UniversalFieldDetector
import com.example.coupontracker.universal.UniversalLayoutAnalyzer
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for universal extraction components
 * V2: Updated to provide LearnedPatternDao dependency
 */
@Module
@InstallIn(SingletonComponent::class)
object UniversalExtractionModule {

    @Provides
    @Singleton
    fun provideUniversalLayoutAnalyzer(): UniversalLayoutAnalyzer {
        return UniversalLayoutAnalyzer()
    }

    // V2: Removed manual construction - PatternLearningEngine now uses @Inject constructor
    // Dagger will automatically provide it with required dependencies (Context, LearnedPatternDao)

    @Provides
    @Singleton
    fun provideAdaptiveConfidenceScorer(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): AdaptiveConfidenceScorer {
        return AdaptiveConfidenceScorer(context)
    }

    @Provides
    @Singleton
    fun provideUniversalFieldDetector(
        patternLearner: PatternLearningEngine,
        layoutAnalyzer: UniversalLayoutAnalyzer,
        confidenceScorer: AdaptiveConfidenceScorer,
        ocrEngine: OcrEngine
    ): UniversalFieldDetector {
        return UniversalFieldDetector(patternLearner, layoutAnalyzer, confidenceScorer, ocrEngine)
    }

    @Provides
    @Singleton
    fun provideUniversalExtractionService(
        @ApplicationContext context: Context,
        fieldDetector: UniversalFieldDetector,
        patternLearner: PatternLearningEngine,
        confidenceScorer: AdaptiveConfidenceScorer,
        progressiveExtractionService: com.example.coupontracker.extraction.ProgressiveExtractionService
    ): UniversalExtractionService {
        return UniversalExtractionService(context, fieldDetector, patternLearner, confidenceScorer, progressiveExtractionService)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }
}

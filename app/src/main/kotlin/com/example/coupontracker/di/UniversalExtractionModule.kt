package com.example.coupontracker.di

import com.example.coupontracker.data.local.LearnedPatternDao
import com.example.coupontracker.universal.AdaptiveConfidenceScorer
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.universal.UniversalFieldDetector
import com.example.coupontracker.universal.UniversalLayoutAnalyzer
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
        confidenceScorer: AdaptiveConfidenceScorer
    ): UniversalFieldDetector {
        return UniversalFieldDetector(patternLearner, layoutAnalyzer, confidenceScorer)
    }

    @Provides
    @Singleton
    fun provideUniversalExtractionService(
        fieldDetector: UniversalFieldDetector,
        patternLearner: PatternLearningEngine,
        confidenceScorer: AdaptiveConfidenceScorer
    ): UniversalExtractionService {
        return UniversalExtractionService(fieldDetector, patternLearner, confidenceScorer)
    }

    @Provides
    @Singleton
    fun provideGson(): Gson {
        return Gson()
    }
}

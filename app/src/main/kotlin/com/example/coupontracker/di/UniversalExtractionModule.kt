package com.example.coupontracker.di

import com.example.coupontracker.universal.AdaptiveConfidenceScorer
import com.example.coupontracker.universal.PatternLearningEngine
import com.example.coupontracker.universal.UniversalExtractionService
import com.example.coupontracker.universal.UniversalFieldDetector
import com.example.coupontracker.universal.UniversalLayoutAnalyzer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dagger Hilt module for universal extraction components
 */
@Module
@InstallIn(SingletonComponent::class)
object UniversalExtractionModule {

    @Provides
    @Singleton
    fun provideUniversalLayoutAnalyzer(): UniversalLayoutAnalyzer {
        return UniversalLayoutAnalyzer()
    }

    @Provides
    @Singleton
    fun providePatternLearningEngine(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context
    ): PatternLearningEngine {
        return PatternLearningEngine(context)
    }

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
}

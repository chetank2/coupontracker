package com.example.coupontracker.di

import android.content.Context
import com.example.coupontracker.extraction.*
import com.example.coupontracker.learning.ExtractionLearningIntegration
import com.example.coupontracker.learning.ParameterChangeLogger
import com.example.coupontracker.universal.PatternLearningEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for progressive extraction components
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtractionModule {
    
    @Provides
    @Singleton
    fun provideStructuredFieldExtractor(): StructuredFieldExtractor {
        return StructuredFieldExtractor()
    }
    
    @Provides
    @Singleton
    fun provideSemanticFieldExtractor(): SemanticFieldExtractor {
        return SemanticFieldExtractor()
    }
    
    @Provides
    @Singleton
    fun provideHeuristicFieldExtractor(): HeuristicFieldExtractor {
        return HeuristicFieldExtractor()
    }
    
    @Provides
    @Singleton
    fun provideDefaultFieldProvider(): DefaultFieldProvider {
        return DefaultFieldProvider()
    }
    
    @Provides
    @Singleton
    fun provideParameterChangeLogger(
        @ApplicationContext context: Context
    ): ParameterChangeLogger {
        return ParameterChangeLogger(context)
    }
    
    @Provides
    @Singleton
    fun provideExtractionLearningIntegration(
        patternLearningEngine: PatternLearningEngine,
        parameterChangeLogger: ParameterChangeLogger
    ): ExtractionLearningIntegration {
        return ExtractionLearningIntegration(patternLearningEngine, parameterChangeLogger)
    }
    
    @Provides
    @Singleton
    fun provideProgressiveExtractionService(
        structuredExtractor: StructuredFieldExtractor,
        semanticExtractor: SemanticFieldExtractor,
        heuristicExtractor: HeuristicFieldExtractor,
        learnedPatternEngine: PatternLearningEngine,
        defaultProvider: DefaultFieldProvider,
        localLlmOcrService: com.example.coupontracker.util.LocalLlmOcrService,
        extractionLearningIntegration: ExtractionLearningIntegration
    ): ProgressiveExtractionService {
        return ProgressiveExtractionService(
            structuredExtractor,
            semanticExtractor,
            heuristicExtractor,
            learnedPatternEngine,
            defaultProvider,
            localLlmOcrService,
            extractionLearningIntegration
        )
    }

    @Provides
    @Singleton
    fun provideConfidenceScorer(): ConfidenceScorer {
        return ConfidenceScorer()
    }

    @Provides
    @Singleton
    fun provideExtractionValidator(
        confidenceScorer: ConfidenceScorer
    ): ExtractionValidator {
        return ExtractionValidator(confidenceScorer)
    }

    @Provides
    @Singleton
    fun provideMultiCouponExtractionService(
        @ApplicationContext context: Context,
        ocrEngine: com.example.coupontracker.ocr.OcrEngine,
        progressiveExtractionService: ProgressiveExtractionService,
        confidenceScorer: ConfidenceScorer,
        extractionValidator: ExtractionValidator
    ): MultiCouponExtractionService {
        return MultiCouponExtractionService(
            context = context,
            ocrEngine = ocrEngine,
            progressiveExtractionService = progressiveExtractionService,
            confidenceScorer = confidenceScorer,
            extractionValidator = extractionValidator
        )
    }
}


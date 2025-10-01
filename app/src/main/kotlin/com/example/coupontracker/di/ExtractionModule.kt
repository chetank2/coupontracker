package com.example.coupontracker.di

import com.example.coupontracker.extraction.*
import com.example.coupontracker.universal.PatternLearningEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
    fun provideProgressiveExtractionService(
        structuredExtractor: StructuredFieldExtractor,
        semanticExtractor: SemanticFieldExtractor,
        heuristicExtractor: HeuristicFieldExtractor,
        learnedPatternEngine: PatternLearningEngine,
        defaultProvider: DefaultFieldProvider
    ): ProgressiveExtractionService {
        return ProgressiveExtractionService(
            structuredExtractor,
            semanticExtractor,
            heuristicExtractor,
            learnedPatternEngine,
            defaultProvider
        )
    }
}


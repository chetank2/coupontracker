package com.example.coupontracker.di

import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.MlKitOcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for OCR engine
 * 
 * CHANGED: Using ML Kit instead of Tesseract due to Tesseract native init issues.
 * ML Kit is more reliable, fully offline, and has better accuracy.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    
    @Binds
    @Singleton
    abstract fun bindOcrEngine(
        mlKitOcrEngine: MlKitOcrEngine
    ): OcrEngine
}


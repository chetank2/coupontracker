package com.example.coupontracker.di

import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.TesseractOcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {
    
    @Binds
    @Singleton
    abstract fun bindOcrEngine(
        tesseractOcrEngine: TesseractOcrEngine
    ): OcrEngine
}


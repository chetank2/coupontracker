package com.example.coupontracker.di

import com.example.coupontracker.ocr.OcrCoordinator
import com.example.coupontracker.ocr.OcrEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for OCR. Production binds OcrEngine → OcrCoordinator, which
 * orchestrates MlKitOcrEngine (primary) and TesseractOcrEngine (fallback).
 * Direct-injection sites that need one engine specifically can still inject
 * MlKitOcrEngine or TesseractOcrEngine by concrete type.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(coordinator: OcrCoordinator): OcrEngine
}

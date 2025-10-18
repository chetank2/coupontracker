package com.example.coupontracker.di

import com.example.coupontracker.data.repository.FeedbackDatasetRepository
import com.example.coupontracker.data.repository.FeedbackDatasetRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FeedbackModule {
    @Binds
    @Singleton
    abstract fun bindFeedbackDatasetRepository(
        impl: FeedbackDatasetRepositoryImpl
    ): FeedbackDatasetRepository
}

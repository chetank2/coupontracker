package com.example.coupontracker.di

import com.example.coupontracker.extraction.model.CouponExtractionModel
import com.example.coupontracker.extraction.model.GemmaTextCouponModel
import com.example.coupontracker.extraction.model.MiniCpmVlmCouponModel
import com.example.coupontracker.extraction.model.QwenTextCouponModel
import com.example.coupontracker.extraction.model.QwenVlmCouponModel
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Registers CouponExtractionModel adapters into a Set multi-binding.
 * ModelSelector consumes the set and picks per role.
 *
 * Adapters land here one-per-plan:
 *   Plan 2: QwenTextCouponModel (TEXT_QWEN)
 *   Plan 4: GemmaTextCouponModel (TEXT_GEMMA)
 *   Plan 5: VLM adapters (VLM_QWEN / VLM_GEMMA / VLM_MINICPM)
 *
 * BENCHMARK_REPLAY is NOT registered in production — tests construct it
 * directly with recordings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ModelModule {

    @Binds
    @IntoSet
    abstract fun bindQwenText(impl: QwenTextCouponModel): CouponExtractionModel

    @Binds
    @IntoSet
    abstract fun bindGemmaText(impl: GemmaTextCouponModel): CouponExtractionModel

    @Binds
    @IntoSet
    abstract fun bindQwenVlm(impl: QwenVlmCouponModel): CouponExtractionModel

    @Binds
    @IntoSet
    abstract fun bindMiniCpmVlm(impl: MiniCpmVlmCouponModel): CouponExtractionModel
}

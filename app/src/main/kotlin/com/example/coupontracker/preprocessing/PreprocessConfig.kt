package com.example.coupontracker.preprocessing

/**
 * Configuration knobs for [ImagePreprocessorCore].
 *
 * All three fields mirror constants that lived in [com.example.coupontracker.util.ImagePreprocessor]:
 *   - [maxDimension] ← MAX_IMAGE_DIMENSION = 1600
 *   - [minDimension] ← MIN_IMAGE_DIMENSION = 800
 *   - [jpegQuality]  ← JPEG_QUALITY = 90
 */
data class PreprocessConfig(
    val maxDimension: Int,
    val minDimension: Int,
    val jpegQuality: Int,
) {
    companion object {
        val DEFAULT = PreprocessConfig(
            maxDimension = 1600,
            minDimension = 800,
            jpegQuality = 90,
        )
    }
}

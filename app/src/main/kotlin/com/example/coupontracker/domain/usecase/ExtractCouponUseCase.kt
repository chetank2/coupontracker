package com.example.coupontracker.domain.usecase

import android.graphics.Bitmap
import com.example.coupontracker.extraction.capture.OcrFirstCouponExtractor
import com.example.coupontracker.extraction.capture.OcrFirstExtractionResult
import java.util.Date
import javax.inject.Inject

class ExtractCouponUseCase @Inject constructor(
    private val extractor: OcrFirstCouponExtractor
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        imageUri: String?,
        captureTimestamp: Date? = null
    ): OcrFirstExtractionResult {
        return extractor.extract(
            bitmap = bitmap,
            imageUri = imageUri,
            captureTimestamp = captureTimestamp
        )
    }
}

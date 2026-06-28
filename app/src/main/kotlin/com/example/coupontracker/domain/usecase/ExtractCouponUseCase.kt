package com.example.coupontracker.domain.usecase

import android.graphics.Bitmap
import com.example.coupontracker.extraction.TextBlock
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
    ): OcrFirstExtractionResult = extract(
        ExtractCouponRequest.BitmapInput(
            bitmap = bitmap,
            imageUri = imageUri,
            captureTimestamp = captureTimestamp
        )
    )

    suspend fun extract(request: ExtractCouponRequest): OcrFirstExtractionResult {
        return when (request) {
            is ExtractCouponRequest.BitmapInput -> extractor.extract(
                bitmap = request.bitmap,
                imageUri = request.imageUri,
                captureTimestamp = request.captureTimestamp
            )
            is ExtractCouponRequest.ScopedOcrInput -> extractor.extractFromOcr(
                bitmap = request.bitmap,
                ocrText = request.ocrText,
                ocrHints = request.ocrHints,
                ocrBlocks = request.ocrBlocks,
                imageUri = request.imageUri,
                captureTimestamp = request.captureTimestamp
            )
        }
    }
}

sealed class ExtractCouponRequest {
    abstract val bitmap: Bitmap
    abstract val imageUri: String?
    abstract val captureTimestamp: Date?

    data class BitmapInput(
        override val bitmap: Bitmap,
        override val imageUri: String?,
        override val captureTimestamp: Date? = null
    ) : ExtractCouponRequest()

    data class ScopedOcrInput(
        override val bitmap: Bitmap,
        val ocrText: String,
        val ocrHints: Map<String, String> = emptyMap(),
        val ocrBlocks: List<TextBlock> = emptyList(),
        override val imageUri: String?,
        override val captureTimestamp: Date? = null
    ) : ExtractCouponRequest()
}

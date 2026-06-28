package com.example.coupontracker.extraction.capture

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.coupontracker.data.model.Coupon
import javax.inject.Inject

/**
 * Runs selected batch capture inputs through the per-item capture router and
 * keeps batch status accounting out of the ViewModel.
 */
class BatchCaptureOrchestrator @Inject constructor(
    private val itemProcessor: BatchCaptureItemProcessor
) {

    suspend fun process(
        inputs: List<BatchCaptureInput>,
        decodeBitmap: (Uri) -> Bitmap?,
        trackBitmap: (Bitmap) -> Unit,
        releaseBitmap: (Bitmap) -> Unit,
        processPdf: suspend (Uri) -> Coupon,
        extractImageCoupons: suspend (Uri, Bitmap) -> List<Coupon>,
        onItemStarted: (BatchCaptureInput) -> Unit = {},
        onItemFinished: (BatchCaptureProgress) -> Unit = {}
    ): BatchCaptureOrchestrationResult {
        val processedCoupons = mutableListOf<Coupon>()
        val itemStatuses = mutableListOf<BatchCaptureItemStatus>()
        var failedCount = 0

        for ((index, input) in inputs.withIndex()) {
            onItemStarted(input)
            try {
                if (input.isPdf()) {
                    Log.d(TAG, "Batch: Processing PDF ${input.displayName}")
                } else if (!input.isImage()) {
                    Log.w(TAG, "Batch: Unsupported file type ${input.mimeType} for uri=${input.uri}")
                }

                val itemResult = itemProcessor.process(
                    input = input,
                    decodeBitmap = decodeBitmap,
                    trackBitmap = trackBitmap,
                    releaseBitmap = releaseBitmap,
                    processPdf = processPdf,
                    extractImageCoupons = extractImageCoupons
                )

                if (itemResult.success) {
                    processedCoupons.addAll(itemResult.coupons)
                    if (input.isImage()) {
                        Log.d(TAG, "Batch: Extracted ${itemResult.couponsFound} coupon(s) from image ${index + 1}/${inputs.size}")
                    }
                } else {
                    failedCount++
                }

                if (itemResult.message == "Unable to open image") {
                    Log.e(TAG, "Batch: Failed to decode bitmap ${index + 1}")
                }
                if (itemResult.message == "No coupons detected") {
                    Log.w(TAG, "Batch: No coupons extracted from image ${index + 1}/${inputs.size}")
                }

                itemStatuses.add(
                    BatchCaptureItemStatus(
                        input = input,
                        success = itemResult.success,
                        message = itemResult.message,
                        couponsFound = itemResult.couponsFound
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Batch: Error processing ${index + 1}/${inputs.size}", e)
                failedCount++
                itemStatuses.add(
                    BatchCaptureItemStatus(
                        input = input,
                        success = false,
                        message = e.message ?: "Unexpected error"
                    )
                )
            }

            onItemFinished(
                BatchCaptureProgress(
                    processedCount = index + 1,
                    itemStatuses = itemStatuses.toList()
                )
            )
        }

        return BatchCaptureOrchestrationResult(
            coupons = processedCoupons,
            itemStatuses = itemStatuses,
            errorMessage = buildStatusMessage(inputs, itemStatuses, failedCount)
        )
    }

    private fun buildStatusMessage(
        inputs: List<BatchCaptureInput>,
        itemStatuses: List<BatchCaptureItemStatus>,
        failedCount: Int
    ): String? {
        val failedItems = itemStatuses.filterNot { it.success }
        return when {
            failedCount == 0 -> null
            failedCount < inputs.size -> {
                val failedNames = failedItems.joinToString { it.input.displayName }
                "Processed ${inputs.size - failedCount} of ${inputs.size} files. Issues with: $failedNames"
            }
            else -> "Failed to process any files."
        }
    }

    private companion object {
        private const val TAG = "BatchCaptureOrchestrator"
    }
}

data class BatchCaptureOrchestrationResult(
    val coupons: List<Coupon>,
    val itemStatuses: List<BatchCaptureItemStatus>,
    val errorMessage: String?
)

data class BatchCaptureItemStatus(
    val input: BatchCaptureInput,
    val success: Boolean,
    val message: String?,
    val couponsFound: Int = 0
)

data class BatchCaptureProgress(
    val processedCount: Int,
    val itemStatuses: List<BatchCaptureItemStatus>
)

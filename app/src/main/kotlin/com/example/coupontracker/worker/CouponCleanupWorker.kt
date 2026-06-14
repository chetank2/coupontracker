package com.example.coupontracker.worker

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import com.example.coupontracker.data.util.CouponDedupUtils
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.LocalLlmOcrService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Date

@HiltWorker
class CouponCleanupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val couponRepository: CouponRepository,
    private val localLlmOcrService: LocalLlmOcrService,
    private val ocrEngine: OcrEngine
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val couponId = inputData.getLong(KEY_COUPON_ID, 0L)
        if (couponId <= 0L) return Result.failure()

        val coupon = couponRepository.getCouponById(couponId) ?: return Result.success()
        couponRepository.updateCoupon(
            coupon.copy(
                cleanupStatus = Coupon.CleanupStatus.RUNNING,
                cleanupStartedAt = Date(),
                cleanupFinishedAt = null,
                cleanupError = null,
                updatedAt = Date()
            )
        )

        return try {
            val ocrText = coupon.rawOcrText?.takeIf { it.isNotBlank() }
                ?: extractOcrText(coupon.imageUri)
            if (ocrText.isNullOrBlank()) {
                markFailed(couponId, "Saved OCR text is unavailable")
                return Result.success()
            }

            val result = withTimeoutOrNull(CLEANUP_TIMEOUT_MS) {
                localLlmOcrService.processCouponOcrTextTyped(ocrText)
            }

            when (result) {
                is ExtractResult.Good -> {
                    val current = couponRepository.getCouponById(couponId) ?: return Result.success()
                    val cleaned = mergeCleanedCoupon(current, result.info)
                    couponRepository.updateCoupon(cleaned)
                    Result.success()
                }

                is ExtractResult.LowQuality -> {
                    markFailed(couponId, "Reader could not improve this coupon")
                    Result.success()
                }

                is ExtractResult.Failed -> {
                    markFailed(couponId, userFacingFailure(result.error.message))
                    Result.success()
                }

                null -> {
                    markFailed(couponId, "Reader timed out")
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Coupon cleanup failed", t)
            markFailed(couponId, userFacingFailure(t.message))
            Result.success()
        }
    }

    private suspend fun decodeBitmap(imageUri: String?) = withContext(Dispatchers.IO) {
        if (imageUri.isNullOrBlank()) return@withContext null
        runCatching {
            applicationContext.contentResolver.openInputStream(Uri.parse(imageUri))?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()
    }

    private suspend fun extractOcrText(imageUri: String?): String? {
        val bitmap = decodeBitmap(imageUri) ?: return null
        return try {
            withContext(Dispatchers.IO) {
                ocrEngine.recognize(bitmap).takeIf { it.isNotBlank() }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun markFailed(couponId: Long, message: String) {
        val current = couponRepository.getCouponById(couponId) ?: return
        couponRepository.updateCoupon(
            current.copy(
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                cleanupFinishedAt = Date(),
                cleanupError = message,
                updatedAt = Date()
            )
        )
    }

    private fun userFacingFailure(rawMessage: String?): String {
        val message = rawMessage.orEmpty()
        return when {
            message.contains("not available", ignoreCase = true) ||
                message.contains("not installed", ignoreCase = true) ||
                message.contains("not found", ignoreCase = true) -> {
                "Set up the Qwen model in Settings, then try Clean again."
            }
            message.contains("loadModel", ignoreCase = true) ||
                message.contains("initialize", ignoreCase = true) ||
                message.contains("native", ignoreCase = true) -> {
                "Qwen could not start. Remove and set up the model again in Settings."
            }
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) -> {
                "Qwen took too long. The OCR result is still saved; try Clean again later."
            }
            message.isBlank() -> "Reader failed. The OCR result is still saved."
            else -> message
        }
    }

    private fun mergeCleanedCoupon(current: Coupon, info: CouponInfo): Coupon {
        val rawOcr = current.rawOcrText
        val qwenDescription = info.description.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
        val qwenCode = info.redeemCode?.trim()?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val currentCode = current.redeemCode?.trim()?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val selectedCode = when {
            currentCode.isNullOrBlank() && isSupportedByOcr(qwenCode, rawOcr) -> qwenCode
            currentCode.isNullOrBlank() -> qwenCode
            isSupportedByOcr(qwenCode, rawOcr) && qwenCode.equals(currentCode, ignoreCase = true) -> qwenCode
            else -> currentCode
        }

        val selectedStore = info.storeName
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { GenericFieldHeuristics.isGenericOrMissing(current.storeName) || current.storeName == Coupon.Defaults.UNKNOWN_STORE }
            ?: current.storeName

        val descriptionWithCashback = DescriptionUtils.appendDetails(
            qwenDescription ?: current.description,
            info.cashbackDetail
        )

        val merged = current.copy(
            storeName = selectedStore,
            description = descriptionWithCashback,
            expiryDate = current.expiryDate ?: info.expiryDate,
            redeemCode = selectedCode,
            category = current.category ?: info.category,
            status = current.status ?: info.status ?: Coupon.Status.ACTIVE,
            minimumPurchase = current.minimumPurchase ?: info.minimumPurchase,
            maximumDiscount = current.maximumDiscount ?: info.maximumDiscount,
            paymentMethod = current.paymentMethod ?: info.paymentMethod,
            platformType = current.platformType ?: info.platformType,
            usageLimit = current.usageLimit ?: info.usageLimit,
            cleanupStatus = Coupon.CleanupStatus.CLEANED,
            cleanupFinishedAt = Date(),
            cleanupError = null,
            lastCleanedBy = ModelCatalog.COUPON_READER_NAME,
            extractionSource = Coupon.ExtractionSource.QWEN_CLEANED,
            updatedAt = Date()
        )

        val refined = CouponPostProcessor.refine(
            coupon = merged,
            context = CouponFixContext(ocrText = rawOcr)
        )

        return refined.copy(
            cleanupStatus = Coupon.CleanupStatus.CLEANED,
            cleanupFinishedAt = merged.cleanupFinishedAt,
            cleanupError = null,
            lastCleanedBy = merged.lastCleanedBy,
            extractionSource = merged.extractionSource,
            normalizedDescription = CouponDedupUtils.normalizeDescription(refined.description),
            needsAttention = refined.needsAttention && hasMissingCriticalFields(refined)
        )
    }

    private fun hasMissingCriticalFields(coupon: Coupon): Boolean {
        return coupon.storeName == Coupon.Defaults.UNKNOWN_STORE ||
            coupon.description.isBlank() ||
            (coupon.redeemCode.isNullOrBlank() && coupon.expiryDate == null)
    }

    private fun isSupportedByOcr(value: String?, rawOcr: String?): Boolean {
        if (value.isNullOrBlank() || rawOcr.isNullOrBlank()) return false
        return rawOcr.contains(value, ignoreCase = true)
    }

    companion object {
        private const val TAG = "CouponCleanupWorker"
        private const val KEY_COUPON_ID = "coupon_id"
        private const val CLEANUP_TIMEOUT_MS = 60_000L

        fun enqueue(context: Context, couponId: Long) {
            val request = OneTimeWorkRequestBuilder<CouponCleanupWorker>()
                .setInputData(workDataOf(KEY_COUPON_ID to couponId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "coupon_cleanup_$couponId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

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
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponInfo
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.LocalLlmOcrService
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.PostOcrCouponNormalizer
import com.example.coupontracker.util.TextExtractor
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
    private val textExtractor = TextExtractor()

    override suspend fun doWork(): Result {
        val couponId = inputData.getLong(KEY_COUPON_ID, 0L)
        if (couponId <= 0L) return Result.failure()
        if (!inputData.getBoolean(KEY_USER_REQUESTED, false)) {
            Log.w(TAG, "Ignoring cleanup work without explicit user request for couponId=$couponId")
            return Result.success()
        }

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

            buildDeterministicCleanedCoupon(coupon, ocrText)?.let { cleaned ->
                couponRepository.updateCoupon(cleaned)
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

    private fun buildDeterministicCleanedCoupon(current: Coupon, rawOcr: String): Coupon? {
        val info = textExtractor.extractCouponInfoSync(rawOcr, current.createdAt)
        val currentCode = current.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val extractedCode = info.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedCode = extractedCode ?: currentCode

        val extractedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val currentStore = current.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedStore = extractedStore ?: currentStore ?: return null

        val scopedOcr = textExtractor.extractCouponBlockForStore(rawOcr, selectedStore) ?: rawOcr
        val normalized = PostOcrCouponNormalizer.normalize(
            currentDescription = current.description,
            ocrText = scopedOcr,
            storeName = selectedStore,
            redeemCode = selectedCode
        )
        val extractedDescription = info.description.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
        val selectedDescription = normalized.description
            ?: extractedDescription
            ?: current.description.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
            ?: return null

        val selectedExpiry = current.expiryDate ?: info.expiryDate
        if (selectedCode.isNullOrBlank() && selectedExpiry == null) {
            return null
        }

        return current.copy(
            storeName = selectedStore,
            description = selectedDescription,
            expiryDate = selectedExpiry,
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
            lastCleanedBy = "OCR rules",
            extractionSource = current.extractionSource,
            normalizedDescription = CouponDedupUtils.normalizeDescription(selectedDescription),
            needsAttention = normalized.needsAttention,
            updatedAt = Date()
        )
    }

    private fun mergeCleanedCoupon(current: Coupon, info: CouponInfo): Coupon {
        val rawOcr = current.rawOcrText
        val qwenDescription = info.description.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
        val qwenCode = info.redeemCode?.trim()?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val currentCode = current.redeemCode?.trim()?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val qwenCodeSupported = OcrEvidenceValidator.isPhraseSupported(qwenCode, rawOcr)
        val currentCodeSupported = OcrEvidenceValidator.isPhraseSupported(currentCode, rawOcr)
        val selectedCode = when {
            qwenCodeSupported && (currentCode.isNullOrBlank() || qwenCode.equals(currentCode, ignoreCase = true)) -> qwenCode
            currentCodeSupported -> currentCode
            qwenCodeSupported -> qwenCode
            else -> currentCode
        }

        val qwenStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
        val currentStore = current.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
        val qwenStoreSupported = OcrEvidenceValidator.isPhraseSupported(qwenStore, rawOcr)
        val currentStoreSupported = OcrEvidenceValidator.isPhraseSupported(currentStore, rawOcr)
        val selectedStore = when {
            qwenStoreSupported -> qwenStore ?: Coupon.Defaults.UNKNOWN_STORE
            currentStoreSupported -> currentStore ?: Coupon.Defaults.UNKNOWN_STORE
            else -> Coupon.Defaults.UNKNOWN_STORE
        }

        val selectedDescription = qwenDescription ?: current.description

        val merged = current.copy(
            storeName = selectedStore,
            description = selectedDescription,
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
        val refinedStoreSupported = OcrEvidenceValidator.isPhraseSupported(refined.storeName, rawOcr)
        val refinedCode = refined.redeemCode?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
        val refinedCodeSupported = refinedCode.isNullOrBlank() ||
            OcrEvidenceValidator.isPhraseSupported(refinedCode, rawOcr)
        val unsupportedStore = !refinedStoreSupported &&
            !GenericFieldHeuristics.isGenericOrMissing(refined.storeName) &&
            refined.storeName != Coupon.Defaults.UNKNOWN_STORE
        val unsupportedCode = !refinedCodeSupported
        val missingCriticalFields = hasMissingCriticalFields(refined)

        return refined.copy(
            cleanupStatus = if (unsupportedStore || unsupportedCode || missingCriticalFields) {
                Coupon.CleanupStatus.FAILED
            } else {
                Coupon.CleanupStatus.CLEANED
            },
            cleanupFinishedAt = merged.cleanupFinishedAt,
            cleanupError = when {
                unsupportedStore -> OcrEvidenceValidator.unsupportedReason("store", refined.storeName)
                unsupportedCode -> OcrEvidenceValidator.unsupportedReason("code", refinedCode)
                missingCriticalFields -> "Reader could not verify enough coupon details from OCR text."
                else -> null
            },
            lastCleanedBy = if (unsupportedStore || unsupportedCode || missingCriticalFields) null else merged.lastCleanedBy,
            extractionSource = if (unsupportedStore || unsupportedCode || missingCriticalFields) {
                current.extractionSource
            } else {
                merged.extractionSource
            },
            normalizedDescription = CouponDedupUtils.normalizeDescription(refined.description),
            needsAttention = refined.needsAttention || unsupportedStore || unsupportedCode ||
                missingCriticalFields
        )
    }

    private fun hasMissingCriticalFields(coupon: Coupon): Boolean {
        return coupon.storeName == Coupon.Defaults.UNKNOWN_STORE ||
            coupon.description.isBlank() ||
            (coupon.redeemCode.isNullOrBlank() && coupon.expiryDate == null)
    }

    companion object {
        private const val TAG = "CouponCleanupWorker"
        private const val KEY_COUPON_ID = "coupon_id"
        private const val KEY_USER_REQUESTED = "user_requested"
        private const val CLEANUP_TIMEOUT_MS = 60_000L

        fun enqueueUserRequested(context: Context, couponId: Long) {
            val request = OneTimeWorkRequestBuilder<CouponCleanupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_COUPON_ID to couponId,
                        KEY_USER_REQUESTED to true
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "coupon_cleanup_$couponId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

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
import com.example.coupontracker.extraction.model.GemmaVisionCouponModel
import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.model.ModelPaths
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.extraction.rules.CouponInfo
import com.example.coupontracker.util.DateParser
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.PostOcrCouponNormalizer
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.extraction.rules.TextExtractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Date

@HiltWorker
class VerifyCouponWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val couponRepository: CouponRepository,
    private val ocrEngine: OcrEngine,
    private val gemmaVisionCouponModel: GemmaVisionCouponModel,
    private val securePreferencesManager: SecurePreferencesManager
) : CoroutineWorker(appContext, workerParams) {
    private val textExtractor = TextExtractor()

    override suspend fun doWork(): Result {
        val couponId = inputData.getLong(KEY_COUPON_ID, 0L)
        if (couponId <= 0L) return Result.failure()
        val userRequested = inputData.getBoolean(KEY_USER_REQUESTED, false)
        val automaticVerification = inputData.getBoolean(KEY_AUTOMATIC_VERIFICATION, false)
        if (!userRequested && !automaticVerification) {
            Log.w(TAG, "Ignoring verification work without explicit mode for couponId=$couponId")
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

            if (!securePreferencesManager.isGemmaVisionVerifierEnabled()) {
                markFailed(
                    couponId,
                    "Gemma Vision verifier is turned off in Settings."
                )
                return Result.success()
            }

            val gemmaStatus = ModelPaths.getGemmaVisionInstallStatus(applicationContext)
            if (!gemmaStatus.installed) {
                markFailed(
                    couponId,
                    gemmaStatus.message
                )
                return Result.success()
            }

            val bitmap = decodeBitmap(coupon.imageUri)
            if (bitmap == null) {
                markFailed(couponId, "Saved image is unavailable for vision verification.")
                return Result.success()
            }
            try {
                val visionResult = withTimeout(CLEANUP_TIMEOUT_MS) {
                    gemmaVisionCouponModel.extractFromImage(
                        image = bitmap,
                        ocrText = ocrText,
                        prompt = buildVisionVerificationPrompt()
                    )
                }
                val current = couponRepository.getCouponById(couponId) ?: return Result.success()
                val visionInfo = parseCanonicalJsonToCouponInfo(visionResult.canonicalJson, current.createdAt)
                val verified = mergeVisionVerifiedCoupon(current, visionInfo, ocrText)
                couponRepository.updateCoupon(verified)
                Result.success()
            } finally {
                bitmap.recycle()
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
                message.contains("not found", ignoreCase = true) ||
                message.contains("missing", ignoreCase = true) ||
                message.contains("incomplete", ignoreCase = true) -> {
                "Set up the offline vision verifier in Settings, then try Verify again."
            }
            message.contains("loadModel", ignoreCase = true) ||
                message.contains("initialize", ignoreCase = true) ||
                message.contains("native", ignoreCase = true) ||
                message.contains("MediaPipe image bridge", ignoreCase = true) ||
                message.contains("runtime could not initialize", ignoreCase = true) -> {
                "The offline verifier could not start. Remove and set up the model again in Settings."
            }
            message.contains("timeout", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) -> {
                "Verification took too long. The OCR result is still saved; try Verify again later."
            }
            message.isBlank() -> "Reader failed. The OCR result is still saved."
            else -> message
        }
    }

    private fun buildDeterministicCleanedCoupon(current: Coupon, rawOcr: String): Coupon? {
        val allowUserEditedFallback = current.extractionSource == Coupon.ExtractionSource.USER_EDITED
        val info = textExtractor.extractCouponInfoSync(rawOcr, current.createdAt)
        val extractedCode = info.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedCode = extractedCode ?: current.redeemCode
            ?.takeIf { allowUserEditedFallback && it.isNotBlank() }
        val codeSource = when {
            extractedCode != null -> FIELD_SOURCE_OCR_RULE
            selectedCode != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val extractedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedStore = extractedStore ?: current.storeName
            .takeIf { allowUserEditedFallback && it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?: return null
        val storeSource = when {
            extractedStore != null -> FIELD_SOURCE_OCR_RULE
            allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val scopedOcr = textExtractor.extractCouponBlockForStore(rawOcr, selectedStore) ?: rawOcr
        val normalized = PostOcrCouponNormalizer.normalize(
            currentDescription = "",
            ocrText = scopedOcr,
            storeName = selectedStore,
            redeemCode = selectedCode
        )
        val extractedDescription = info.description.takeIf(GenericFieldHeuristics::isMeaningfulDescription)
        val selectedDescription = normalized.description
            ?: extractedDescription
            ?: current.description.takeIf { allowUserEditedFallback && GenericFieldHeuristics.isMeaningfulDescription(it) }
            ?: return null
        val descriptionSource = when {
            normalized.description != null || extractedDescription != null -> FIELD_SOURCE_OCR_RULE
            allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val selectedExpiry = info.expiryDate ?: current.expiryDate.takeIf { allowUserEditedFallback }
        val expirySource = when {
            info.expiryDate != null -> FIELD_SOURCE_OCR_RULE
            selectedExpiry != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }
        if (selectedCode.isNullOrBlank() && selectedExpiry == null) {
            return null
        }

        val verified = current.copy(
            storeName = selectedStore,
            description = selectedDescription,
            expiryDate = selectedExpiry,
            redeemCode = selectedCode,
            category = info.category,
            status = info.status ?: Coupon.Status.ACTIVE,
            minimumPurchase = info.minimumPurchase,
            maximumDiscount = info.maximumDiscount,
            paymentMethod = info.paymentMethod,
            platformType = info.platformType,
            usageLimit = info.usageLimit,
            cleanupStatus = Coupon.CleanupStatus.CLEANED,
            cleanupFinishedAt = Date(),
            cleanupError = null,
            lastCleanedBy = "OCR rules",
            extractionSource = if (allowUserEditedFallback && listOf(storeSource, descriptionSource, codeSource, expirySource).contains(FIELD_SOURCE_USER_EDITED)) {
                Coupon.ExtractionSource.USER_EDITED
            } else {
                Coupon.ExtractionSource.OCR_VERIFIED
            },
            extractionRunPath = buildFieldSourceRunPath(
                stage = "deterministic_cleanup",
                storeSource = storeSource,
                descriptionSource = descriptionSource,
                codeSource = codeSource,
                expirySource = expirySource
            ),
            normalizedDescription = CouponDedupUtils.normalizeDescription(selectedDescription),
            needsAttention = normalized.needsAttention || listOf(storeSource, descriptionSource, codeSource, expirySource).contains(FIELD_SOURCE_USER_EDITED),
            updatedAt = Date()
        )
        return withConfidenceAssessment(verified, rawOcr)
    }

    private fun buildFieldSourceRunPath(
        stage: String,
        storeSource: String,
        descriptionSource: String,
        codeSource: String,
        expirySource: String
    ): String {
        return JSONObject()
            .put("stage", stage)
            .put("storeName", storeSource)
            .put("description", descriptionSource)
            .put("redeemCode", codeSource)
            .put("expiryDate", expirySource)
            .toString()
    }

    private fun mergeVisionVerifiedCoupon(current: Coupon, info: CouponInfo, rawOcr: String?): Coupon {
        val allowUserEditedFallback = current.extractionSource == Coupon.ExtractionSource.USER_EDITED
        val selectedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?: current.storeName.takeIf { allowUserEditedFallback && it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
        val selectedDescription = info.description
            .takeIf(GenericFieldHeuristics::isMeaningfulDescription)
            ?: current.description.takeIf { allowUserEditedFallback && GenericFieldHeuristics.isMeaningfulDescription(it) }
        val selectedCode = info.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?: current.redeemCode?.takeIf { allowUserEditedFallback && it.isNotBlank() }
        val selectedExpiry = info.expiryDate ?: current.expiryDate.takeIf { allowUserEditedFallback }
        val missingCriticalFields = selectedStore == null ||
            selectedStore == Coupon.Defaults.UNKNOWN_STORE ||
            selectedDescription == null ||
            (selectedCode.isNullOrBlank() && selectedExpiry == null)

        if (missingCriticalFields) {
            Log.w(
                TAG,
                "Vision verifier returned insufficient fields: " +
                    "store=${selectedStore != null}, description=${selectedDescription != null}, " +
                    "code=${!selectedCode.isNullOrBlank()}, expiry=${selectedExpiry != null}"
            )
            return current.copy(
                cleanupStatus = Coupon.CleanupStatus.NONE,
                cleanupFinishedAt = Date(),
                cleanupError = null,
                lastCleanedBy = null,
                extractionRunPath = buildFieldSourceRunPath(
                    stage = "vision_cleanup_failed",
                    storeSource = if (selectedStore != null) FIELD_SOURCE_VISION else FIELD_SOURCE_MISSING,
                    descriptionSource = if (selectedDescription != null) FIELD_SOURCE_VISION else FIELD_SOURCE_MISSING,
                    codeSource = if (selectedCode != null) FIELD_SOURCE_VISION else FIELD_SOURCE_MISSING,
                    expirySource = if (selectedExpiry != null) FIELD_SOURCE_VISION else FIELD_SOURCE_MISSING
                ),
                needsAttention = true,
                updatedAt = Date()
            )
        }
        val verifiedStore = selectedStore ?: return current
        val verifiedDescription = selectedDescription ?: return current

        val verified = current.copy(
            storeName = verifiedStore,
            description = verifiedDescription,
            expiryDate = selectedExpiry,
            redeemCode = selectedCode,
            category = info.category,
            status = info.status ?: Coupon.Status.ACTIVE,
            minimumPurchase = info.minimumPurchase,
            maximumDiscount = info.maximumDiscount,
            paymentMethod = info.paymentMethod,
            platformType = info.platformType,
            usageLimit = info.usageLimit,
            cleanupStatus = Coupon.CleanupStatus.CLEANED,
            cleanupFinishedAt = Date(),
            cleanupError = null,
            lastCleanedBy = ModelCatalog.GEMMA_VISION_READER_NAME,
            extractionSource = if (allowUserEditedFallback) Coupon.ExtractionSource.USER_EDITED else Coupon.ExtractionSource.VISION_VERIFIED,
            extractionRunPath = buildFieldSourceRunPath(
                stage = "vision_cleanup",
                storeSource = if (info.storeName.isNotBlank()) FIELD_SOURCE_VISION else FIELD_SOURCE_USER_EDITED,
                descriptionSource = if (GenericFieldHeuristics.isMeaningfulDescription(info.description)) FIELD_SOURCE_VISION else FIELD_SOURCE_USER_EDITED,
                codeSource = when {
                    info.redeemCode?.trim()?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) } != null -> FIELD_SOURCE_VISION
                    selectedCode != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
                    else -> FIELD_SOURCE_MISSING
                },
                expirySource = when {
                    info.expiryDate != null -> FIELD_SOURCE_VISION
                    selectedExpiry != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
                    else -> FIELD_SOURCE_MISSING
                }
            ),
            normalizedDescription = CouponDedupUtils.normalizeDescription(verifiedDescription),
            needsAttention = info.needsAttention || allowUserEditedFallback,
            updatedAt = Date()
        )
        return withConfidenceAssessment(verified, rawOcr)
    }

    private fun parseCanonicalJsonToCouponInfo(json: String, baseDate: Date?): CouponInfo {
        val obj = JSONObject(json)
        val expiryText = obj.optNullableString(CouponSchemaKeys.EXPIRY_DATE)
        return CouponInfo(
            storeName = obj.optNullableString(CouponSchemaKeys.STORE_NAME).orEmpty(),
            description = obj.optNullableString(CouponSchemaKeys.DESCRIPTION).orEmpty(),
            expiryDate = DateParser.parseDate(expiryText, baseDate),
            redeemCode = obj.optNullableString(CouponSchemaKeys.REDEEM_CODE),
            needsAttention = obj.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false),
            storeNameSource = obj.optNullableString(CouponSchemaKeys.STORE_NAME_SOURCE)
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun buildVisionVerificationPrompt(): String {
        return "Read this coupon image. Return JSON only: " +
            "{\"storeName\":\"\",\"description\":\"\",\"redeemCode\":null,\"expiryDate\":null," +
            "\"storeNameSource\":\"vision\",\"storeNameEvidence\":[],\"needsAttention\":false}. " +
            "Use visible text only. Code must be exact. Missing fields are null."
    }

    private fun withConfidenceAssessment(coupon: Coupon, rawOcr: String?): Coupon {
        val assessment = CouponExtractionConfidenceScorer.score(coupon, rawOcr)
        return coupon.copy(
            extractionQualityScore = assessment.score,
            extractionConfidenceBreakdown = coupon.extractionConfidenceBreakdown.ifEmpty {
                assessment.fieldConfidences
            },
            needsAttention = coupon.needsAttention ||
                assessment.recommendation != ExtractionRecommendation.SAVE_DIRECTLY
        )
    }

    companion object {
        private const val TAG = "VerifyCouponWorker"
        private const val KEY_COUPON_ID = "coupon_id"
        private const val KEY_USER_REQUESTED = "user_requested"
        private const val KEY_AUTOMATIC_VERIFICATION = "automatic_verification"
        private const val CLEANUP_TIMEOUT_MS = 90_000L
        private const val FIELD_SOURCE_OCR_RULE = "OCR_RULE"
        private const val FIELD_SOURCE_VISION = "VISION"
        private const val FIELD_SOURCE_MISSING = "MISSING"
        private const val FIELD_SOURCE_USER_EDITED = "USER_EDITED"

        fun enqueueUserRequested(context: Context, couponId: Long) {
            val request = OneTimeWorkRequestBuilder<VerifyCouponWorker>()
                .setInputData(
                    workDataOf(
                        KEY_COUPON_ID to couponId,
                        KEY_USER_REQUESTED to true
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "coupon_verify_$couponId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueueAutomaticVerification(context: Context, couponId: Long) {
            val request = OneTimeWorkRequestBuilder<VerifyCouponWorker>()
                .setInputData(
                    workDataOf(
                        KEY_COUPON_ID to couponId,
                        KEY_AUTOMATIC_VERIFICATION to true
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "coupon_verify_$couponId",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

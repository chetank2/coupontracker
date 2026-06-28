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
import com.example.coupontracker.domain.usecase.VerifyCouponUseCase
import com.example.coupontracker.extraction.model.GemmaVisionCouponModel
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.extraction.vision.VisionFieldExtraction
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionEvidenceMergePolicy
import com.example.coupontracker.extraction.vision.VisionVerificationConfig
import com.example.coupontracker.model.ModelPaths
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.OcrEvidenceValidator
import com.example.coupontracker.util.PostOcrCouponNormalizer
import com.example.coupontracker.util.SecurePreferencesManager
import com.example.coupontracker.util.StoreCandidateValidator
import com.example.coupontracker.extraction.rules.TextExtractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Date
import java.util.Locale

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
    private val visionFieldJsonParser = VisionFieldJsonParser()
    private val visionEvidenceMergePolicy = VisionEvidenceMergePolicy()
    private val cleanupStatusWriter = CouponCleanupStatusWriter(couponRepository)
    private val verifyCouponUseCase = VerifyCouponUseCase()
    private val visionCropPreparer = VisionCropPreparer(
        ocrEngine = ocrEngine,
        gemmaVisionCouponModel = gemmaVisionCouponModel,
        visionFieldJsonParser = visionFieldJsonParser,
        visionEvidenceMergePolicy = visionEvidenceMergePolicy,
        textExtractor = textExtractor
    )

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
        couponRepository.updateCoupon(verifyCouponUseCase.markRunning(coupon))

        return try {
            val ocrText = coupon.rawOcrText?.takeIf { it.isNotBlank() }
                ?: extractOcrText(coupon.imageUri)
            if (ocrText.isNullOrBlank()) {
                markFailed(couponId, "Saved OCR text is unavailable")
                return Result.success()
            }

            val gemmaEnabled = securePreferencesManager.isGemmaVisionVerifierEnabled()
            val gemmaStatus = ModelPaths.getGemmaVisionInstallStatus(applicationContext)

            val deterministicCleaned = buildDeterministicCleanedCoupon(coupon, ocrText)
            val shouldRunVision = verifyCouponUseCase.shouldRunVisionVerification(
                userRequested = userRequested,
                automaticVerification = automaticVerification,
                deterministicCleaned = deterministicCleaned,
                rawOcr = ocrText,
                gemmaEnabled = gemmaEnabled,
                gemmaInstalled = gemmaStatus.installed
            )
            val visionBaseCoupon = deterministicCleaned ?: coupon
            if (deterministicCleaned != null) {
                val deterministicState = if (shouldRunVision) {
                    verifyCouponUseCase.markDeterministicBaselineRunning(deterministicCleaned)
                } else {
                    deterministicCleaned
                }
                couponRepository.updateCoupon(deterministicState)
            }
            if (deterministicCleaned != null && !shouldRunVision) {
                return Result.success()
            }

            if (!gemmaEnabled) {
                markFailed(
                    couponId,
                    "Gemma Vision verifier is turned off in Settings."
                )
                return Result.success()
            }

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
            val visionInput = visionCropPreparer.prepareTwoPassVisionInput(bitmap, visionBaseCoupon, ocrText)
            if (visionInput == null) {
                val current = couponRepository.getCouponById(couponId) ?: visionBaseCoupon
                markVisionFailed(
                    current = mergeLatestCouponState(visionBaseCoupon, current),
                    visionInput = null,
                    error = IllegalStateException("Could not isolate a coupon crop for Gemma Vision."),
                    stage = "vision_crop_unavailable",
                    rawVisionJson = null,
                    rawOcr = null
                )
                if (!bitmap.isRecycled) bitmap.recycle()
                return Result.success()
            }
            try {
                val cropOcrText = withContext(Dispatchers.IO) {
                    ocrEngine.recognize(visionInput.bitmap)
                }
                Log.i(
                    TAG,
                    "CROP_OCR_DONE couponId=$couponId source=${visionInput.source} " +
                        "textLength=${cropOcrText.length} pixelCrop=${visionInput.pixelCrop?.flattenToString()}"
                )
                // Do not fall back to full-screen OCR here: it can prove fields
                // from background cards for a foreground crop.
                val cropEvidenceText = cropOcrText.takeIf { it.isNotBlank() }
                val visionResult = withTimeout(VisionVerificationConfig.FIELD_LABEL_TIMEOUT_MS) {
                    gemmaVisionCouponModel.extractRawFromImage(
                        image = visionInput.bitmap,
                        ocrText = cropEvidenceText,
                        prompt = VisionVerificationPrompts.fieldLabels()
                    )
                }
                val current = couponRepository.getCouponById(couponId) ?: return Result.success()
                val fieldLabels = runCatching {
                    visionFieldJsonParser.parse(visionResult.canonicalJson)
                }.getOrElse { error ->
                    markVisionFailed(
                        current = mergeLatestCouponState(visionBaseCoupon, current),
                        visionInput = visionInput,
                        error = error,
                        stage = "field_label_parse_failed",
                        rawVisionJson = visionResult.canonicalJson,
                        rawOcr = cropEvidenceText
                    )
                    return Result.success()
                }
                Log.i(
                    TAG,
                    "GEMMA_FIELD_LABEL_PARSED couponId=$couponId source=${visionInput.source} cards=${fieldLabels.cards.size} " +
                        "confidence=${"%.2f".format(fieldLabels.confidence)} " +
                        "activeCode=${fieldLabels.activeCard?.codeState} activeExpiry=${fieldLabels.activeCard?.expiryState}"
                )
                val verified = mergeVisionFieldLabels(
                    current = mergeLatestCouponState(visionBaseCoupon, current),
                    vision = fieldLabels,
                    rawOcr = cropEvidenceText,
                    visionInput = visionInput
                )
                couponRepository.updateCoupon(verified)
                Result.success()
            } finally {
                if (visionInput.bitmap !== bitmap && !visionInput.bitmap.isRecycled) {
                    visionInput.bitmap.recycle()
                }
                bitmap.recycle()
            }
        } catch (t: CancellationException) {
            Log.i(TAG, "Coupon cleanup cancelled for couponId=$couponId")
            withContext(NonCancellable) {
                markFailed(couponId, "Vision verification was interrupted. The OCR result is still saved for review.")
            }
            throw t
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
        cleanupStatusWriter.markGenericFailure(couponId, message)
    }

    private suspend fun markVisionFailed(
        current: Coupon,
        visionInput: VisionInput?,
        error: Throwable,
        stage: String,
        rawVisionJson: String?,
        rawOcr: String?
    ) {
        couponRepository.updateCoupon(
            withConfidenceAssessment(
                current.copy(
                    cleanupStatus = Coupon.CleanupStatus.FAILED,
                    cleanupFinishedAt = Date(),
                    cleanupError = userFacingFailure(error.message),
                    debugVisionEvidence = visionEvidenceMergePolicy.buildFailureEvidence(
                        stage = stage,
                        visionInput = visionInput?.toMergeInput(),
                        error = error,
                        rawVisionJson = rawVisionJson
                    ),
                    lastCleanedBy = null,
                    extractionSource = current.extractionSource.withoutTrustedModelSource(),
                    needsAttention = true,
                    updatedAt = Date()
                ),
                rawOcr
            )
        )
    }

    internal fun userFacingFailure(rawMessage: String?): String {
        return cleanupStatusWriter.userFacingFailure(rawMessage)
    }

    private fun buildDeterministicCleanedCoupon(current: Coupon, rawOcr: String): Coupon? {
        val allowUserEditedFallback = current.extractionSource == Coupon.ExtractionSource.USER_EDITED
        val captureTimestamp = extractCaptureTimestamp(current) ?: current.createdAt
        val info = textExtractor.extractCouponInfoSync(rawOcr, captureTimestamp)
        val extractedCode = info.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val currentStrongCode = current.redeemCode
            ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val selectedCode = extractedCode ?: currentStrongCode ?: current.redeemCode
            ?.takeIf { allowUserEditedFallback && it.isNotBlank() }
        val noCodeRequired = hasNoCodeEvidence(rawOcr)
        val codeSource = when {
            extractedCode != null -> FIELD_SOURCE_OCR_RULE
            currentStrongCode != null -> FIELD_SOURCE_PRESERVED
            noCodeRequired -> FIELD_SOURCE_OCR_RULE
            selectedCode != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val extractedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val currentStrongStore = current.storeName
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            .takeIf { StoreCandidateValidator.isAcceptable(it, rawOcr) }
            .takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val preserveCurrentStore = current.extractionSource in setOf(
            Coupon.ExtractionSource.USER_EDITED,
            Coupon.ExtractionSource.VISION_VERIFIED
        )
        val selectedStore = if (preserveCurrentStore) {
            currentStrongStore ?: extractedStore
        } else {
            extractedStore ?: currentStrongStore
        } ?: current.storeName
            .takeIf { allowUserEditedFallback && it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?: return null
        val storeSource = when {
            preserveCurrentStore && selectedStore == currentStrongStore -> FIELD_SOURCE_PRESERVED
            selectedStore == extractedStore -> FIELD_SOURCE_OCR_RULE
            selectedStore == currentStrongStore -> FIELD_SOURCE_PRESERVED
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
        val normalizedDescription = normalized.description
            ?.takeIf(::isSupportedCleanupDescription)
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) || hasSupportedDescriptionTokens(it, rawOcr) }
        val extractedDescription = info.description.takeIf(::isSupportedCleanupDescription)
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) || hasSupportedDescriptionTokens(it, rawOcr) }
        val currentStrongDescription = current.description
            .takeIf(::isSupportedCleanupDescription)
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) || hasSupportedDescriptionTokens(it, rawOcr) }
        val selectedDescription = selectBestCleanupDescription(
            normalizedDescription,
            extractedDescription,
            currentStrongDescription
        )
            ?: current.description.takeIf { allowUserEditedFallback && GenericFieldHeuristics.isMeaningfulDescription(it) }
            ?: return null
        val descriptionSource = when {
            selectedDescription == normalizedDescription || selectedDescription == extractedDescription -> FIELD_SOURCE_OCR_RULE
            selectedDescription == currentStrongDescription -> FIELD_SOURCE_PRESERVED
            allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val currentStrongExpiry = current.expiryDate
        val selectedExpiry = info.expiryDate ?: currentStrongExpiry ?: current.expiryDate.takeIf { allowUserEditedFallback }
        val expirySource = when {
            info.expiryDate != null -> FIELD_SOURCE_OCR_RULE
            currentStrongExpiry != null -> FIELD_SOURCE_PRESERVED
            selectedExpiry != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }
        if (selectedCode.isNullOrBlank() && selectedExpiry == null && !noCodeRequired) {
            return null
        }

        val verified = current.copy(
            storeName = selectedStore,
            description = selectedDescription,
            expiryDate = selectedExpiry,
            redeemCode = selectedCode,
            codeState = when {
                !selectedCode.isNullOrBlank() -> Coupon.CodeState.PRESENT
                noCodeRequired -> Coupon.CodeState.NO_CODE_NEEDED
                else -> current.codeState
            },
            expiryState = when {
                selectedExpiry != null -> Coupon.ExpiryState.PRESENT
                else -> current.expiryState
            },
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

    private fun isSupportedCleanupDescription(value: String?): Boolean {
        return GenericFieldHeuristics.isMeaningfulDescription(value) &&
            OfferTextQuality.isLikelyOfferText(value) &&
            !OfferTextQuality.isLikelyDateOrContextNoise(value)
    }

    private fun selectBestCleanupDescription(vararg candidates: String?): String? {
        return candidates
            .filterNotNull()
            .distinctBy { it.trim().lowercase(Locale.ROOT) }
            .maxWithOrNull(
                compareBy<String> { OfferTextQuality.score(it) }
                    .thenBy { it.length }
            )
    }

    private fun mergeLatestCouponState(baseline: Coupon, latest: Coupon): Coupon {
        return verifyCouponUseCase.mergeLatestCouponState(baseline, latest)
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

    internal fun mergeVisionFieldLabels(
        current: Coupon,
        vision: VisionFieldExtraction,
        rawOcr: String?,
        visionInput: VisionInput
    ): Coupon {
        val merged = visionEvidenceMergePolicy.mergeFieldLabels(
            current = current,
            vision = vision,
            rawOcr = rawOcr,
            visionInput = visionInput.toMergeInput(),
            captureTimestamp = extractCaptureTimestamp(current) ?: current.createdAt
        )
        return withConfidenceAssessment(merged, rawOcr)
    }

    private fun extractCaptureTimestamp(coupon: Coupon): Date? {
        val uri = coupon.imageUri?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            ImageMetadataExtractor.extractCaptureTimestamp(applicationContext, Uri.parse(uri))
        }.getOrNull()
    }

    private fun hasSupportedDescriptionTokens(description: String, rawOcr: String?): Boolean {
        if (rawOcr.isNullOrBlank()) return true
        val ocrTokens = rawOcr.lowercase(Locale.ROOT)
        val candidateTokens = description
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim(',', '.', '*', ':', ';', '(', ')') }
            .filter { it.length >= 3 }
        val supportedCount = candidateTokens.count { token -> ocrTokens.contains(token) }
        return if (candidateTokens.size <= 2) {
            candidateTokens.isNotEmpty() && supportedCount == candidateTokens.size
        } else {
            supportedCount >= 3
        }
    }

    private fun hasStoreTokenEvidence(storeName: String, rawOcr: String?): Boolean {
        if (rawOcr.isNullOrBlank()) return false
        val ocrTokens = rawOcr.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()
        val storeTokens = storeName.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
        if (storeTokens.isEmpty()) return false
        val supported = storeTokens.count { it in ocrTokens }
        val hasDistinctiveAcronym = storeTokens.any { token ->
            token.length >= 4 && token.all(Char::isLetter) && token in ocrTokens
        }
        return supported >= 2 || (storeTokens.size <= 2 && supported == storeTokens.size) || hasDistinctiveAcronym
    }

    private fun hasNoCodeEvidence(rawOcr: String?): Boolean {
        val normalized = rawOcr.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        return Regex("\\bno\\s+code(?:\\s+needed|required)?\\b").containsMatchIn(normalized) ||
            normalized.contains("nocodeneeded") ||
            normalized.contains("no code needed")
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
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
        private const val FIELD_SOURCE_OCR_RULE = "OCR_RULE"
        private const val FIELD_SOURCE_MISSING = "MISSING"
        private const val FIELD_SOURCE_USER_EDITED = "USER_EDITED"
        private const val FIELD_SOURCE_PRESERVED = "PRESERVED"

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

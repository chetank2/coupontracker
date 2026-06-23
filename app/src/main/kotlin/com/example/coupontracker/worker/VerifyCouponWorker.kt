package com.example.coupontracker.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
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
import com.example.coupontracker.extraction.quality.OfferTextQuality
import com.example.coupontracker.llm.CouponSchemaKeys
import com.example.coupontracker.model.ModelCatalog
import com.example.coupontracker.model.ModelPaths
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.OcrTextSpan
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.extraction.rules.CouponInfo
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.GenericFieldHeuristics
import com.example.coupontracker.util.ImageMetadataExtractor
import com.example.coupontracker.util.ModelExpiryNormalizer
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

            val gemmaEnabled = securePreferencesManager.isGemmaVisionVerifierEnabled()
            val gemmaStatus = ModelPaths.getGemmaVisionInstallStatus(applicationContext)
            val shouldRunVision = userRequested && gemmaEnabled && gemmaStatus.installed

            buildDeterministicCleanedCoupon(coupon, ocrText)?.let { cleaned ->
                couponRepository.updateCoupon(cleaned)
                if (!shouldRunVision) {
                    return Result.success()
                }
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
            val visionInput = prepareVisionBitmap(bitmap, coupon, ocrText)
            try {
                val visionResult = withTimeout(CLEANUP_TIMEOUT_MS) {
                    gemmaVisionCouponModel.extractFromImage(
                        image = visionInput.bitmap,
                        ocrText = ocrText,
                        prompt = buildVisionVerificationPrompt()
                    )
                }
                val current = couponRepository.getCouponById(couponId) ?: return Result.success()
                val captureTimestamp = extractCaptureTimestamp(current)
                val visionInfo = parseCanonicalJsonToCouponInfo(
                    json = visionResult.canonicalJson,
                    captureTimestamp = captureTimestamp
                )
                val verified = mergeVisionVerifiedCoupon(current, visionInfo, ocrText, visionInput.usedTargetedCrop)
                couponRepository.updateCoupon(verified)
                Result.success()
            } finally {
                if (visionInput.bitmap !== bitmap && !visionInput.bitmap.isRecycled) {
                    visionInput.bitmap.recycle()
                }
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Coupon cleanup failed", t)
            markFailed(couponId, userFacingFailure(t.message))
            Result.success()
        }
    }

    private data class VisionInput(
        val bitmap: Bitmap,
        val usedTargetedCrop: Boolean
    )

    private suspend fun prepareVisionBitmap(source: Bitmap, coupon: Coupon, rawOcr: String): VisionInput {
        val crop = runCatching { cropBitmapToCouponEvidence(source, coupon, rawOcr) }
            .onFailure { Log.w(TAG, "Could not create target crop for Gemma Vision: ${it.message}") }
            .getOrNull()
        if (crop == null) {
            Log.d(TAG, "Gemma Vision using original bitmap ${source.width}x${source.height}")
            return VisionInput(source, usedTargetedCrop = false)
        }
        Log.d(
            TAG,
            "Gemma Vision using OCR-targeted crop ${source.width}x${source.height} -> ${crop.width}x${crop.height}"
        )
        return VisionInput(crop, usedTargetedCrop = true)
    }

    private suspend fun cropBitmapToCouponEvidence(source: Bitmap, coupon: Coupon, rawOcr: String): Bitmap? {
        val spans = withContext(Dispatchers.IO) { ocrEngine.recognizeWithBoxes(source) }
            .filter { it.text.isNotBlank() && !it.boundingBox.isEmpty }
        if (spans.isEmpty()) return null

        val anchorSpans = spans.filter { span -> isCouponAnchorSpan(span, coupon, rawOcr) }
        val exactCodeAnchors = coupon.redeemCode
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.let { code -> anchorSpans.count { normalizeEvidence(it.text).contains(normalizeEvidence(code)) } }
            ?: 0
        if (anchorSpans.size < MIN_VISION_CROP_ANCHORS && exactCodeAnchors == 0) return null

        val minAnchorY = anchorSpans.minOf { it.boundingBox.top }
        val maxAnchorY = anchorSpans.maxOf { it.boundingBox.bottom }
        val verticalPadding = (source.height * VISION_CROP_VERTICAL_PADDING_RATIO).toInt()
            .coerceAtLeast(MIN_VISION_CROP_PADDING_PX)
        val cropTop = (minAnchorY - verticalPadding).coerceAtLeast(0)
        val cropBottom = (maxAnchorY + verticalPadding).coerceAtMost(source.height)
        val cropHeight = cropBottom - cropTop
        if (cropHeight <= 0 || cropHeight >= source.height * MAX_VISION_CROP_HEIGHT_RATIO) return null

        val horizontalBounds = spans
            .filter { centerY(it.boundingBox) in cropTop..cropBottom }
            .map { it.boundingBox }
        val cropLeft = (horizontalBounds.minOfOrNull { it.left } ?: 0)
            .minus((source.width * VISION_CROP_HORIZONTAL_PADDING_RATIO).toInt())
            .coerceAtLeast(0)
        val cropRight = (horizontalBounds.maxOfOrNull { it.right } ?: source.width)
            .plus((source.width * VISION_CROP_HORIZONTAL_PADDING_RATIO).toInt())
            .coerceAtMost(source.width)
        val cropWidth = cropRight - cropLeft
        if (cropWidth <= 0) return null

        return Bitmap.createBitmap(source, cropLeft, cropTop, cropWidth, cropHeight)
    }

    private fun isCouponAnchorSpan(span: OcrTextSpan, coupon: Coupon, rawOcr: String): Boolean {
        val text = normalizeEvidence(span.text)
        if (text.isBlank()) return false

        coupon.redeemCode
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.let { code ->
                if (text.contains(normalizeEvidence(code))) return true
            }

        val storeTokens = evidenceTokens(coupon.storeName)
        if (storeTokens.any { token -> text.contains(token) }) return true

        val descriptionTokens = evidenceTokens(coupon.description)
            .filterNot { it in GENERIC_DESCRIPTION_ANCHORS }
        if (descriptionTokens.any { token -> text.contains(token) }) return true

        val scopedOcr = textExtractor.extractCouponBlockForStore(rawOcr, coupon.storeName).orEmpty()
        val scopedTokens = evidenceTokens(scopedOcr)
        return scopedTokens.take(MAX_SCOPED_ANCHOR_TOKENS).any { token -> text.contains(token) }
    }

    private fun evidenceTokens(value: String?): List<String> {
        return normalizeEvidence(value.orEmpty())
            .split(" ")
            .map { it.trim() }
            .filter { it.length >= MIN_EVIDENCE_TOKEN_LENGTH }
            .filterNot { it in GENERIC_DESCRIPTION_ANCHORS }
            .distinct()
    }

    private fun normalizeEvidence(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun centerY(rect: Rect): Int = (rect.top + rect.bottom) / 2

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
                lastCleanedBy = null,
                extractionSource = current.extractionSource.withoutTrustedModelSource(),
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
        val codeSource = when {
            extractedCode != null -> FIELD_SOURCE_OCR_RULE
            currentStrongCode != null -> FIELD_SOURCE_PRESERVED
            selectedCode != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            else -> FIELD_SOURCE_MISSING
        }

        val extractedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
        val currentStrongStore = current.storeName
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
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

    private fun mergeVisionVerifiedCoupon(
        current: Coupon,
        info: CouponInfo,
        rawOcr: String?,
        usedTargetedCrop: Boolean
    ): Coupon {
        val allowUserEditedFallback = current.extractionSource == Coupon.ExtractionSource.USER_EDITED
        val selectedStore = info.storeName.trim()
            .takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
            ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
            ?: current.storeName.takeIf {
                it.isNotBlank() &&
                    !GenericFieldHeuristics.isGenericOrMissing(it) &&
                    (rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr))
            }
            ?: current.storeName.takeIf { allowUserEditedFallback && it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissing(it) }
        val selectedDescription = info.description
            .takeIf(GenericFieldHeuristics::isMeaningfulDescription)
            ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) || hasSupportedDescriptionTokens(it, rawOcr) }
            ?: current.description
                .takeIf(GenericFieldHeuristics::isMeaningfulDescription)
                ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) || hasSupportedDescriptionTokens(it, rawOcr) }
            ?: current.description.takeIf { allowUserEditedFallback && GenericFieldHeuristics.isMeaningfulDescription(it) }
        val selectedCode = info.redeemCode?.trim()
            ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
            ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
            ?: current.redeemCode
                ?.takeIf { it.isNotBlank() && !GenericFieldHeuristics.isGenericOrMissingCode(it) }
                ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) }
            ?: current.redeemCode?.takeIf { allowUserEditedFallback && it.isNotBlank() }
        val selectedExpiry = info.expiryDate ?: current.expiryDate ?: current.expiryDate.takeIf { allowUserEditedFallback }
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
                extractionSource = current.extractionSource.withoutTrustedModelSource(),
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
        val storeSource = if (
            info.storeName.isNotBlank() &&
            !GenericFieldHeuristics.isGenericOrMissing(info.storeName) &&
            (rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(info.storeName, rawOcr))
        ) {
            FIELD_SOURCE_VISION
        } else {
            FIELD_SOURCE_PRESERVED
        }
        val descriptionSource = if (
            GenericFieldHeuristics.isMeaningfulDescription(info.description) &&
            OfferTextQuality.isLikelyOfferText(info.description) &&
            (rawOcr.isNullOrBlank() ||
                OcrEvidenceValidator.isPhraseSupported(info.description, rawOcr) ||
                hasSupportedDescriptionTokens(info.description, rawOcr))
        ) {
            FIELD_SOURCE_VISION
        } else {
            FIELD_SOURCE_PRESERVED
        }
        val codeSource = when {
            info.redeemCode?.trim()
                ?.takeIf { !GenericFieldHeuristics.isGenericOrMissingCode(it) }
                ?.takeIf { rawOcr.isNullOrBlank() || OcrEvidenceValidator.isPhraseSupported(it, rawOcr) } != null -> FIELD_SOURCE_VISION
            selectedCode != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            selectedCode != null -> FIELD_SOURCE_PRESERVED
            else -> FIELD_SOURCE_MISSING
        }
        val expirySource = when {
            info.expiryDate != null -> FIELD_SOURCE_VISION
            selectedExpiry != null && allowUserEditedFallback -> FIELD_SOURCE_USER_EDITED
            selectedExpiry != null -> FIELD_SOURCE_PRESERVED
            else -> FIELD_SOURCE_MISSING
        }
        val actionFieldVisionSupported = codeSource == FIELD_SOURCE_VISION || expirySource == FIELD_SOURCE_VISION
        val strictVisionVerified = usedTargetedCrop &&
            storeSource == FIELD_SOURCE_VISION &&
            descriptionSource == FIELD_SOURCE_VISION &&
            actionFieldVisionSupported
        val extractionSource = when {
            allowUserEditedFallback -> Coupon.ExtractionSource.USER_EDITED
            !strictVisionVerified -> current.extractionSource.withoutTrustedModelSource() ?: Coupon.ExtractionSource.OCR_VERIFIED
            else -> Coupon.ExtractionSource.VISION_VERIFIED
        }
        val cleanupAccepted = strictVisionVerified && !info.needsAttention && !allowUserEditedFallback
        val runPath = buildFieldSourceRunPath(
            stage = "vision_cleanup",
            storeSource = storeSource,
            descriptionSource = descriptionSource,
            codeSource = codeSource,
            expirySource = expirySource
        )

        if (!cleanupAccepted) {
            val preserved = current.copy(
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                cleanupFinishedAt = Date(),
                cleanupError = "Vision verification needs review",
                lastCleanedBy = null,
                extractionSource = extractionSource,
                extractionRunPath = runPath,
                needsAttention = true,
                updatedAt = Date()
            )
            return withConfidenceAssessment(preserved, rawOcr)
        }

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
            cleanupStatus = if (cleanupAccepted) Coupon.CleanupStatus.CLEANED else Coupon.CleanupStatus.FAILED,
            cleanupFinishedAt = Date(),
            cleanupError = if (cleanupAccepted) null else "Vision verification needs review",
            lastCleanedBy = if (cleanupAccepted) ModelCatalog.GEMMA_VISION_READER_NAME else null,
            extractionSource = extractionSource,
            extractionRunPath = runPath,
            normalizedDescription = CouponDedupUtils.normalizeDescription(verifiedDescription),
            needsAttention = info.needsAttention || allowUserEditedFallback || !strictVisionVerified,
            updatedAt = Date()
        )
        return withConfidenceAssessment(verified, rawOcr)
    }

    private fun String?.withoutTrustedModelSource(): String? {
        return when (this) {
            Coupon.ExtractionSource.VISION_VERIFIED,
            Coupon.ExtractionSource.QWEN_CLEANED -> null
            else -> this
        }
    }

    private fun parseCanonicalJsonToCouponInfo(
        json: String,
        captureTimestamp: Date?
    ): CouponInfo {
        val obj = JSONObject(json)
        val expiryText = obj.optNullableString(CouponSchemaKeys.EXPIRY_DATE)
        val expiryDate = ModelExpiryNormalizer.parse(expiryText, captureTimestamp)
        if (!expiryText.isNullOrBlank() && expiryDate == null && !expiryText.equals("unknown", ignoreCase = true)) {
            Log.w(TAG, "Could not normalize model expiryDate='$expiryText'")
        }
        val storeName = obj.optNullableString(CouponSchemaKeys.STORE_NAME).orEmpty()
        val evidence = obj.optJSONArray(CouponSchemaKeys.STORE_NAME_EVIDENCE)?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.orEmpty()
        val evidenceWeak = storeName.isBlank() ||
            GenericFieldHeuristics.isGenericOrMissing(storeName) ||
            obj.optNullableString(CouponSchemaKeys.STORE_NAME_SOURCE).equals("unknown", ignoreCase = true) ||
            evidence.isEmpty()
        return CouponInfo(
            storeName = storeName,
            description = obj.optNullableString(CouponSchemaKeys.DESCRIPTION).orEmpty(),
            expiryDate = expiryDate,
            redeemCode = obj.optNullableString(CouponSchemaKeys.REDEEM_CODE),
            needsAttention = obj.optBoolean(CouponSchemaKeys.NEEDS_ATTENTION, false) || evidenceWeak,
            storeNameSource = obj.optNullableString(CouponSchemaKeys.STORE_NAME_SOURCE),
            storeNameEvidence = evidence
        )
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
        val supportedCount = description
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.trim(',', '.', '*', ':', ';', '(', ')') }
            .filter { it.length >= 3 }
            .count { token -> ocrTokens.contains(token) }
        return supportedCount >= 3
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
        private const val CLEANUP_TIMEOUT_MS = 180_000L
        private const val MIN_VISION_CROP_ANCHORS = 2
        private const val MIN_VISION_CROP_PADDING_PX = 120
        private const val MIN_EVIDENCE_TOKEN_LENGTH = 4
        private const val MAX_SCOPED_ANCHOR_TOKENS = 18
        private const val VISION_CROP_VERTICAL_PADDING_RATIO = 0.08f
        private const val VISION_CROP_HORIZONTAL_PADDING_RATIO = 0.04f
        private const val MAX_VISION_CROP_HEIGHT_RATIO = 0.9f
        private const val FIELD_SOURCE_OCR_RULE = "OCR_RULE"
        private const val FIELD_SOURCE_VISION = "VISION"
        private const val FIELD_SOURCE_MISSING = "MISSING"
        private const val FIELD_SOURCE_USER_EDITED = "USER_EDITED"
        private const val FIELD_SOURCE_PRESERVED = "PRESERVED"
        private val GENERIC_DESCRIPTION_ANCHORS = setOf(
            "coupon",
            "offer",
            "details",
            "redeem",
            "cashback",
            "valid",
            "expires",
            "prime",
            "more",
            "with"
        )

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

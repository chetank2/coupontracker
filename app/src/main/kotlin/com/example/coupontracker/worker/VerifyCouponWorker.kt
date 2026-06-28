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
import com.example.coupontracker.extraction.vision.VisionFieldExtraction
import com.example.coupontracker.extraction.vision.VisionFieldJsonParser
import com.example.coupontracker.extraction.vision.VisionEvidenceMergePolicy
import com.example.coupontracker.extraction.vision.VisionFieldMergeInput
import com.example.coupontracker.extraction.vision.VisionLayoutCard
import com.example.coupontracker.model.ModelPaths
import com.example.coupontracker.ocr.OcrEngine
import com.example.coupontracker.ocr.OcrTextSpan
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

            val deterministicCleaned = buildDeterministicCleanedCoupon(coupon, ocrText)
            val shouldRunVision = shouldRunVisionVerification(
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
                    deterministicCleaned.copy(
                        cleanupStatus = Coupon.CleanupStatus.RUNNING,
                        cleanupStartedAt = Date(),
                        cleanupFinishedAt = null,
                        cleanupError = null,
                        lastCleanedBy = null,
                        updatedAt = Date()
                    )
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
            val visionInput = prepareTwoPassVisionInput(bitmap, visionBaseCoupon, ocrText)
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
                val visionResult = withTimeout(FIELD_LABEL_TIMEOUT_MS) {
                    gemmaVisionCouponModel.extractRawFromImage(
                        image = visionInput.bitmap,
                        ocrText = cropEvidenceText,
                        prompt = buildVisionFieldLabelPrompt()
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

    internal data class VisionInput(
        val bitmap: Bitmap,
        val usedTargetedCrop: Boolean,
        val source: String,
        val normalizedBoundsJson: String?,
        val pixelCrop: Rect?,
        val layoutState: String?,
        val debugEvidence: String?
    ) {
        fun toMergeInput(): VisionFieldMergeInput {
            return VisionFieldMergeInput(
                usedTargetedCrop = usedTargetedCrop,
                source = source,
                normalizedBoundsJson = normalizedBoundsJson,
                pixelCrop = pixelCrop,
                layoutState = layoutState,
                debugEvidence = debugEvidence
            )
        }
    }

    private suspend fun prepareTwoPassVisionInput(source: Bitmap, coupon: Coupon, rawOcr: String): VisionInput? {
        Log.i(TAG, "GEMMA_LAYOUT_STARTED image=${source.width}x${source.height}")
        var layoutFailureEvidence: String? = null
        var layoutRawJson: String? = null
        val layoutCrop = runCatching {
            val result = withTimeout(LAYOUT_TIMEOUT_MS) {
                gemmaVisionCouponModel.extractRawFromImage(
                    image = source,
                    ocrText = null,
                    prompt = buildVisionLayoutPrompt()
                )
            }
            layoutRawJson = result.canonicalJson
            val detection = visionFieldJsonParser.parseLayout(result.canonicalJson)
            val selected = detection.activeCard
            Log.i(
                TAG,
                "GEMMA_LAYOUT_PARSED cards=${detection.cards.size} confidence=${"%.2f".format(detection.confidence)} " +
                    "selectedConfidence=${"%.2f".format(selected?.confidence ?: 0f)} bounds=${selected?.bounds}"
            )
            if (selected == null || detection.confidence < MIN_LAYOUT_CONFIDENCE || selected.confidence < MIN_LAYOUT_CONFIDENCE) {
                Log.w(TAG, "GEMMA_LAYOUT_REJECTED reason=low_confidence")
                null
            } else {
                cropBitmapToLayoutCard(source, selected)
            }
        }.onFailure { error ->
            Log.w(TAG, "GEMMA_LAYOUT_REJECTED reason=${error.javaClass.simpleName} message=${error.message}")
            layoutFailureEvidence = visionEvidenceMergePolicy.buildFailureEvidence(
                stage = "layout_parse_failed",
                visionInput = null,
                error = error,
                rawVisionJson = layoutRawJson
            )
        }.getOrNull()
        if (layoutCrop != null) return layoutCrop

        return prepareOcrTargetedVisionBitmap(source, coupon, rawOcr, layoutFailureEvidence)
    }

    private fun cropBitmapToLayoutCard(source: Bitmap, card: VisionLayoutCard): VisionInput? {
        val pixelCrop = card.bounds.toPixelRect(source, LAYOUT_CROP_PADDING_RATIO)
        val widthRatio = pixelCrop.width().toFloat() / source.width.toFloat()
        val heightRatio = pixelCrop.height().toFloat() / source.height.toFloat()
        val areaRatio = (pixelCrop.width().toFloat() * pixelCrop.height().toFloat()) /
            (source.width.toFloat() * source.height.toFloat())
        if (widthRatio >= MAX_LAYOUT_CROP_WIDTH_RATIO ||
            heightRatio >= MAX_LAYOUT_CROP_HEIGHT_RATIO ||
            areaRatio >= MAX_LAYOUT_CROP_AREA_RATIO
        ) {
            Log.w(
                TAG,
                "GEMMA_LAYOUT_REJECTED reason=crop_too_large pixelCrop=${pixelCrop.flattenToString()} " +
                    "widthRatio=${"%.2f".format(widthRatio)} heightRatio=${"%.2f".format(heightRatio)} " +
                    "areaRatio=${"%.2f".format(areaRatio)}"
            )
            return null
        }
        Log.i(
            TAG,
            "GEMMA_LAYOUT_CROP_SELECTED normalizedBounds=${card.bounds} pixelCrop=${pixelCrop.flattenToString()} " +
                "layoutState=${card.layoutState} confidence=${"%.2f".format(card.confidence)}"
        )
        val crop = Bitmap.createBitmap(source, pixelCrop.left, pixelCrop.top, pixelCrop.width(), pixelCrop.height())
        Log.i(TAG, "GEMMA_LAYOUT_CROP_READY source=${source.width}x${source.height} crop=${crop.width}x${crop.height}")
        return VisionInput(
            bitmap = crop,
            usedTargetedCrop = true,
            source = "layout",
            normalizedBoundsJson = buildNormalizedBoundsJson(card).toString(),
            pixelCrop = pixelCrop,
            layoutState = card.layoutState,
            debugEvidence = null
        )
    }

    private fun buildNormalizedBoundsJson(card: VisionLayoutCard): JSONObject {
        return JSONObject()
            .put("x", card.bounds.x.toDouble())
            .put("y", card.bounds.y.toDouble())
            .put("w", card.bounds.w.toDouble())
            .put("h", card.bounds.h.toDouble())
    }

    private suspend fun prepareOcrTargetedVisionBitmap(
        source: Bitmap,
        coupon: Coupon,
        rawOcr: String,
        debugEvidence: String?
    ): VisionInput? {
        val crop = runCatching { cropBitmapToCouponEvidence(source, coupon, rawOcr) }
            .onFailure { Log.w(TAG, "Could not create target crop for Gemma Vision: ${it.message}") }
            .getOrNull()
        if (crop == null) {
            Log.w(TAG, "Gemma Vision rejected original bitmap fallback because no crop was isolated")
            return null
        }
        Log.d(
            TAG,
            "Gemma Vision using OCR-targeted crop ${source.width}x${source.height} -> " +
                "${crop.bitmap.width}x${crop.bitmap.height} pixelCrop=${crop.rect.flattenToString()}"
        )
        return VisionInput(
            bitmap = crop.bitmap,
            usedTargetedCrop = true,
            source = "ocr_targeted_fallback",
            normalizedBoundsJson = null,
            pixelCrop = crop.rect,
            layoutState = null,
            debugEvidence = debugEvidence
        )
    }

    private data class TargetedCrop(
        val bitmap: Bitmap,
        val rect: Rect
    )

    private suspend fun cropBitmapToCouponEvidence(source: Bitmap, coupon: Coupon, rawOcr: String): TargetedCrop? {
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
        val initialCropBottom = (maxAnchorY + verticalPadding).coerceAtMost(source.height)
        val maxCropHeight = (source.height * MAX_VISION_CROP_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val cropBottom = if (initialCropBottom - cropTop > maxCropHeight) {
            (cropTop + maxCropHeight).coerceAtMost(source.height)
        } else {
            initialCropBottom
        }
        val cropHeight = cropBottom - cropTop
        if (cropHeight <= 0) return null

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

        val rect = Rect(cropLeft, cropTop, cropRight, cropBottom)
        return TargetedCrop(
            bitmap = Bitmap.createBitmap(source, rect.left, rect.top, rect.width(), rect.height()),
            rect = rect
        )
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

    private fun shouldRunVisionVerification(
        userRequested: Boolean,
        automaticVerification: Boolean,
        deterministicCleaned: Coupon?,
        rawOcr: String,
        gemmaEnabled: Boolean,
        gemmaInstalled: Boolean
    ): Boolean {
        if (!gemmaEnabled || !gemmaInstalled) return false
        if (userRequested) return true
        return automaticVerification &&
            deterministicCleaned?.needsVisionReviewAfterDeterministicCleanup(rawOcr) == true
    }

    private fun Coupon.needsVisionReviewAfterDeterministicCleanup(rawOcr: String): Boolean {
        val assessment = CouponExtractionConfidenceScorer.score(this, rawOcr)
        val missingCodeState = redeemCode.isNullOrBlank() && codeState == Coupon.CodeState.UNKNOWN
        val missingExpiryState = expiryDate == null && expiryState == Coupon.ExpiryState.UNKNOWN
        return needsAttention ||
            cleanupStatus == Coupon.CleanupStatus.FAILED ||
            assessment.recommendation == ExtractionRecommendation.VERIFY_WITH_VISION ||
            assessment.recommendation == ExtractionRecommendation.MANUAL_REVIEW ||
            missingCodeState ||
            missingExpiryState
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
        val cleanupBaseline = baseline.copy(
            cleanupStatus = latest.cleanupStatus,
            cleanupStartedAt = latest.cleanupStartedAt,
            cleanupFinishedAt = latest.cleanupFinishedAt,
            cleanupError = latest.cleanupError,
            updatedAt = latest.updatedAt
        )
        return if (latest.extractionSource == Coupon.ExtractionSource.USER_EDITED) {
            latest
        } else {
            cleanupBaseline
        }
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

    private fun buildVisionVerificationPrompt(): String {
        return "Read this coupon image. Return JSON only: " +
            "{\"storeName\":\"\",\"description\":\"\",\"redeemCode\":null,\"expiryDate\":null," +
            "\"storeNameSource\":\"vision\",\"storeNameEvidence\":[],\"needsAttention\":false}. " +
            "Use visible text only. Code must be exact. Missing fields are null."
    }

    private fun buildVisionLayoutPrompt(): String {
        return "JSON only. Layout only. Do not return store, offer, code, or expiry. " +
            "Return exactly {\"layoutState\":\"\",\"confidence\":0,\"cards\":[{\"active\":true,\"confidence\":0,\"bounds\":{\"x\":0,\"y\":0,\"w\":0,\"h\":0}}]}. " +
            "Bounds are normalized 0..1. Pick one active foreground coupon/card/modal. " +
            "layoutState: COMPLETE, PARTIAL, MODAL_FOREGROUND, MULTI_CARD, or LOW_CONFIDENCE."
    }

    private fun buildVisionFieldLabelPrompt(): String {
        return "JSON only, no markdown. Use visible crop/OCR text only. " +
            "Return tiny JSON with keys ls,s,d,cs,c,es,e,conf. " +
            "ls one of COMPLETE, PARTIAL, MODAL_FOREGROUND, MULTI_CARD, LOW_CONFIDENCE. " +
            "cs one of PRESENT, NO_CODE_NEEDED, NOT_VISIBLE, UNKNOWN. " +
            "es one of PRESENT, NOT_VISIBLE, UNKNOWN. " +
            "s=store, d=offer, c=exact code or null, e=expiry text or null. " +
            "Use null for absent text. Do not invent or copy example values."
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
        private const val LAYOUT_TIMEOUT_MS = 45_000L
        private const val FIELD_LABEL_TIMEOUT_MS = 90_000L
        private const val MIN_VISION_CROP_ANCHORS = 2
        private const val MIN_VISION_CROP_PADDING_PX = 120
        private const val MIN_EVIDENCE_TOKEN_LENGTH = 4
        private const val MAX_SCOPED_ANCHOR_TOKENS = 18
        private const val VISION_CROP_VERTICAL_PADDING_RATIO = 0.08f
        private const val VISION_CROP_HORIZONTAL_PADDING_RATIO = 0.04f
        private const val MAX_VISION_CROP_HEIGHT_RATIO = 0.52f
        private const val MIN_LAYOUT_CONFIDENCE = 0.5f
        private const val LAYOUT_CROP_PADDING_RATIO = 0.07f
        private const val MAX_LAYOUT_CROP_WIDTH_RATIO = 0.92f
        private const val MAX_LAYOUT_CROP_HEIGHT_RATIO = 0.68f
        private const val MAX_LAYOUT_CROP_AREA_RATIO = 0.62f
        private const val FIELD_SOURCE_OCR_RULE = "OCR_RULE"
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

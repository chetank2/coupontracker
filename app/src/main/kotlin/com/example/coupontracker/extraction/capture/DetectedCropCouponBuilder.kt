package com.example.coupontracker.extraction.capture

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.model.FieldType
import com.example.coupontracker.data.util.DescriptionUtils
import com.example.coupontracker.extraction.FieldCandidate
import com.example.coupontracker.extraction.TextBlock
import com.example.coupontracker.extraction.validation.CouponFieldBundleValidator
import com.example.coupontracker.extraction.validation.FieldValueBundle
import com.example.coupontracker.ml.CouponInstance
import com.example.coupontracker.util.CouponExtractionConfidenceScorer
import com.example.coupontracker.util.CouponFixContext
import com.example.coupontracker.util.CouponPostProcessor
import com.example.coupontracker.util.DateParser
import com.example.coupontracker.util.ExtractionRecommendation
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.RunPath
import com.example.coupontracker.util.normalizeExpiryDate
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DetectedCropCouponBuilder {
    fun buildCoupon(
        couponInstance: CouponInstance,
        extraction: DetectedCropFieldExtraction,
        imageUri: String?,
        captureTimestamp: Date?
    ): Coupon {
        return finalizeCoupon(
            base = createCouponFromInstance(
                couponInstance = couponInstance,
                extraction = extraction,
                imageUri = imageUri,
                captureTimestamp = captureTimestamp
            ),
            ocrText = extraction.fullOcrText,
            captureTimestamp = captureTimestamp
        )
    }

    fun buildProvisionalCoupon(
        couponInstance: CouponInstance,
        extraction: DetectedCropFieldExtraction,
        imageUri: String?,
        captureTimestamp: Date?
    ): Coupon {
        val baseCoupon = createCouponFromInstance(
            couponInstance = couponInstance,
            extraction = extraction,
            imageUri = imageUri,
            captureTimestamp = captureTimestamp
        )

        val validatedCoupon = validateDetectedCouponInstance(
            coupon = baseCoupon,
            extraction = extraction,
            expiryDateText = extraction.fields["expiryDate"]
        )

        return markOcrProvisional(
            finalizeCoupon(
                base = validatedCoupon,
                ocrText = extraction.fullOcrText,
                captureTimestamp = captureTimestamp
            )
        )
    }

    private fun createCouponFromInstance(
        couponInstance: CouponInstance,
        extraction: DetectedCropFieldExtraction,
        imageUri: String?,
        captureTimestamp: Date?
    ): Coupon {
        val extractedInfo = extraction.fields
        val expiryDate = DateParser.parseDate(extractedInfo["expiryDate"], captureTimestamp)
            ?: parseExpiryDate(extractedInfo["expiryDate"])

        val cashbackDetail = extractedInfo["amount"]?.let { raw ->
            DescriptionUtils.formatCashbackDetail(raw) ?: raw
        }

        val runPathSummary = extraction.runPath?.let { path ->
            buildString {
                append(path.strategy.ifBlank { "LLM" })
                if (path.final.isNotBlank()) {
                    append(" \u2192 ")
                    append(path.final)
                }
            }.ifBlank { null }
        }

        val baseDescription = extractedInfo["description"] ?: extractedInfo["benefit"] ?: "Multi-coupon detected"
        val mergedDescription = DescriptionUtils.appendDetails(baseDescription, cashbackDetail)

        return Coupon(
            storeName = extractedInfo["storeName"] ?: extractedInfo["app"] ?: Coupon.Defaults.UNKNOWN_STORE,
            description = mergedDescription,
            expiryDate = expiryDate,
            redeemCode = extractedInfo["code"],
            imageUri = imageUri,
            category = determineCategory(extractedInfo),
            status = when (couponInstance.status) {
                com.example.coupontracker.ml.CouponStatus.COMPLETE -> "ACTIVE"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_TOP -> "PARTIAL"
                com.example.coupontracker.ml.CouponStatus.PARTIAL_BOTTOM -> "PARTIAL"
            },
            extractionQualityScore = extraction.qualityScore,
            extractionConfidenceBreakdown = extraction.fieldConfidences,
            extractionStage = extraction.sourceStage?.name,
            extractionRunPath = runPathSummary,
            rawOcrText = extraction.fullOcrText,
            extractionSource = Coupon.ExtractionSource.OCR_FAST,
            extractionTimestamp = Date()
        )
    }

    private fun validateDetectedCouponInstance(
        coupon: Coupon,
        extraction: DetectedCropFieldExtraction,
        expiryDateText: String?
    ): Coupon {
        val validation = CouponFieldBundleValidator().validate(
            bundle = FieldValueBundle(
                storeName = coupon.storeName,
                description = coupon.description,
                redeemCode = coupon.redeemCode,
                expiryDateText = expiryDateText,
                codeState = coupon.codeState,
                expiryState = coupon.expiryState
            ),
            fields = buildDetectedFieldCandidates(coupon, extraction, expiryDateText),
            rawOcrText = extraction.fullOcrText,
            ocrBlocks = extraction.ocrBlocks,
            imageHeight = extraction.imageHeight
        )

        val issueMessages = validation.issues.map { "${it.field.name}:${it.message}" }
        val hasError = validation.issues.any { it.severity == CouponFieldBundleValidator.Severity.ERROR } ||
            !validation.spatialResult.consistent
        val foregroundModal = coupon.layoutState == Coupon.LayoutState.MODAL_FOREGROUND
        val multiCouponRegion = issueMessages.any { it.contains("multiple_coupon_sections_in_single_region") } &&
            !foregroundModal
        val onlyForegroundOwnershipIssue = foregroundModal &&
            issueMessages.isNotEmpty() &&
            issueMessages.all { it.contains("multiple_coupon_sections_in_single_region") }
        val needsAttention = validation.needsAttention && !onlyForegroundOwnershipIssue
        val invalidCode = issueMessages.any {
            it.contains("COUPON_CODE:") || it.contains("store_duplicates_code") || it.contains("description_duplicates_code")
        }
        val runPath = JSONObject()
            .put("stage", "detected_coupon_instance")
            .put("validator", "CouponFieldBundleValidator")
            .put("trusted", validation.trusted)
            .put("needsAttention", validation.needsAttention)
            .put("issues", JSONArray(issueMessages))
            .toString()

        return coupon.copy(
            redeemCode = if (multiCouponRegion || invalidCode) null else coupon.redeemCode,
            expiryDate = if (multiCouponRegion) null else coupon.expiryDate,
            needsAttention = coupon.needsAttention || needsAttention,
            cleanupStatus = if (needsAttention) {
                Coupon.CleanupStatus.FAILED
            } else {
                coupon.cleanupStatus
            },
            cleanupError = if (needsAttention) validation.reason else null,
            extractionSource = if (validation.trusted && !hasError) {
                Coupon.ExtractionSource.OCR_VERIFIED
            } else {
                coupon.extractionSource
            },
            extractionRunPath = runPath
        )
    }

    private fun buildDetectedFieldCandidates(
        coupon: Coupon,
        extraction: DetectedCropFieldExtraction,
        expiryDateText: String?
    ): Map<FieldType, FieldCandidate> {
        fun confidenceFor(fieldName: String): Float {
            return extraction.fieldConfidences[fieldName]
                ?: extraction.fieldConfidences[fieldName.lowercase(Locale.ROOT)]
                ?: 0.55f
        }

        return buildMap {
            put(
                FieldType.STORE_NAME,
                FieldCandidate(coupon.storeName, confidenceFor("storeName"), "detected_coupon_ocr", null)
            )
            put(
                FieldType.DESCRIPTION,
                FieldCandidate(coupon.description, confidenceFor("description"), "detected_coupon_ocr", null)
            )
            coupon.redeemCode?.takeIf { it.isNotBlank() }?.let { code ->
                put(FieldType.COUPON_CODE, FieldCandidate(code, confidenceFor("code"), "detected_coupon_ocr", null))
            }
            val expiryCandidate = expiryDateText?.takeIf { it.isNotBlank() } ?: coupon.expiryDate?.toString()
            expiryCandidate?.let { expiry ->
                put(FieldType.EXPIRY_DATE, FieldCandidate(expiry, confidenceFor("expiryDate"), "detected_coupon_ocr", null))
            }
        }
    }

    private fun finalizeCoupon(
        base: Coupon,
        ocrText: String?,
        captureTimestamp: Date?
    ): Coupon {
        val refined = CouponPostProcessor.refine(
            coupon = base,
            context = CouponFixContext(
                ocrText = ocrText,
                captureTimestamp = captureTimestamp
            )
        )
        val normalized = refined.copy(expiryDate = normalizeExpiryDate(refined.expiryDate, captureTimestamp))
        val assessment = CouponExtractionConfidenceScorer.score(normalized, ocrText)
        return normalized.copy(
            extractionQualityScore = assessment.score,
            extractionConfidenceBreakdown = normalized.extractionConfidenceBreakdown.ifEmpty {
                assessment.fieldConfidences
            },
            needsAttention = normalized.needsAttention ||
                assessment.recommendation != ExtractionRecommendation.SAVE_DIRECTLY
        )
    }

    private fun determineCategory(extractedInfo: Map<String, String>): String {
        val relevantKeys = listOf("storeName", "description", "terms", "benefit", "app")
        val text = relevantKeys.mapNotNull { key -> extractedInfo[key] }
            .joinToString(" ")
            .lowercase()

        return when {
            text.contains("food") || text.contains("restaurant") || text.contains("dining") || text.contains("meal") -> "Food"
            text.contains("fashion") || text.contains("clothing") || text.contains("apparel") || text.contains("wear") -> "Fashion"
            text.contains("grocery") || text.contains("groceries") || text.contains("supermarket") -> "Grocery"
            text.contains("travel") || text.contains("booking") || text.contains("hotel") -> "Travel"
            text.contains("electronics") || text.contains("mobile") || text.contains("appliance") -> "Electronics"
            else -> "Other"
        }
    }

    companion object {
        private const val DETECTED_CROP_OCR_PENDING_REASON =
            "Background vision verification pending for OCR-only detected crop"

        fun parseExpiryDate(
            dateString: String?,
            locale: Locale = Locale.getDefault()
        ): Date? {
            if (dateString.isNullOrBlank()) return null

            val cleanedDate = dateString
                .trim()
                .replace("\u202F", " ")
                .let {
                    val timeRegex = Regex("\\s*(?:at\\s*)?\\d{1,2}:\\d{2}(?:\\s*[AaPp][Mm])?(?:\\s*[A-Za-z]+)?$")
                    timeRegex.replace(it) { _ -> "" }.trim()
                }

            val dateFormats = listOf(
                "dd/MM/yyyy",
                "MM/dd/yyyy",
                "yyyy/MM/dd",
                "dd-MM-yyyy",
                "MM-dd-yyyy",
                "yyyy-MM-dd",
                "dd.MM.yyyy",
                "MM.dd.yyyy",
                "yyyy.MM.dd",
                "dd MMM yyyy",
                "dd MMMM yyyy",
                "dd MMM, yyyy",
                "dd MMMM, yyyy",
                "dd/MM/yy",
                "MM/dd/yy",
                "dd-MM-yy",
                "MM-dd-yy",
                "dd.MM.yy",
                "MM.dd.yy",
                "dd MMM yy",
                "dd MMMM yy"
            )

            val twoDigitYearStart = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2000)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            for (format in dateFormats) {
                try {
                    val sdf = SimpleDateFormat(format, locale)
                    sdf.isLenient = false
                    if (format.contains("yy") && !format.contains("yyyy")) {
                        sdf.set2DigitYearStart(twoDigitYearStart)
                    }
                    val parsed = sdf.parse(cleanedDate)
                    if (parsed != null) {
                        return parsed
                    }
                } catch (e: Exception) {
                    // Try next format.
                }
            }

            return null
        }

        fun markOcrProvisional(coupon: Coupon): Coupon {
            val pendingEvidence = listOf(
                "background_vision_verification=pending",
                "source=single_detected_crop_ocr_only"
            )
            val mergedEvidence = sequenceOf(
                coupon.debugVisionEvidence,
                pendingEvidence.joinToString("; ")
            )
                .filterNot { it.isNullOrBlank() }
                .joinToString("; ")

            return coupon.copy(
                needsAttention = true,
                cleanupStatus = Coupon.CleanupStatus.PENDING,
                cleanupStartedAt = null,
                cleanupFinishedAt = null,
                cleanupError = DETECTED_CROP_OCR_PENDING_REASON,
                layoutState = if (coupon.layoutState == Coupon.LayoutState.COMPLETE) {
                    Coupon.LayoutState.LOW_CONFIDENCE
                } else {
                    coupon.layoutState
                },
                debugVisionEvidence = mergedEvidence
            )
        }
    }
}

data class DetectedCropFieldExtraction(
    val fields: Map<String, String>,
    val runPath: RunPath? = null,
    val qualityScore: Int? = null,
    val fieldConfidences: Map<String, Float> = emptyMap(),
    val sourceStage: ExtractionStage? = null,
    val fullOcrText: String? = null,
    val ocrBlocks: List<TextBlock> = emptyList(),
    val imageHeight: Int = 0
)

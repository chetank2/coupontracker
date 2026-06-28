package com.example.coupontracker.worker

import com.example.coupontracker.data.model.Coupon
import com.example.coupontracker.data.repository.CouponRepository
import java.util.Date

class CouponCleanupStatusWriter(
    private val couponRepository: CouponRepository
) {

    suspend fun markGenericFailure(couponId: Long, message: String) {
        val current = couponRepository.getCouponById(couponId) ?: return
        couponRepository.updateCoupon(
            current.copy(
                cleanupStatus = Coupon.CleanupStatus.FAILED,
                cleanupFinishedAt = Date(),
                cleanupError = message,
                lastCleanedBy = null,
                extractionSource = current.extractionSource.withoutTrustedModelSource(),
                needsAttention = true,
                updatedAt = Date()
            )
        )
    }

    fun userFacingFailure(rawMessage: String?): String {
        val message = rawMessage.orEmpty()
        return when {
            message.contains("json", ignoreCase = true) ||
                message.contains("parse", ignoreCase = true) ||
                message.contains("unterminated", ignoreCase = true) ||
                message.contains("malformed", ignoreCase = true) ||
                message.contains("vision field response", ignoreCase = true) ||
                message.contains("vision layout response", ignoreCase = true) ||
                message.contains("field-label response", ignoreCase = true) ||
                message.contains("unusable normalized bounds", ignoreCase = true) -> {
                "Gemma Vision could not return a usable structured result. The OCR result is still saved for review."
            }
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
}

internal fun String?.withoutTrustedModelSource(): String? {
    return when (this) {
        Coupon.ExtractionSource.VISION_VERIFIED,
        Coupon.ExtractionSource.QWEN_CLEANED -> null
        else -> this
    }
}

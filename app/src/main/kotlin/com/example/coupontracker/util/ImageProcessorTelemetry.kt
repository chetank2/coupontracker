package com.example.coupontracker.util

import com.example.coupontracker.ocr.TesseractOcrEngine

/**
 * Context describing why the pipeline fell back to ML Kit.
 */
data class MlKitFallbackContext(
    val reason: String,
    val cause: Throwable? = null,
    val initStats: TesseractOcrEngine.InitStats? = null,
    val ocrReady: Boolean,
    val attemptedEngines: List<String> = listOf("TESSERACT")
)

/**
 * Build a telemetry RunPath payload for ML Kit fallback scenarios so that
 * observability dashboards can track real-world frequency.
 */
fun buildMlKitFallbackRunPath(context: MlKitFallbackContext): RunPath {
    val reasons = mutableListOf(context.reason)
    context.cause?.message?.let { message ->
        if (message.isNotBlank()) {
            reasons.add("error:${message.take(80)}")
        }
    }
    context.initStats?.let { stats ->
        stats.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            reasons.add("tesseract:${error.take(80)}")
        }
        stats.installedChecksum?.takeIf { it.isNotBlank() }?.let { checksum ->
            reasons.add("traineddata:${checksum.take(16)}")
        }
        reasons.add("attempt:${stats.attempt}")
        reasons.add("copied:${stats.copiedTrainedData}")
    }

    return RunPath(
        strategy = "OCR_FIRST",
        tried = context.attemptedEngines.toMutableList(),
        final = "MLKIT",
        reasons = reasons,
        nativeAvailable = context.ocrReady,
        totalTimeMs = 0L
    )
}

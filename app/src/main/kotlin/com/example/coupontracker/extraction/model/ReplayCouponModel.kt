package com.example.coupontracker.extraction.model

import android.graphics.Bitmap
import java.security.MessageDigest
import java.nio.ByteBuffer
import kotlin.system.measureTimeMillis

/**
 * Hermetic extraction model for benchmarks and CI. Looks up pre-recorded
 * extraction JSON by image hash. Never touches a real runtime.
 *
 * @param recordings map of imageSha256Hex → canonical JSON
 * @param hasher pluggable for tests; default hashes the bitmap's backing bytes
 */
class ReplayCouponModel(
    private val recordings: Map<String, String>,
    private val hasher: (Bitmap) -> String = DEFAULT_HASHER
) : CouponExtractionModel {

    override val mode: ModelMode = ModelMode.BENCHMARK_REPLAY

    override suspend fun extractFromText(
        ocrText: String,
        prompt: String,
        grammar: String?
    ): ModelExtractionResult {
        throw UnsupportedOperationException(
            "ReplayCouponModel only supports extractFromImage (keyed by image hash)"
        )
    }

    override suspend fun extractFromImage(
        image: Bitmap,
        ocrText: String?,
        prompt: String
    ): ModelExtractionResult {
        lateinit var recorded: String
        val latency = measureTimeMillis {
            val sha = hasher(image)
            recorded = recordings[sha]
                ?: throw IllegalStateException(
                    "no replay recording for image hash=$sha. " +
                            "Record one in benchmark/goldenset/replay/ or regenerate."
                )
        }
        return ModelExtractionResult(
            canonicalJson = recorded,
            latencyMs = latency,
            usedFallback = false
        )
    }

    companion object {
        val DEFAULT_HASHER: (Bitmap) -> String = { bitmap ->
            val buf = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buf)
            MessageDigest.getInstance("SHA-256")
                .digest(buf.array())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

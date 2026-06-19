package com.example.coupontracker.llm.gemma

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.coupontracker.model.ModelPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflection-backed Gemma vision verifier using MediaPipe GenAI sessions.
 *
 * This intentionally avoids static references to tasks-genai 0.10.27 classes,
 * because that artifact is Java 21 bytecode while the Android project still
 * builds with Java 17.
 */
@Singleton
class GemmaVisionRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()
    @Volatile private var inference: Any? = null

    init {
        AppContextHolder.context = context.applicationContext
    }

    suspend fun runVisionInference(bitmap: Bitmap, prompt: String): String? =
        withContext(Dispatchers.IO) {
            val engine = try {
                acquireOrThrow()
            } catch (e: Exception) {
                Log.w(TAG, "Gemma vision runtime is not ready", e)
                return@withContext null
            }
            mutex.withLock {
                try {
                    generateVision(engine, bitmap, prompt)
                } catch (e: Exception) {
                    Log.w(TAG, "Gemma vision inference failed", e)
                    null
                }
            }
        }

    suspend fun runVisionInferenceOrThrow(bitmap: Bitmap, prompt: String): String =
        withContext(Dispatchers.IO) {
            val engine = acquireOrThrow()
            mutex.withLock {
                generateVision(engine, bitmap, prompt)
            }
        }

    fun isReady(): Boolean = inference != null

    fun release() {
        synchronized(this) {
            runCatching { (inference as? AutoCloseable)?.close() }
            inference = null
        }
    }

    fun modelBundleExists(): Boolean = ModelPaths.getGemmaVisionInstallStatus(context).installed

    private fun acquireOrThrow(): Any {
        val cached = inference
        if (cached != null) return cached
        synchronized(this) {
            inference?.let { return it }
            val status = ModelPaths.getGemmaVisionInstallStatus(context)
            val model = status.modelFile ?: throw IllegalStateException(status.message)
            val visionOptions = buildVisionOptionsIfPresent()
            val options = GemmaRuntime.buildInferenceOptions(
                modelPath = model.absolutePath,
                maxTokens = DEFAULT_MAX_TOKENS,
                maxImages = 1,
                visionOptions = visionOptions
            )
            inference = runCatching { GemmaRuntime.createInference(options) }
                .getOrElse { error ->
                    throw IllegalStateException(
                        "Gemma Vision runtime could not initialize ${model.name}: ${error.message}",
                        error
                    )
                }
            return requireNotNull(inference) { "Gemma Vision runtime initialized to null." }
        }
    }

    private fun generateVision(engine: Any, bitmap: Bitmap, prompt: String): String {
        val sessionClass = Class.forName(GemmaRuntime.LLM_SESSION_CLASS)
        val options = GemmaRuntime.buildSessionOptions(enableVision = true)
        val session = sessionClass
            .getMethod(
                "createFromOptions",
                Class.forName(GemmaRuntime.LLM_INFERENCE_CLASS),
                Class.forName(GemmaRuntime.LLM_SESSION_OPTIONS_CLASS)
            )
            .invoke(null, engine, options)
        val scaledBitmap = bitmap.scaleForVision()
        val mpImage = runCatching { buildMpImage(scaledBitmap) }
            .getOrElse { error ->
                throw IllegalStateException(
                    "MediaPipe image bridge is unavailable: ${error.message}",
                    error
                )
            }

        try {
            sessionClass.getMethod("addImage", Class.forName(MP_IMAGE_CLASS)).invoke(session, mpImage)
            sessionClass.getMethod("addQueryChunk", String::class.java).invoke(session, prompt)
            val future = sessionClass.getMethod("generateResponseAsync").invoke(session)
            return try {
                val response = future.javaClass
                    .getMethod("get", Long::class.javaPrimitiveType, TimeUnit::class.java)
                    .invoke(future, RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) as String
                Log.d(TAG, "Gemma vision response chars=${response.length}")
                response
            } catch (error: Throwable) {
                val cause = error.cause ?: error
                if (cause is TimeoutException) {
                    runCatching { sessionClass.getMethod("cancelGenerateResponseAsync").invoke(session) }
                    throw TimeoutException("Gemma Vision timed out after ${RESPONSE_TIMEOUT_MS}ms")
                }
                throw cause
            }
        } finally {
            runCatching { (mpImage as? AutoCloseable)?.close() }
            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            runCatching { (session as? AutoCloseable)?.close() }
        }
    }

    private fun Bitmap.scaleForVision(): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= MAX_VISION_IMAGE_EDGE) return this
        val scale = MAX_VISION_IMAGE_EDGE.toFloat() / longest.toFloat()
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        Log.d(TAG, "Scaling Gemma vision bitmap ${width}x$height -> ${targetWidth}x$targetHeight")
        return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    }

    private fun buildMpImage(bitmap: Bitmap): Any {
        val builderClass = BITMAP_IMAGE_BUILDER_CLASS_CANDIDATES
            .firstNotNullOfOrNull { className -> runCatching { Class.forName(className) }.getOrNull() }
            ?: throw ClassNotFoundException(
                "None of ${BITMAP_IMAGE_BUILDER_CLASS_CANDIDATES.joinToString()} are available"
            )
        val builder = builderClass.getConstructor(Bitmap::class.java).newInstance(bitmap)
        return builderClass.getMethod("build").invoke(builder)
            ?: throw IllegalStateException("BitmapImageBuilder.build returned null")
    }

    private fun buildVisionOptionsIfPresent(): Any? {
        val encoder = File(context.filesDir, VISION_ENCODER_RELATIVE_PATH)
        val adapter = File(context.filesDir, VISION_ADAPTER_RELATIVE_PATH)
        if (!encoder.exists() || !adapter.exists()) {
            return null
        }
        return GemmaRuntime.buildVisionOptions(
            encoderPath = encoder.absolutePath,
            adapterPath = adapter.absolutePath
        )
    }

    companion object {
        private const val TAG = "GemmaVisionRuntime"
        private const val MP_IMAGE_CLASS = "com.google.mediapipe.framework.image.MPImage"
        private const val BITMAP_IMAGE_BUILDER_CLASS = "com.google.mediapipe.framework.image.BitmapImageBuilder"

        const val MODEL_RELATIVE_PATH = "gemma/gemma-3-vision.task"
        const val VISION_ENCODER_RELATIVE_PATH = "gemma/gemma-3-vision-encoder.task"
        const val VISION_ADAPTER_RELATIVE_PATH = "gemma/gemma-3-vision-adapter.task"
        const val DEFAULT_MAX_TOKENS = 512
        private const val RESPONSE_TIMEOUT_MS = 130_000L
        private const val MAX_VISION_IMAGE_EDGE = 512
        private val BITMAP_IMAGE_BUILDER_CLASS_CANDIDATES = listOf(
            "com.google.mediapipe.framework.image.BitmapImageBuilder"
        )
    }
}

package com.example.coupontracker.llm.gemma

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe GenAI multimodal wrapper for Gemma 3 Vision. Uses
 * `LlmInferenceSession.addImage(MPImage)` to attach the bitmap to a
 * single-shot session, then calls `generateResponse` for the JSON output.
 *
 * The MediaPipe vision API surface is version-volatile; this wrapper pins
 * to the `tasks-genai:0.10.14` shape. If a future bump changes the session
 * builder or accessory call, only this class needs editing — the
 * `GemmaVisionCouponModel` adapter sees a stable interface.
 *
 * If the vision model bundle is missing on disk, `runVisionInference`
 * returns null and the calling adapter surfaces the missing-asset case via
 * its standard error path.
 */
@Singleton
class GemmaVisionRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()
    @Volatile private var inference: LlmInference? = null

    suspend fun runVisionInference(bitmap: Bitmap, prompt: String): String? =
        withContext(Dispatchers.IO) {
            val engine = acquire() ?: return@withContext null
            mutex.withLock {
                try {
                    val session = LlmInferenceSession.createFromOptions(
                        engine,
                        LlmInferenceSession.LlmInferenceSessionOptions.builder()
                            .setGraphOptions(
                                GraphOptions.builder()
                                    .setEnableVisionModality(true)
                                    .build()
                            )
                            .build()
                    )
                    session.addQueryChunk(prompt)
                    session.addImage(BitmapImageBuilder(bitmap).build())
                    val response = session.generateResponse()
                    session.close()
                    response
                } catch (e: Exception) {
                    Log.w(TAG, "Gemma vision inference failed", e)
                    null
                }
            }
        }

    fun isReady(): Boolean = inference != null

    fun release() {
        synchronized(this) {
            inference?.close()
            inference = null
        }
    }

    private fun acquire(): LlmInference? {
        val cached = inference
        if (cached != null) return cached
        synchronized(this) {
            inference?.let { return it }
            val path = File(context.filesDir, MODEL_RELATIVE_PATH)
            if (!path.exists()) {
                Log.w(TAG, "Gemma vision bundle not found at ${path.absolutePath}")
                return null
            }
            val options = LlmInferenceOptions.builder()
                .setModelPath(path.absolutePath)
                .setMaxTokens(DEFAULT_MAX_TOKENS)
                .setTopK(1)
                .setTemperature(0.1f)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            return inference
        }
    }

    companion object {
        private const val TAG = "GemmaVisionRuntime"
        const val MODEL_RELATIVE_PATH = "gemma/gemma-3-vision.task"
        const val DEFAULT_MAX_TOKENS = 512
    }
}

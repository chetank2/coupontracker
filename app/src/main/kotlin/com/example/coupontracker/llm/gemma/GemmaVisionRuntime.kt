package com.example.coupontracker.llm.gemma

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaPipe GenAI wrapper reserved for Gemma vision. The currently pinned
 * `tasks-genai:0.10.14` artifact exposes text-only `LlmInference`; it does
 * not ship the session/graph image APIs used by later examples. Until the
 * dependency is bumped to a vision-capable API, this class keeps the adapter
 * compile-safe and returns a text-only response to the prompt.
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
                    Log.w(TAG, "MediaPipe tasks-genai 0.10.14 has no image session API; running text-only prompt fallback")
                    engine.generateResponse(prompt)
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
            val options = LlmInference.LlmInferenceOptions.builder()
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

package com.example.coupontracker.llm.gemma

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around MediaPipe GenAI's LlmInference for the Gemma text
 * path. Structured to mirror LlmRuntimeManager.runTextInference so the
 * adapter-level code is symmetric across backends.
 *
 * Model path defaults to the location ModelAssetManager.GEMMA_TEXT_PATH
 * writes to. If the file is missing, `runTextInference` returns null so
 * the caller (adapter) can surface the missing-asset case via a standard
 * ModelExtractionResult error path.
 */
@Singleton
class GemmaRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()
    @Volatile private var inference: LlmInference? = null

    suspend fun runTextInference(
        ocrText: String,
        prompt: String,
        maxTokensOverride: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val engine = acquire() ?: return@withContext null
        val combined = buildString {
            append(prompt.trim())
            append("\n\n")
            append(ocrText.trim())
        }
        mutex.withLock {
            try {
                engine.generateResponse(combined)
            } catch (e: Exception) {
                Log.w(TAG, "Gemma inference failed", e)
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
                Log.w(TAG, "Gemma model not found at ${path.absolutePath}")
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
        private const val TAG = "GemmaRuntime"
        const val MODEL_RELATIVE_PATH = "gemma/gemma-2b-it.task"
        const val DEFAULT_MAX_TOKENS = 512
    }
}

package com.example.coupontracker.llm.gemma

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reflection-backed MediaPipe GenAI wrapper.
 *
 * tasks-genai 0.10.27 exposes the session API needed for Gemma multimodal
 * models, but its classes are Java 21 bytecode. Static imports break the
 * app's Java 17 Android build during kapt. Reflection keeps compile-time
 * compatibility while allowing the newer runtime to be used on device.
 */
@Singleton
class GemmaRuntime @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()
    @Volatile private var inference: Any? = null

    init {
        AppContextHolder.context = context.applicationContext
    }

    suspend fun runTextInference(
        ocrText: String,
        prompt: String,
        maxTokensOverride: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val engine = acquire(maxTokensOverride ?: DEFAULT_MAX_TOKENS) ?: return@withContext null
        val combined = buildString {
            append(prompt.trim())
            append("\n\n")
            append(ocrText.trim())
        }
        mutex.withLock {
            try {
                generateText(engine, combined)
            } catch (e: Exception) {
                Log.w(TAG, "Gemma inference failed", e)
                null
            }
        }
    }

    fun isReady(): Boolean = inference != null

    fun release() {
        synchronized(this) {
            runCatching { (inference as? AutoCloseable)?.close() }
            inference = null
        }
    }

    private fun acquire(maxTokens: Int): Any? {
        val cached = inference
        if (cached != null) return cached
        synchronized(this) {
            inference?.let { return it }
            val path = File(context.filesDir, MODEL_RELATIVE_PATH)
            if (!path.exists()) {
                Log.w(TAG, "Gemma model not found at ${path.absolutePath}")
                return null
            }
            val options = buildInferenceOptions(
                modelPath = path.absolutePath,
                maxTokens = maxTokens,
                maxImages = 0,
                visionOptions = null
            )
            inference = createInference(options)
            return inference
        }
    }

    private fun generateText(engine: Any, prompt: String): String {
        val sessionClass = Class.forName(LLM_SESSION_CLASS)
        val options = buildSessionOptions(enableVision = false)
        val session = sessionClass
            .getMethod("createFromOptions", Class.forName(LLM_INFERENCE_CLASS), Class.forName(LLM_SESSION_OPTIONS_CLASS))
            .invoke(null, engine, options)

        try {
            sessionClass.getMethod("addQueryChunk", String::class.java).invoke(session, prompt)
            return sessionClass.getMethod("generateResponse").invoke(session) as String
        } finally {
            runCatching { (session as? AutoCloseable)?.close() }
        }
    }

    companion object {
        private const val TAG = "GemmaRuntime"
        const val MODEL_RELATIVE_PATH = "gemma/gemma-2b-it.task"
        const val DEFAULT_MAX_TOKENS = 512

        internal const val LLM_INFERENCE_CLASS = "com.google.mediapipe.tasks.genai.llminference.LlmInference"
        internal const val LLM_OPTIONS_CLASS =
            "com.google.mediapipe.tasks.genai.llminference.LlmInference\$LlmInferenceOptions"
        internal const val LLM_SESSION_CLASS =
            "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession"
        internal const val LLM_SESSION_OPTIONS_CLASS =
            "com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession\$LlmInferenceSessionOptions"
        internal const val GRAPH_OPTIONS_CLASS = "com.google.mediapipe.tasks.genai.llminference.GraphOptions"
        internal const val VISION_OPTIONS_CLASS = "com.google.mediapipe.tasks.genai.llminference.VisionModelOptions"

        internal fun createInference(options: Any): Any {
            return Class.forName(LLM_INFERENCE_CLASS)
                .getMethod("createFromOptions", Context::class.java, Class.forName(LLM_OPTIONS_CLASS))
                .invoke(null, AppContextHolder.context, options)
                ?: error("LlmInference.createFromOptions returned null")
        }

        internal fun buildInferenceOptions(
            modelPath: String,
            maxTokens: Int,
            maxImages: Int,
            visionOptions: Any?
        ): Any {
            val optionsClass = Class.forName(LLM_OPTIONS_CLASS)
            val builder = optionsClass.getMethod("builder").invoke(null)
                ?: error("LlmInferenceOptions.builder returned null")
            builder.callBuilder("setModelPath", String::class.java, modelPath)
            builder.callBuilder("setMaxTokens", Int::class.javaPrimitiveType!!, maxTokens)
            builder.callIfPresent("setMaxNumImages", Int::class.javaPrimitiveType!!, maxImages)
            builder.callIfPresent("setMaxTopK", Int::class.javaPrimitiveType!!, 1)
            builder.callIfPresent("setSupportedLoraRanks", List::class.java, Collections.emptyList<Int>())
            visionOptions?.let {
                builder.callIfPresent("setVisionModelOptions", Class.forName(VISION_OPTIONS_CLASS), it)
            }
            return builder.javaClass.getMethod("build").invoke(builder)
                ?: error("LlmInferenceOptions.build returned null")
        }

        internal fun buildSessionOptions(enableVision: Boolean): Any {
            val optionsClass = Class.forName(LLM_SESSION_OPTIONS_CLASS)
            val builder = optionsClass.getMethod("builder").invoke(null)
                ?: error("LlmInferenceSessionOptions.builder returned null")
            builder.callBuilder("setTopK", Int::class.javaPrimitiveType!!, 1)
            builder.callBuilder("setTopP", Float::class.javaPrimitiveType!!, 0.85f)
            builder.callBuilder("setTemperature", Float::class.javaPrimitiveType!!, 0.1f)
            builder.callBuilder("setRandomSeed", Int::class.javaPrimitiveType!!, 1)
            if (enableVision) {
                builder.callIfPresent("setGraphOptions", Class.forName(GRAPH_OPTIONS_CLASS), buildGraphOptions())
            }
            return builder.javaClass.getMethod("build").invoke(builder)
                ?: error("LlmInferenceSessionOptions.build returned null")
        }

        internal fun buildGraphOptions(): Any {
            val graphClass = Class.forName(GRAPH_OPTIONS_CLASS)
            val builder = graphClass.getMethod("builder").invoke(null)
                ?: error("GraphOptions.builder returned null")
            builder.callBuilder("setIncludeTokenCostCalculator", Boolean::class.javaPrimitiveType!!, false)
            builder.callBuilder("setEnableVisionModality", Boolean::class.javaPrimitiveType!!, true)
            builder.callBuilder("setEnableAudioModality", Boolean::class.javaPrimitiveType!!, false)
            return builder.javaClass.getMethod("build").invoke(builder)
                ?: error("GraphOptions.build returned null")
        }

        internal fun buildVisionOptions(encoderPath: String, adapterPath: String): Any {
            val visionClass = Class.forName(VISION_OPTIONS_CLASS)
            val builder = visionClass.getMethod("builder").invoke(null)
                ?: error("VisionModelOptions.builder returned null")
            builder.callBuilder("setEncoderPath", String::class.java, encoderPath)
            builder.callBuilder("setAdapterPath", String::class.java, adapterPath)
            return builder.javaClass.getMethod("build").invoke(builder)
                ?: error("VisionModelOptions.build returned null")
        }

        internal fun Any.callBuilder(name: String, type: Class<*>, value: Any): Any {
            return javaClass.getMethod(name, type).invoke(this, value)
                ?: error("$name returned null")
        }

        internal fun Any.callIfPresent(name: String, type: Class<*>, value: Any): Any? {
            return runCatching { javaClass.getMethod(name, type).invoke(this, value) }.getOrNull()
        }
    }
}

/**
 * Holds application context for reflective static factory calls without
 * leaking an Activity. Initialized by Gemma runtimes before first use.
 */
internal object AppContextHolder {
    lateinit var context: Context
}

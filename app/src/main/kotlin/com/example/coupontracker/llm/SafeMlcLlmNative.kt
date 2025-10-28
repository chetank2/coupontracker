package com.example.coupontracker.llm

import android.content.Context
import android.util.Log

/**
 * Safe wrapper around MlcLlmNative that prevents silent fallbacks.
 * Missing native libraries now surface as hard initialization failures.
 */
class SafeMlcLlmNative(context: Context) {

    companion object {
        private const val TAG = "SafeMlcLlmNative"
    }

    private val appContext = context.applicationContext ?: context
    private val nativeInterface = MlcLlmNative()

    init {
        if (!MlcLlmNative.loadLibrary(appContext)) {
            throw IllegalStateException("Native LLM library unavailable")
        }
        Log.i(TAG, "Native LLM interface initialized")
    }

    fun initializeModel(modelPath: String, configPath: String): Long {
        return try {
            nativeInterface.initializeModel(modelPath, configPath)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native initializeModel() unavailable", error)
        }
    }

    fun runTextInference(
        modelHandle: Long,
        ocrText: String,
        prompt: String
    ): String {
        return try {
            nativeInterface.runTextInference(modelHandle, ocrText, prompt)
                ?: throw IllegalStateException("Native text inference returned null response")
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native runTextInference() unavailable", error)
        }
    }
    
    fun runVisionInference(
        modelHandle: Long,
        imageData: ByteArray,
        width: Int,
        height: Int,
        prompt: String
    ): String {
        return try {
            nativeInterface.runVisionInference(modelHandle, imageData, width, height, prompt)
                ?: throw IllegalStateException("Native vision inference returned null response")
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native runVisionInference() unavailable", error)
        }
    }

    fun getModelInfo(modelHandle: Long): String {
        return try {
            nativeInterface.getModelInfo(modelHandle)
                ?: throw IllegalStateException("Native getModelInfo() returned null")
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native getModelInfo() unavailable", error)
        }
    }

    fun getMemoryUsage(modelHandle: Long): Long {
        return try {
            nativeInterface.getMemoryUsage(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native getMemoryUsage() unavailable", error)
        }
    }

    fun warmupModel(modelHandle: Long): Boolean {
        return try {
            nativeInterface.warmupModel(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native warmupModel() unavailable", error)
        }
    }

    fun releaseModel(modelHandle: Long) {
        try {
            nativeInterface.releaseModel(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native releaseModel() unavailable", error)
        }
    }

    fun setInferenceParams(
        modelHandle: Long,
        temperature: Float,
        maxTokens: Int,
        topP: Float
    ): Boolean {
        return try {
            nativeInterface.setInferenceParams(modelHandle, temperature, maxTokens, topP)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native setInferenceParams() unavailable", error)
        }
    }

    fun cancelInference(modelHandle: Long) {
        try {
            nativeInterface.cancelInference(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native cancelInference() unavailable", error)
        }
    }

    fun isInferenceRunning(modelHandle: Long): Boolean {
        return try {
            nativeInterface.isInferenceRunning(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native isInferenceRunning() unavailable", error)
        }
    }

    fun getInferenceProgress(modelHandle: Long): Float {
        return try {
            nativeInterface.getInferenceProgress(modelHandle)
        } catch (error: UnsatisfiedLinkError) {
            throw IllegalStateException("Native getInferenceProgress() unavailable", error)
        }
    }
}

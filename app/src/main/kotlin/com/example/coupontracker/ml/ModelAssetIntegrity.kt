package com.example.coupontracker.ml

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * Utility to ensure that bundled model assets are the expected size before loading.
 * Prevents placeholder binaries from being silently used in production builds.
 */
object ModelAssetIntegrity {
    private const val TAG = "ModelAssetIntegrity"

    fun ensureAssetMinSize(context: Context, assetPath: String, minBytes: Long, name: String) {
        val assetManager = context.assets
        val actualSize = runCatching { determineAssetSize(assetManager, assetPath) }
            .getOrElse { error ->
                Log.e(TAG, "Failed to inspect asset '$assetPath'", error)
                throw IllegalStateException("Model '$name' missing. Ship the real artifact.", error)
            }

        require(actualSize >= minBytes) {
            "Model '$name' too small ($actualSize B). Ship the real artifact."
        }
    }

    private fun determineAssetSize(assetManager: AssetManager, assetPath: String): Long {
        assetManager.openFd(assetPath).use { descriptor ->
            if (descriptor.length >= 0) {
                return descriptor.length
            }
        }

        assetManager.open(assetPath).use { inputStream ->
            return inputStream.available().toLong()
        }
    }
}

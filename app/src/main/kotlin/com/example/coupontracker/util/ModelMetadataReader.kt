package com.example.coupontracker.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Utility class to read model metadata from assets
 */
@Singleton
class ModelMetadataReader @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ModelMetadataReader"
        private const val MODEL_METADATA_FILE = "model_metadata.json"
        private const val COUPON_MODEL_CONFIG_FILE = "models/india_coupon_model_config.json"
    }

    /**
     * Get the model version from metadata
     * @return Pair of model version and number of patterns
     */
    fun getModelVersion(): Pair<String, Int> {
        try {
            // Try to read from model_metadata.json first
            val metadataJson = readAssetFile(MODEL_METADATA_FILE)
            if (metadataJson != null) {
                val metadata = JSONObject(metadataJson)
                val modelVersion = metadata.optString("model_version", "Unknown")
                val numPatterns = metadata.optInt("num_patterns", 0)
                return Pair(modelVersion, numPatterns)
            }

            // If that fails, try to read from the model config file
            val configJson = readAssetFile(COUPON_MODEL_CONFIG_FILE)
            if (configJson != null) {
                val config = JSONObject(configJson)
                val modelVersion = config.optString("version", "Unknown")
                return Pair(modelVersion, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading model metadata", e)
        }

        // Default fallback
        return Pair("Unknown", 0)
    }

    /**
     * Read a file from assets as a string
     */
    private fun readAssetFile(filePath: String): String? {
        return try {
            context.assets.open(filePath).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.d(TAG, "File not found in assets: $filePath")
            null
        }
    }
}

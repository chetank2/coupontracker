package com.example.coupontracker.util

import android.content.Context
import android.util.Log
import org.json.JSONObject
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
    }

    private val modelManager by lazy { ModelManager.getInstance(context) }

    /**
     * Get the model version from the active bundle along with the number of pattern entries.
     */
    fun getModelVersion(): Pair<String, Int> {
        return try {
            val bundle = modelManager.active()
            val version = readVersionFromMetadata(bundle) ?: bundle.version.raw
            val numPatterns = countPatternEntries(bundle)
            Pair(version, numPatterns)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading model metadata", e)
            Pair("Unknown", 0)
        }
    }

    private fun readVersionFromMetadata(bundle: ModelManager.ModelBundle): String? {
        return try {
            modelManager.openFile(bundle, ModelFile.METADATA).bufferedReader().use { reader ->
                val json = reader.readText()
                val metadata = JSONObject(json)
                metadata.optString("model_version", "").takeIf { it.isNotBlank() }
            }
        } catch (ioe: Exception) {
            Log.d(TAG, "Metadata file missing or unreadable for bundle ${bundle.name}")
            null
        }
    }

    private fun countPatternEntries(bundle: ModelManager.ModelBundle): Int {
        return try {
            modelManager.openFile(bundle, ModelFile.PATTERNS).bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { line -> line.isNotBlank() && !line.trim().startsWith("#") }
                    .count()
                    .toInt()
            }
        } catch (ioe: Exception) {
            Log.d(TAG, "Pattern file missing for bundle ${bundle.name}")
            0
        }
    }
}

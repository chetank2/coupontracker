package com.example.coupontracker.llm

import android.content.Context
import android.util.Log
import com.example.coupontracker.model.ModelPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown when the expected model assets cannot be located or fail validation.
 */
class MissingModelAssetsException(message: String) : IllegalStateException(message)

/**
 * Descriptor for a single file that should live inside a model directory.
 */
data class ModelAssetDescriptor(
    val relativePath: String,
    val description: String,
    val minSizeBytes: Long,
    val expectedSha256: String? = null,
    val optional: Boolean = false
)

/**
 * Result emitted after verifying a model directory.
 */
data class ModelAssetVerificationResult(
    val modelId: String,
    val directory: File,
    val checksums: Map<String, String>
)

@Singleton
class ModelAssetManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelAssetManager"

        private val LEGACY_MINICPM_LAYOUT = listOf(
            ModelAssetDescriptor(
                relativePath = "minicpm_llm_q4f16_1.so",
                description = "MiniCPM native library",
                minSizeBytes = 4_000_000L,
                expectedSha256 = "65d9139e97c5a196b48ae08facc468bcc41fef82ef1325ecab2c32e85e1fbbde"
            ),
            ModelAssetDescriptor(
                relativePath = "model.bin",
                description = "MiniCPM model weights",
                minSizeBytes = 3_500_000L,
                expectedSha256 = "94d7d225fbf28a20ec30534207ec1a0ea017a20cf25674cde166a6d4f0c7bad1"
            ),
            ModelAssetDescriptor(
                relativePath = "vision_config.json",
                description = "MiniCPM vision configuration",
                minSizeBytes = 512L,
                expectedSha256 = "a1e7efdfb761c86a3b1a323b3e859eb61718babb036ce66574d75528c33ebb6c"
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.CONFIG_FILE,
                description = "MLC runtime configuration",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.CONFIG_FILE),
                expectedSha256 = "c039de2a0c0ec44016207af64a896f7cd3b6940962709c3e49c9321d6c666ff6"
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.TOKENIZER_MODEL,
                description = "Tokenizer model",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.TOKENIZER_MODEL),
                expectedSha256 = "fd635c2e01878a509339a2d4a269c3600531d0e2c8757b553ab4dee59a215869"
            ),
            ModelAssetDescriptor(
                relativePath = ".verified",
                description = "Verification sentinel",
                minSizeBytes = 1L
            )
        )
    }

    private val defaultLayouts: Map<String, List<ModelAssetDescriptor>> = mapOf(
        ModelPaths.MODEL_ID_QWEN25 to listOf(
            ModelAssetDescriptor(
                relativePath = ModelPaths.QWEN25_MODEL_FILE,
                description = "Qwen2.5 GGUF weights",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN25, ModelPaths.QWEN25_MODEL_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.CONFIG_FILE,
                description = "MLC runtime configuration",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN25, ModelPaths.CONFIG_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.TOKENIZER_JSON,
                description = "Tokenizer vocabulary",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN25, ModelPaths.TOKENIZER_JSON)
            ),
            ModelAssetDescriptor(
                relativePath = "coupon_schema.gbnf",
                description = "JSON grammar",
                minSizeBytes = 256L,
                optional = true
            ),
            ModelAssetDescriptor(
                relativePath = ".verified",
                description = "Verification sentinel",
                minSizeBytes = 1L
            )
        ),
        ModelPaths.MODEL_ID_QWEN2 to listOf(
            ModelAssetDescriptor(
                relativePath = ModelPaths.QWEN2_MODEL_FILE,
                description = "Qwen2 GGUF weights",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN2, ModelPaths.QWEN2_MODEL_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.CONFIG_FILE,
                description = "MLC runtime configuration",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN2, ModelPaths.CONFIG_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.TOKENIZER_JSON,
                description = "Tokenizer vocabulary",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_QWEN2, ModelPaths.TOKENIZER_JSON)
            ),
            ModelAssetDescriptor(
                relativePath = ".verified",
                description = "Verification sentinel",
                minSizeBytes = 1L
            )
        ),
        ModelPaths.MODEL_ID_MINICPM to listOf(
            ModelAssetDescriptor(
                relativePath = ModelPaths.MINICPM_MODEL_FILE,
                description = "MiniCPM GGUF weights",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.MINICPM_MODEL_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.MINICPM_MMPROJ_FILE,
                description = "MiniCPM vision projector",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.MINICPM_MMPROJ_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.CONFIG_FILE,
                description = "MLC runtime configuration",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.CONFIG_FILE)
            ),
            ModelAssetDescriptor(
                relativePath = ModelPaths.TOKENIZER_MODEL,
                description = "Tokenizer model",
                minSizeBytes = ModelPaths.getMinFileSize(ModelPaths.MODEL_ID_MINICPM, ModelPaths.TOKENIZER_MODEL)
            ),
            ModelAssetDescriptor(
                relativePath = ".verified",
                description = "Verification sentinel",
                minSizeBytes = 1L
            )
        )
    )

    fun getExpectedAssets(modelId: String, useLegacyMiniLayout: Boolean = false): List<ModelAssetDescriptor> {
        return if (useLegacyMiniLayout && modelId == ModelPaths.MODEL_ID_MINICPM) {
            LEGACY_MINICPM_LAYOUT
        } else {
            defaultLayouts[modelId] ?: emptyList()
        }
    }

    fun verifyModelAssets(
        modelId: String = ModelPaths.DEFAULT_MODEL_ID,
        directory: File = ModelPaths.modelDir(context, modelId),
        expectedChecksums: Map<String, String>? = null,
        useLegacyMiniLayout: Boolean = false
    ): ModelAssetVerificationResult {
        val layout = getExpectedAssets(modelId, useLegacyMiniLayout)
        if (layout.isEmpty()) {
            throw MissingModelAssetsException("No asset layout defined for modelId=$modelId")
        }

        if (!directory.exists() || !directory.isDirectory) {
            throw MissingModelAssetsException(
                "Model directory missing for $modelId: ${directory.absolutePath}"
            )
        }

        val checksums = mutableMapOf<String, String>()
        layout.forEach { descriptor ->
            val assetFile = File(directory, descriptor.relativePath)

            if (!assetFile.exists()) {
                if (descriptor.optional) {
                    Log.d(TAG, "Optional asset missing for $modelId: ${descriptor.relativePath}")
                    return@forEach
                }
                throw MissingModelAssetsException(
                    "Missing ${descriptor.description} (${descriptor.relativePath}) for $modelId"
                )
            }

            val length = assetFile.length()
            if (length < descriptor.minSizeBytes) {
                throw MissingModelAssetsException(
                    "${descriptor.description} (${descriptor.relativePath}) is too small: " +
                        "${formatBytes(length)} < ${formatBytes(descriptor.minSizeBytes)}"
                )
            }

            val sha256 = calculateSha256(assetFile)
            checksums[descriptor.relativePath] = sha256

            val expected = descriptor.expectedSha256 ?: expectedChecksums?.get(descriptor.relativePath)
            if (expected != null && !sha256.equals(expected, ignoreCase = true)) {
                throw MissingModelAssetsException(
                    "Checksum mismatch for ${descriptor.relativePath}: expected $expected but found $sha256"
                )
            }
        }

        return ModelAssetVerificationResult(
            modelId = modelId,
            directory = directory,
            checksums = checksums
        )
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

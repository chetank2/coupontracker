package com.example.coupontracker.llm

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object GrammarAssetSynchronizer {

    private const val TAG = "GrammarAssetSync"
    private const val GRAMMAR_FILE_NAME = "coupon_schema.gbnf"
    private const val SENTINEL_FILE_NAME = "coupon_schema.gbnf.sha256"

    data class Result(
        val copied: Boolean,
        val hash: String?,
        val message: String? = null
    )

    fun sync(context: Context, modelDir: File): Result {
        return runCatching {
            val assetBytes = context.assets.open(GRAMMAR_FILE_NAME).use { input ->
                input.readBytes()
            }
            val assetHash = digest(assetBytes)

            val grammarFile = File(modelDir, GRAMMAR_FILE_NAME)
            val sentinelFile = File(modelDir, SENTINEL_FILE_NAME)

            val sentinelHash = sentinelFile.takeIf { it.exists() }
                ?.readText()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val fileHash = grammarFile.takeIf { it.exists() }
                ?.let { existing ->
                    runCatching { digest(existing) }.getOrNull()
                }

            val needsCopy = when {
                !grammarFile.exists() -> {
                    Log.d(TAG, "Grammar file missing – will deploy new copy")
                    true
                }
                sentinelHash == null -> {
                    Log.d(TAG, "Grammar sentinel missing – refreshing grammar asset")
                    true
                }
                !assetHash.equals(sentinelHash, ignoreCase = true) -> {
                    Log.d(TAG, "Grammar hash mismatch (asset=$assetHash, sentinel=$sentinelHash) – refreshing")
                    true
                }
                fileHash == null -> {
                    Log.d(TAG, "Failed to read existing grammar hash – refreshing")
                    true
                }
                !assetHash.equals(fileHash, ignoreCase = true) -> {
                    Log.d(TAG, "Grammar file content drift detected – refreshing")
                    true
                }
                else -> false
            }

            if (!needsCopy) {
                Log.d(TAG, "Grammar asset already up to date (sha256=$assetHash)")
                return Result(copied = false, hash = assetHash)
            }

            grammarFile.parentFile?.mkdirs()
            grammarFile.outputStream().use { output ->
                output.write(assetBytes)
            }
            sentinelFile.writeText(assetHash)

            Log.d(TAG, "Deployed grammar asset to ${grammarFile.absolutePath} (${assetBytes.size} bytes, sha256=$assetHash)")

            Result(copied = true, hash = assetHash)
        }.getOrElse { error ->
            Log.e(TAG, "Failed to synchronize grammar asset", error)
            Result(copied = false, hash = null, message = error.message)
        }
    }

    @Throws(IOException::class)
    private fun digest(file: File): String {
        return file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
        }
    }

    private fun digest(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte)
        }
    }
}


package com.example.coupontracker.ocr

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs tessdata assets into the app's writable directory while
 * validating version sentinels and checksums.
 */
class TessDataInstaller(
    private val assetOpener: () -> InputStream,
    private val tessDataDir: File,
    private val trainedDataFilename: String,
    private val sentinelFilename: String,
    private val expectedChecksum: String,
    private val expectedVersion: String,
    private val logger: Logger = Logger()
) {

    data class Result(
        val file: File,
        val assetChecksum: String,
        val installedChecksum: String,
        val copied: Boolean
    )

    data class Logger(
        val debug: (String) -> Unit = {},
        val info: (String) -> Unit = {},
        val warn: (String) -> Unit = {},
        val error: (String, Throwable?) -> Unit = { _, _ -> }
    )

    fun installIfNeeded(): Result {
        tessDataDir.mkdirs()
        val dataFile = File(tessDataDir, trainedDataFilename)
        val sentinelFile = File(tessDataDir, sentinelFilename)

        logger.debug("Ensuring tessdata at ${dataFile.absolutePath}")

        val assetChecksum = assetOpener().use { input ->
            computeSha256(input)
        }
        if (!assetChecksum.equals(expectedChecksum, ignoreCase = true)) {
            logger.error(
                "eng.traineddata checksum mismatch. Expected $expectedChecksum but asset is $assetChecksum",
                null
            )
            throw IllegalStateException("Bundled eng.traineddata does not match expected checksum")
        }

        val sentinelPayload = "$expectedVersion|$assetChecksum"
        val sentinelMatches = if (sentinelFile.exists()) {
            sentinelFile.readText().trim() == sentinelPayload
        } else {
            false
        }

        val installedChecksum = if (dataFile.exists()) {
            dataFile.inputStream().use { input -> computeSha256(input) }
        } else {
            null
        }

        val needsCopy = !sentinelMatches || installedChecksum == null ||
            !installedChecksum.equals(assetChecksum, ignoreCase = true)

        if (needsCopy) {
            logger.info("Refreshing tessdata asset ($trainedDataFilename) → ${dataFile.absolutePath}")
            assetOpener().use { input ->
                FileOutputStream(dataFile).use { output ->
                    input.copyTo(output)
                }
            }
            sentinelFile.writeText(sentinelPayload)
        } else {
            logger.debug("Tessdata asset is up-to-date (checksum $assetChecksum)")
        }

        val finalChecksum = dataFile.inputStream().use { input -> computeSha256(input) }
        if (!finalChecksum.equals(assetChecksum, ignoreCase = true)) {
            val message = "Installed eng.traineddata checksum $finalChecksum does not match asset $assetChecksum"
            logger.error(message, null)
            throw IllegalStateException(message)
        }

        return Result(
            file = dataFile,
            assetChecksum = assetChecksum,
            installedChecksum = finalChecksum,
            copied = needsCopy
        )
    }

    companion object {
        @JvmStatic
        fun computeSha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (true) {
                read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte)
            }
        }

        @JvmStatic
        fun computeSha256(bytes: ByteArray): String {
            return computeSha256(bytes.inputStream())
        }
    }
}

package com.example.coupontracker.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ModelDownloadManagerTest {

    private lateinit var context: Context
    private lateinit var securePreferencesManager: SecurePreferencesManager
    private lateinit var modelDownloadManager: ModelDownloadManager

    private lateinit var originalRequiredFiles: Map<String, String>
    private lateinit var goodFixtureChecksums: Map<String, String>

    private val fixtureFileNames = listOf(
        "minicpm_llm_q4f16_1.so",
        "mlc-chat-config.json",
        "tokenizer.json",
        "params_shard_0.bin",
        "params_shard_1.bin",
        "ndarray-cache.json",
        "vocab.txt"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        securePreferencesManager = SecurePreferencesManager(context)

        originalRequiredFiles = readRequiredFiles()
        goodFixtureChecksums = loadFixtureChecksums("good")
        overrideRequiredFiles(goodFixtureChecksums)

        modelDownloadManager = ModelDownloadManager(context)

        securePreferencesManager.setLlmModelDownloaded(true)
        securePreferencesManager.setLlmModelVersion("test-fixture")
    }

    @After
    fun tearDown() {
        overrideRequiredFiles(originalRequiredFiles)
        securePreferencesManager.setLlmModelDownloaded(false)
        securePreferencesManager.setLlmModelVersion("")
        securePreferencesManager.setLlmModelSizeMB(0f)
        securePreferencesManager.setLlmModelChecksum("")
        getModelDir().deleteRecursively()
    }

    @Test
    fun refreshModelStatus_withValidFixture_marksFilesPresent() = runTest {
        copyFixtureFiles("good")

        val status = modelDownloadManager.refreshModelStatus(force = true)

        kotlin.test.assertTrue(status.filesPresent, "Expected valid MiniCPM fixture to be accepted")
    }

    @Test
    fun refreshModelStatus_withTamperedFixture_detectsChecksumMismatch() = runTest {
        copyFixtureFiles("tampered")

        val status = modelDownloadManager.refreshModelStatus(force = true)

        kotlin.test.assertFalse(status.filesPresent, "Expected tampered MiniCPM fixture to be rejected")
    }

    private fun copyFixtureFiles(fixtureName: String) {
        val modelDir = getModelDir()
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        val classLoader = javaClass.classLoader ?: error("Missing class loader")

        fixtureFileNames.forEach { fileName ->
            val resourcePath = "models/minicpm/$fixtureName/$fileName"
            val targetFile = File(modelDir, fileName)

            classLoader.getResourceAsStream(resourcePath)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Fixture resource not found: $resourcePath")
        }

        val totalBytes = modelDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        securePreferencesManager.setLlmModelSizeMB(totalBytes / (1024f * 1024f))
        securePreferencesManager.setLlmModelDownloaded(true)
        securePreferencesManager.setLlmModelChecksum(calculateAggregateChecksum(modelDir))
    }

    private fun loadFixtureChecksums(fixtureName: String): Map<String, String> {
        val classLoader = javaClass.classLoader ?: error("Missing class loader")
        return fixtureFileNames.associateWith { fileName ->
            val resourcePath = "models/minicpm/$fixtureName/$fileName"
            classLoader.getResourceAsStream(resourcePath)?.use { input ->
                calculateChecksum(input)
            } ?: error("Fixture resource not found: $resourcePath")
        }
    }

    private fun calculateAggregateChecksum(modelDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        modelDir.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.name }
            .forEach { file ->
                val checksum = file.inputStream().use { input ->
                    calculateChecksum(input)
                }
                digest.update(checksum.toByteArray())
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun calculateChecksum(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = inputStream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readRequiredFiles(): Map<String, String> {
        val companion = getCompanionInstance()
        val field = companion.javaClass.getDeclaredField("REQUIRED_FILES")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(companion) as Map<String, String>).toMap()
    }

    private fun overrideRequiredFiles(newValue: Map<String, String>) {
        val companion = getCompanionInstance()
        val field = companion.javaClass.getDeclaredField("REQUIRED_FILES")
        field.isAccessible = true

        removeFinalModifier(field)
        field.set(companion, newValue)
    }

    private fun getCompanionInstance(): Any {
        val companionField = ModelDownloadManager::class.java.getDeclaredField("Companion")
        companionField.isAccessible = true
        return companionField.get(null)
    }

    private fun removeFinalModifier(field: Field) {
        val modifiersField = Field::class.java.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
    }

    private fun getModelDir(): File = File(context.filesDir, "models")
}

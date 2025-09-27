package com.example.coupontracker.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.util.SecurePreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import io.mockk.Runs
import io.mockk.anyConstructed
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ModelDownloadManagerTest {

    private lateinit var context: Context
    private lateinit var securePreferencesManager: SecurePreferencesManager
    private lateinit var modelDownloadManager: ModelDownloadManager

    private val fixtureFilePaths = listOf(
        "minicpm_llm_q4f16_1.so",
        "mlc-chat-config.json",
        "tokenizer/tokenizer.json",
        "params_shard_0.bin",
        "params_shard_1.bin",
        "ndarray-cache.json",
        "vocab.txt"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        securePreferencesManager = SecurePreferencesManager(context)

        modelDownloadManager = ModelDownloadManager(context)

        getModelDir().deleteRecursively()

        securePreferencesManager.setLlmModelDownloaded(true)
        securePreferencesManager.setLlmModelVersion("test-fixture")
    }

    @After
    fun tearDown() {
        securePreferencesManager.setLlmModelDownloaded(false)
        securePreferencesManager.setLlmModelVersion("")
        securePreferencesManager.setLlmModelSizeMB(0f)
        securePreferencesManager.setLlmModelChecksum("")
        securePreferencesManager.setLlmModelBaseUrlOverride(null)
        getModelDir().deleteRecursively()
        runCatching { unmockkConstructor(URL::class) }
    }

    @Test
    fun verifyModelFiles_withValidFixture_allowsInstall() = runTest {
        copyFixtureFiles("good")

        val verified = invokeVerifyModelFiles()
        kotlin.test.assertTrue(verified, "Expected verifyModelFiles to accept valid MiniCPM fixture")

        val status = modelDownloadManager.refreshModelStatus(force = true)

        kotlin.test.assertTrue(status.filesPresent, "Expected valid MiniCPM fixture to be accepted")
    }

    @Test
    fun verifyModelFiles_withTamperedFixture_detectsChecksumMismatch() = runTest {
        copyFixtureFiles("tampered")

        val verified = invokeVerifyModelFiles()
        kotlin.test.assertFalse(verified, "Expected verifyModelFiles to reject tampered MiniCPM fixture")

        val status = modelDownloadManager.refreshModelStatus(force = true)

        kotlin.test.assertFalse(status.filesPresent, "Expected tampered MiniCPM fixture to be rejected")
    }

    @Test
    fun refreshModelStatus_whenTokenizerMissing_marksModelIncomplete() = runTest {
        copyFixtureFiles("good")

        val tokenizerFile = File(getModelDir(), "tokenizer/tokenizer.json")
        kotlin.test.assertTrue(tokenizerFile.delete(), "Expected tokenizer.json to be deleted for test")

        val verified = invokeVerifyModelFiles()
        kotlin.test.assertFalse(verified, "Expected verification to fail when tokenizer.json is missing")

        val status = modelDownloadManager.refreshModelStatus(force = true)
        kotlin.test.assertFalse(status.filesPresent, "Expected status to report missing tokenizer.json")
    }

    @Test
    fun downloadFile_whenHttpError_returnsDetailedFailure() = runTest {
        mockkConstructor(URL::class)
        val connection = mockk<HttpURLConnection>()

        every { anyConstructed<URL>().openConnection() } returns connection
        every { connection.connectTimeout = any() } just Runs
        every { connection.readTimeout = any() } just Runs
        every { connection.connect() } just Runs
        every { connection.responseCode } returns 503
        every { connection.responseMessage } returns "Service Unavailable"
        every { connection.disconnect() } just Runs

        val tempFile = File.createTempFile("download-test", ".zip")
        tempFile.deleteOnExit()

        try {
            val result = modelDownloadManager.downloadFile(
                url = "https://example.com/model.zip",
                outputFile = tempFile,
                progressCallback = { }
            )

            kotlin.test.assertTrue(result.isFailure, "Expected download to fail for non-200 response")
            val message = result.exceptionOrNull()?.message ?: ""
            kotlin.test.assertTrue(message.contains("503"), "Expected error message to include status code, but was: $message")
            kotlin.test.assertTrue(message.contains("Service Unavailable"), "Expected error message to include response message, but was: $message")
        } finally {
            tempFile.delete()
            unmockkConstructor(URL::class)
        }
    }

    @Test
    fun downloadModel_withValidDistribution_succeedsAndExtracts() = runTest {
        simulateWifiConnection()
        securePreferencesManager.setLlmDownloadWifiOnly(true)

        val server = MockWebServer()
        val zipFile = locateDistributionZip()
        val buffer = Buffer().write(zipFile.readBytes())

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", zipFile.length())
                .setBody(buffer)
        )

        server.start()

        try {
            val overrideBaseUrl = server.url("/android/minicpm").toString().trimEnd('/')
            securePreferencesManager.setLlmModelBaseUrlOverride(overrideBaseUrl)

            val result = modelDownloadManager.downloadModel { }

            kotlin.test.assertTrue(result is DownloadResult.Success, "Expected download to succeed: $result")

            val modelDir = getModelDir()
            val expectedFiles = listOf(
                "model.bin",
                "vision_config.json",
                "minicpm_llm_q4f16_1.so",
                "mlc-chat-config.json",
                "tokenizer.model",
                "tokenizer.json"
            )

            expectedFiles.forEach { fileName ->
                kotlin.test.assertTrue(
                    File(modelDir, fileName).exists(),
                    "Expected $fileName to be extracted"
                )
            }
        } finally {
            securePreferencesManager.setLlmModelBaseUrlOverride(null)
            server.shutdown()
        }
    }

    private fun simulateWifiConnection() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowConnectivity = shadowOf(connectivityManager)
        val network = Network(0)
        shadowConnectivity.setActiveNetwork(network)

        val capabilities = NetworkCapabilities.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        shadowConnectivity.setNetworkCapabilities(network, capabilities)
    }

    private fun locateDistributionZip(): File {
        val candidates = listOf(
            File(System.getProperty("user.dir"), "android_models/minicpm_llama3_v25_android.zip"),
            File("android_models/minicpm_llama3_v25_android.zip"),
            File("../android_models/minicpm_llama3_v25_android.zip")
        )

        return candidates.firstOrNull { it.exists() }
            ?: error("Unable to locate MiniCPM distribution zip")
    }

    private fun copyFixtureFiles(fixtureName: String) {
        val modelDir = getModelDir()
        if (modelDir.exists()) {
            modelDir.deleteRecursively()
        }
        modelDir.mkdirs()

        val classLoader = javaClass.classLoader ?: error("Missing class loader")

        fixtureFilePaths.forEach { relativePath ->
            val resourcePath = "models/minicpm/$fixtureName/$relativePath"
            val targetFile = File(modelDir, relativePath)

            targetFile.parentFile?.mkdirs()

            classLoader.getResourceAsStream(resourcePath)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: error("Fixture resource not found: $resourcePath")
        }

        val baseModelDir = listOf(
            File(System.getProperty("user.dir"), "android_models/mlc_model"),
            File("android_models/mlc_model"),
            File("../android_models/mlc_model")
        ).firstOrNull { it.exists() }
            ?: error("Unable to locate android_models/mlc_model fixture directory")
        val baseModelFiles = listOf(
            "model.bin",
            "vision_config.json",
            "tokenizer.model"
        )

        baseModelFiles.forEach { fileName ->
            val sourceFile = File(baseModelDir, fileName)
            require(sourceFile.exists()) { "Required base model fixture missing: ${sourceFile.path}" }

            val targetFile = File(modelDir, fileName)
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = true)
        }

        val totalBytes = modelDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        securePreferencesManager.setLlmModelSizeMB(totalBytes / (1024f * 1024f))
        securePreferencesManager.setLlmModelDownloaded(true)
        securePreferencesManager.setLlmModelChecksum(calculateAggregateChecksum(modelDir))
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

    private fun invokeVerifyModelFiles(): Boolean {
        val method = ModelDownloadManager::class.java.getDeclaredMethod("verifyModelFiles")
        method.isAccessible = true
        return method.invoke(modelDownloadManager) as Boolean
    }

    private fun getModelDir(): File = File(context.filesDir, "models")
}

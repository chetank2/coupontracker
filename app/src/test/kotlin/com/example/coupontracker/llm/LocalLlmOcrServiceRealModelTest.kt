package com.example.coupontracker.llm

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.util.ExtractResult
import com.example.coupontracker.util.ExtractionStage
import com.example.coupontracker.util.LocalLlmOcrService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LocalLlmOcrServiceRealModelTest {

    private lateinit var context: Context
    private lateinit var modelDir: File
    private lateinit var defaultLoader: MlcLlmNative.NativeLibraryLoader
    private val loadedPaths = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        modelDir = File(context.filesDir, "models").apply {
            deleteRecursively()
            mkdirs()
        }

        copyResourceToModelDir("models/minicpm/good/minicpm_llm_q4f16_1.so", "minicpm_llm_q4f16_1.so")
        File(modelDir, "mlc-chat-config.json").writeText("{}")
        File(modelDir, "model.bin").writeBytes(ByteArray(16))
        File(modelDir, "vision_config.json").writeText("{}")
        File(modelDir, "tokenizer.model").writeText("mock tokenizer")

        loadedPaths.clear()
        defaultLoader = MlcLlmNative.libraryLoader
        MlcLlmNative.resetForTests()
        MlcLlmNative.libraryLoader = object : MlcLlmNative.NativeLibraryLoader {
            override fun loadLibrary(name: String) {
                throw UnsatisfiedLinkError("Packaged library missing in test: $name")
            }

            override fun load(path: String) {
                loadedPaths.add(path)
            }
        }
    }

    @After
    fun tearDown() {
        MlcLlmNative.libraryLoader = defaultLoader
        MlcLlmNative.resetForTests()
    }

    @Test
    fun `processCouponImageTyped returns good result without fallback`() = runTest {
        val loadSuccess = MlcLlmNative.loadLibrary(context)
        assertTrue(loadSuccess)
        assertTrue(loadedPaths.isNotEmpty())
        assertEquals(File(modelDir, "minicpm_llm_q4f16_1.so").absolutePath, loadedPaths.first())

        val llmRuntime = mockk<LlmRuntimeManager>()
        every { llmRuntime.isModelAvailable() } returns true
        every { llmRuntime.getModelInfo() } returns ModelInfo(
            name = "MiniCPM-Llama3-V2.5",
            version = "v2.5-q4-android",
            isAvailable = true,
            isLoaded = true,
            sizeBytes = 4_700_000L,
            sizeMB = 4.7f,
            referenceCount = 1
        )
        every { llmRuntime.getMemoryStats() } returns MemoryStats(
            totalMemoryMB = 1024,
            freeMemoryMB = 512,
            maxMemoryMB = 2048,
            modelLoadedMemoryMB = 256
        )
        coEvery { llmRuntime.runInference(any(), any()) } returns FIXTURE_RESPONSE

        val telemetry = mockk<LlmTelemetryService>(relaxed = true)

        val service = LocalLlmOcrService(
            context,
            llmRuntime,
            telemetry
        ) { "Enjoy flat 20% off on orders above 1000 with code PRIME20" }

        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

        val result = service.processCouponImageTyped(bitmap)

        assertTrue(result is ExtractResult.Good)
        val good = result as ExtractResult.Good
        assertEquals(ExtractionStage.LLM, good.signals.stage)
        assertTrue(good.signals.nativeAvailable)
        assertEquals("Prime Store", good.info.storeName)
        assertEquals("PRIME20", good.info.redeemCode)

        coVerify(exactly = 1) { llmRuntime.runInference(any(), any()) }
        verify(exactly = 1) {
            telemetry.recordInference(
                durationMs = any(),
                success = true,
                errorType = null,
                fallbackUsed = null,
                extractedFieldCount = any(),
                memoryUsageMB = any()
            )
        }
        verify(exactly = 0) {
            telemetry.recordInference(
                durationMs = any(),
                success = false,
                errorType = any(),
                fallbackUsed = any(),
                extractedFieldCount = any(),
                memoryUsageMB = any()
            )
        }
    }

    private fun copyResourceToModelDir(resource: String, fileName: String) {
        val inputStream = requireNotNull(javaClass.classLoader?.getResourceAsStream(resource)) {
            "Missing test resource: $resource"
        }
        val target = File(modelDir, fileName)
        inputStream.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private val FIXTURE_RESPONSE = """
        {
            "storeName": "Prime Store",
            "description": "Flat 20% off on select products",
            "redeemCode": "SAVE20",
            "cashback": {
                "type": "percent",
                "valueNum": 20,
                "currency": "INR"
            },
            "expiryDate": "31 May 2025",
            "minOrderAmount": "₹1000"
        }
        """.trimIndent()
    }
}

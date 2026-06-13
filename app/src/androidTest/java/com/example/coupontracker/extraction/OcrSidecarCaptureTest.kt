package com.example.coupontracker.extraction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.ocr.OcrEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OcrSidecarCaptureTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var ocrEngine: OcrEngine

    @Test
    fun canary_samples_write_ocr_sidecars() = runBlocking {
        hiltRule.inject()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val expectedJson = testContext.assets.open("canary_expected.json")
            .bufferedReader()
            .use { it.readText() }
        val expected = JSONObject(expectedJson)
        val sidecars = JSONArray()

        appendSidecars(sidecars, expected.optJSONArray("samples"))
        appendSidecars(sidecars, expected.optJSONArray("failures"))
        appendSidecars(sidecars, expected.optJSONArray("changed"))

        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("sidecars", sidecars)
            .put("total", sidecars.length())
        val reportFile = File(targetContext.getExternalFilesDir(null), "ocr_sidecars.json")
        reportFile.writeText(report.toString(2))

        assertTrue("OCR sidecar report was not written", reportFile.exists())
        assertTrue("Expected at least one OCR sidecar", sidecars.length() > 0)
    }

    private suspend fun appendSidecars(sidecars: JSONArray, samples: JSONArray?) {
        if (samples == null) return
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            sidecars.put(captureSidecar(sample))
        }
    }

    private suspend fun captureSidecar(sample: JSONObject): JSONObject {
        val bitmap = loadBitmap(sample)
        val rawText = ocrEngine.recognize(bitmap)
        val spans = ocrEngine.recognizeWithBoxes(bitmap)
        val tiles = JSONArray()
        spans.forEach { span ->
            val box = span.boundingBox
            tiles.put(
                JSONObject()
                    .put("text", span.text)
                    .put("left", box.left)
                    .put("top", box.top)
                    .put("right", box.right)
                    .put("bottom", box.bottom)
                    .put("confidence", span.confidence.toDouble())
            )
        }
        return JSONObject()
            .put("id", sample.getString("id"))
            .put("image", sample.optString("image"))
            .put("imageSha256", sample.optString("imageSha256"))
            .put("ocr", JSONObject().put("text", rawText).put("tiles", tiles))
    }

    private fun loadBitmap(sample: JSONObject): Bitmap {
        val imagePath = sample.getString("image")
        val assetPath = when {
            imagePath.startsWith("canary/") -> imagePath
            imagePath.startsWith("failures/") -> imagePath
            imagePath.startsWith("changed/") -> imagePath
            else -> "canary/${imagePath.substringAfterLast('/')}"
        }
        val stream = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open(assetPath)
        return BitmapFactory.decodeStream(stream)
            ?: error("Could not decode $assetPath")
    }
}

package com.example.coupontracker.extraction

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.coupontracker.BuildConfig
import com.example.coupontracker.data.model.Coupon
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
class ExtractionSmokeTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var extractionService: MultiCouponExtractionService

    @Test
    fun canary_samples_write_extraction_report() = runBlocking {
        hiltRule.inject()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext
        val expectedJson = testContext.assets.open("canary_expected.json")
            .bufferedReader()
            .use { it.readText() }
        val expected = JSONObject(expectedJson)
        val results = JSONArray()

        appendResults(results, expected.optJSONArray("samples"), "canary")
        appendResults(results, expected.optJSONArray("failures"), "failure")
        appendResults(results, expected.optJSONArray("changed"), "changed")

        val report = JSONObject()
            .put("llmBackend", BuildConfig.LLM_BACKEND)
            .put("buildConfig", JSONObject().put("LLM_BACKEND", BuildConfig.LLM_BACKEND))
            .put("samples", results)
            .put("total", results.length())
        val reportFile = File(targetContext.getExternalFilesDir(null), "extraction_smoke_report.json")
        reportFile.writeText(report.toString(2))

        assertTrue("Report was not written", reportFile.exists())
        assertTrue("Expected at least one smoke sample", results.length() > 0)
    }

    private suspend fun appendResults(results: JSONArray, samples: JSONArray?, source: String) {
        if (samples == null) return
        for (index in 0 until samples.length()) {
            val sample = samples.getJSONObject(index)
            results.put(runSample(sample, source))
        }
    }

    private suspend fun runSample(sample: JSONObject, source: String): JSONObject {
        val started = System.currentTimeMillis()
        val bitmap = loadBitmap(sample)
        val result = runCatching {
            extractionService.extractMultipleCoupons(bitmap)
        }
        val latencyMs = System.currentTimeMillis() - started
        val row = JSONObject()
            .put("id", sample.getString("id"))
            .put("source", source)
            .put("latency_ms", latencyMs)

        result.fold(
            onSuccess = { extraction ->
                row.put("parsed", firstCouponJson(extraction))
                row.put("coupons", couponsJson(extraction.coupons))
                row.put("total_detected", extraction.totalDetected)
                row.put("total_extracted", extraction.totalExtracted)
                row.put("total_filtered", extraction.totalFiltered)
            },
            onFailure = { error ->
                row.put("parsed", JSONObject())
                row.put("error", error.message ?: error::class.java.name)
            }
        )
        return row
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

    private fun firstCouponJson(result: MultiCouponExtractionService.MultiCouponResult): JSONObject {
        val first = result.coupons.firstOrNull()?.coupon ?: return JSONObject()
        return couponJson(first)
    }

    private fun couponsJson(coupons: List<MultiCouponExtractionService.CouponWithConfidence>): JSONArray {
        val array = JSONArray()
        coupons.forEach { couponWithConfidence ->
            array.put(
                couponJson(couponWithConfidence.coupon)
                    .put("confidence", couponWithConfidence.confidence.toDouble())
            )
        }
        return array
    }

    private fun couponJson(coupon: Coupon): JSONObject =
        JSONObject()
            .put("storeName", coupon.storeName)
            .put("description", coupon.description)
            .put("redeemCode", coupon.redeemCode ?: "unknown")
            .put("expiryDate", coupon.expiryDate?.time ?: "unknown")
            .put("storeNameSource", coupon.storeNameSource ?: "unknown")
            .put("storeNameEvidence", JSONArray(coupon.storeNameEvidence))
            .put("needsAttention", coupon.needsAttention)
}

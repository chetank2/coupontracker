package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoldenSetBenchmarkTest {

    @Test
    fun `hermetic replay benchmark reports perfect scores`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue("golden set must not be empty", samples.isNotEmpty())

        val replay = ManifestLoader.replayRecordings(samples)
        val model = ReplayCouponModel(recordings = replay) { _ ->
            sentinelHash.get() ?: error("no sentinel hash set")
        }

        val rows = samples.map { sample ->
            sentinelHash.set(sample.imageSha256)
            val result = model.extractFromImage(FakeBitmap, null, "ignored")
            MetricsCalculator.score(sample, result.canonicalJson, result.latencyMs)
        }

        val agg = MetricsCalculator.aggregate(rows)

        assertEquals("replay JSON must be perfect", 1.0, agg.jsonValidity, 0.0)
        assertEquals(1.0, agg.redeemCodeAccuracy, 0.0)
        assertEquals(1.0, agg.storeNameAccuracy, 0.0)
        assertEquals(1.0, agg.expiryDateAccuracy, 0.0)

        val outDir = File("build/reports/goldenset").apply { mkdirs() }
        File(outDir, "hermetic.md").writeText(renderReport(rows, agg))
    }

    private fun renderReport(rows: List<SampleMetrics>, agg: AggregateMetrics): String = buildString {
        append("# Hermetic golden-set benchmark\n\n")
        append(agg.toMarkdown())
        append("\n## per-sample\n\n")
        append("| id | brand | redeem | store | expiry | valid | latency_ms |\n")
        append("|---|---|---|---|---|---|---|\n")
        rows.forEach {
            append("| %s | %s | %s | %s | %s | %s | %d |\n".format(
                it.id, it.brand,
                if (it.redeemCodeExact) "✔" else "✘",
                if (it.storeNameNormalizedMatch) "✔" else "✘",
                if (it.expiryDateMatch) "✔" else "✘",
                if (it.jsonValid) "✔" else "✘",
                it.latencyMs
            ))
        }
    }

    companion object {
        private val sentinelHash = ThreadLocal<String>()
        private val FakeBitmap: android.graphics.Bitmap = mockk(relaxed = true)
    }
}

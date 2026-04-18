package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoldenSetAbTest {

    @Test
    fun `hermetic A B comparison produces a report`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue(samples.isNotEmpty())

        val qwenRecordings = ManifestLoader.replayRecordings(samples)
        val gemmaRecordings = samples.associate { s ->
            val text = javaClass.classLoader!!.getResource("replay_gemma/${s.id}.json")?.readText()
                ?: error("missing replay_gemma/${s.id}.json")
            s.imageSha256 to text
        }

        val qwen = ReplayCouponModel(qwenRecordings) { sentinel.get()!! }
        val gemma = ReplayCouponModel(gemmaRecordings) { sentinel.get()!! }

        val qwenRows = samples.map { s ->
            sentinel.set(s.imageSha256)
            val r = qwen.extractFromImage(fake, null, "")
            MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
        }
        val gemmaRows = samples.map { s ->
            sentinel.set(s.imageSha256)
            val r = gemma.extractFromImage(fake, null, "")
            MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
        }

        val qwenAgg = MetricsCalculator.aggregate(qwenRows)
        val gemmaAgg = MetricsCalculator.aggregate(gemmaRows)

        val out = File("build/reports/goldenset").apply { mkdirs() }
        File(out, "ab_hermetic.md").writeText(render(qwenAgg, gemmaAgg))
    }

    private fun render(q: AggregateMetrics, g: AggregateMetrics): String = buildString {
        append("# Gemma vs Qwen hermetic A/B\n\n")
        append("| metric | Qwen | Gemma | \u0394 |\n|---|---|---|---|\n")
        row("redeemCode exact", q.redeemCodeAccuracy, g.redeemCodeAccuracy)
        row("storeName normalized", q.storeNameAccuracy, g.storeNameAccuracy)
        row("expiryDate match", q.expiryDateAccuracy, g.expiryDateAccuracy)
        row("JSON validity", q.jsonValidity, g.jsonValidity)
        row("hallucination rate", q.hallucinationRate, g.hallucinationRate)
    }

    private fun StringBuilder.row(name: String, a: Double, b: Double) {
        append("| %s | %.3f | %.3f | %+.3f |\n".format(name, a, b, b - a))
    }

    companion object {
        private val sentinel = ThreadLocal<String>()
        private val fake: android.graphics.Bitmap = mockk(relaxed = true)
    }
}

package com.example.coupontracker.benchmark

import com.example.coupontracker.extraction.model.ReplayCouponModel
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VlmBakeOffTest {

    private val candidates = listOf("qwen", "minicpm", "gemma")

    @Test
    fun `bakeoff generates comparison report across three VLM candidates`() = runBlocking {
        val samples = ManifestLoader.loadAll()
        assertTrue(samples.isNotEmpty())

        val perCandidate = candidates.associateWith { name ->
            val recordings = samples.associate { s ->
                val path = "replay_vlm/$name/${s.id}.json"
                val text = javaClass.classLoader!!.getResource(path)?.readText()
                    ?: error("missing $path")
                s.imageSha256 to text
            }
            val model = ReplayCouponModel(recordings) { sentinel.get()!! }
            val rows = samples.map { s ->
                sentinel.set(s.imageSha256)
                val r = model.extractFromImage(fake, null, "")
                MetricsCalculator.score(s, r.canonicalJson, r.latencyMs)
            }
            MetricsCalculator.aggregate(rows)
        }

        val out = File("build/reports/goldenset").apply { mkdirs() }
        File(out, "vlm_bakeoff.md").writeText(render(perCandidate))
    }

    private fun render(perCandidate: Map<String, AggregateMetrics>): String = buildString {
        append("# VLM bake-off\n\n")
        append("| metric | ").append(candidates.joinToString(" | ")).append(" |\n")
        append("|---|").append(candidates.joinToString("|") { "---" }).append("|\n")
        fun row(name: String, pick: (AggregateMetrics) -> Double) {
            append("| $name |")
            candidates.forEach { append(" %.3f |".format(pick(perCandidate[it]!!))) }
            append("\n")
        }
        row("redeemCode exact") { it.redeemCodeAccuracy }
        row("storeName normalized") { it.storeNameAccuracy }
        row("expiryDate match") { it.expiryDateAccuracy }
        row("JSON validity") { it.jsonValidity }
        row("hallucination rate") { it.hallucinationRate }
    }

    companion object {
        private val sentinel = ThreadLocal<String>()
        private val fake: android.graphics.Bitmap = mockk(relaxed = true)
    }
}

package com.example.coupontracker.benchmark

import org.json.JSONArray

object ManifestLoader {

    fun loadAll(): List<GoldenSetSample> {
        val loader = javaClass.classLoader
            ?: error("no classloader to read benchmark/goldenset/manifest.json")
        val text = loader.getResource("manifest.json")?.readText()
            ?: error("benchmark/goldenset/manifest.json not on test classpath; " +
                     "check sourceSets.test.resources wiring")
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val id = obj.getString("id")
            val replayText = loader.getResource("replay/$id.json")?.readText()
                ?: error("missing replay fixture for $id")
            GoldenSetSample(
                id = id,
                imagePath = obj.getString("image"),
                imageSha256 = obj.getString("imageSha256"),
                brand = obj.getString("brand"),
                expected = obj.getJSONObject("expected"),
                replayJson = replayText
            )
        }
    }

    fun replayRecordings(samples: List<GoldenSetSample>): Map<String, String> =
        samples.associate { it.imageSha256 to it.replayJson }
}

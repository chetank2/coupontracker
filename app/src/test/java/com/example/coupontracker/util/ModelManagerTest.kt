package com.example.coupontracker.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelManagerTest {

    @Test
    fun `valid bundle loads successfully`() {
        val assets = buildBundleAssets("active", validChecksums = true)
        val source = FakeAssetSource(assets)
        assertEquals(listOf("active"), source.list("models"))
        val manager = ModelManager.createForTest(source)

        val bundle = manager.active()
        assertEquals("active", bundle.directory)
        assertEquals("Unit Test Bundle", bundle.name)
        assertEquals("1.0.0", bundle.version.raw)
        assertTrue(bundle.hasFile(ModelFile.MODEL))
    }

    @Test
    fun `checksum mismatch throws during initialization`() {
        val assets = buildBundleAssets("active", validChecksums = false)
        assertThrows(IllegalStateException::class.java) {
            ModelManager.createForTest(FakeAssetSource(assets))
        }
    }

    @Test
    fun `can toggle between active and previous slots`() {
        val assetMap = mutableMapOf<String, ByteArray>()
        assetMap.putAll(buildBundleAssets("active", validChecksums = true))
        assetMap.putAll(buildBundleAssets("previous", validChecksums = true))

        val source = FakeAssetSource(assetMap)
        assertEquals(setOf("active", "previous"), source.list("models").toSet())

        val manager = ModelManager.createForTest(source)
        assertEquals(BundleSlot.ACTIVE, manager.currentSlot())

        val switched = manager.setActiveSlot(BundleSlot.PREVIOUS)
        assertTrue(switched)
        assertEquals("previous", manager.active().directory)
        assertEquals(BundleSlot.PREVIOUS, manager.currentSlot())

        val secondSwitch = manager.setActiveSlot(BundleSlot.PREVIOUS)
        assertFalse(secondSwitch)
    }

    private fun buildBundleAssets(directory: String, validChecksums: Boolean): Map<String, ByteArray> {
        val base = "models/$directory"
        val rawFiles = mapOf(
            "$base/model.tflite" to "model".toByteArray(),
            "$base/labels.json" to "{}".toByteArray(),
            "$base/preprocess.json" to "{}".toByteArray(),
            "$base/postprocess.json" to "{}".toByteArray(),
            "$base/model_metadata.json" to "{}".toByteArray(),
            "$base/coupon_patterns.txt" to "store:0,0,1,1".toByteArray()
        )

        val manifestFiles = mapOf(
            "model" to "$base/model.tflite",
            "labels" to "$base/labels.json",
            "preprocess" to "$base/preprocess.json",
            "postprocess" to "$base/postprocess.json",
            "metadata" to "$base/model_metadata.json",
            "patterns" to "$base/coupon_patterns.txt"
        )

        val checksums = manifestFiles.mapValues { (_, pathKey) ->
            sha256(rawFiles[pathKey] ?: error("Missing file for $pathKey"))
        }.toMutableMap()
        if (!validChecksums) {
            checksums["model"] = "deadbeef"
        }

        val manifest = """
            {
              "name": "Unit Test Bundle",
              "version": "1.0.0",
              "files": {
                "model": "model.tflite",
                "labels": "labels.json",
                "preprocess": "preprocess.json",
                "postprocess": "postprocess.json",
                "metadata": "model_metadata.json",
                "patterns": "coupon_patterns.txt"
              },
              "checksums": {
                "model": "${checksums["model"]}",
                "labels": "${checksums["labels"]}",
                "preprocess": "${checksums["preprocess"]}",
                "postprocess": "${checksums["postprocess"]}",
                "metadata": "${checksums["metadata"]}",
                "patterns": "${checksums["patterns"]}"
              }
            }
        """.trimIndent().toByteArray()

        val manifestEntry = mapOf("$base/manifest.json" to manifest)

        return rawFiles + manifestEntry
    }

    private fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content)
        return hash.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }


    private class FakeAssetSource(
        private val assets: Map<String, ByteArray>
    ) : ModelManager.AssetSource {
        override fun list(path: String): List<String> {
            val prefix = if (path.isEmpty()) "" else "$path/"
            return assets.keys
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .map { key -> key.substringBefore('/') }
                .distinct()
        }

        override fun open(path: String): InputStream {
            val data = assets[path] ?: throw IllegalArgumentException("Missing asset: $path")
            return ByteArrayInputStream(data)
        }

        override fun exists(path: String): Boolean = assets.containsKey(path)
    }
}

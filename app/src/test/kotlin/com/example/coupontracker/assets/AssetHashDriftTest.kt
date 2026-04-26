package com.example.coupontracker.assets

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pin the SHA-256 of every file the Mac harness reads. If anyone changes one
 * of these without updating the pinned hash, the build breaks. Pinned hashes
 * live next to this test in src/test/resources/asset_hashes.txt.
 */
class AssetHashDriftTest {

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `pinned asset hashes match`() {
        val golden = File("src/test/resources/asset_hashes.txt").readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { it.split("  ", limit = 2).let { (h, p) -> p to h } }
        for ((path, expected) in golden) {
            val f = File("src/main/$path")
            val actual = sha256(f)
            assertEquals("Asset $path drifted from pinned hash", expected, actual)
        }
    }
}

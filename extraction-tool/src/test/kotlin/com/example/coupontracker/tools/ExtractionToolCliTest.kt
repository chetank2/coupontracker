package com.example.coupontracker.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.imageio.ImageIO
import java.awt.image.BufferedImage

class ExtractionToolCliTest {

    @Test
    fun `preprocess subcommand emits ARGB IntArray and dimensions as JSON`() {
        // Build a 4x4 red PNG into a byte array.
        val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 4) for (x in 0 until 4) img.setRGB(x, y, 0xFFFF0000.toInt())
        val pngBytes = ByteArrayOutputStream().apply { ImageIO.write(img, "png", this) }.toByteArray()

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("preprocess", "--stdin"),
            stdin = ByteArrayInputStream(pngBytes),
            stdout = PrintStream(out),
        )

        val json = out.toString()
        // ImagePreprocessorCore upscales to minDimension (800) when input is smaller.
        assert(json.contains("\"width\":800")) { "got: $json" }
        assert(json.contains("\"height\":800")) { "got: $json" }
        assert(json.contains("\"sha256\":")) { "got: $json" }
    }

    @Test
    fun `parse subcommand returns canonical JSON for known model output`() {
        val raw = """
            prose before
            {"storeName":"Acme","description":"Flat off","couponCode":"X1","expiryDate":"2025-01-01","storeNameSource":"ocr","storeNameEvidence":[],"needsAttention":false,"extra":"drop"}
            prose after
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("parse", "--stdin"),
            stdin = ByteArrayInputStream(raw.toByteArray()),
            stdout = PrintStream(out),
        )

        val parsed = out.toString()
        assert(parsed.contains("\"storeName\":\"Acme\"")) { "got: $parsed" }
        assert(parsed.contains("\"redeemCode\":\"X1\"")) { "got: $parsed" }
        assertFalse(parsed.contains("couponCode"))
        assertFalse(parsed.contains("extra"))
    }

    @Test
    fun `parse subcommand unwraps first object from array output`() {
        val raw = """
            [{"storeName":"Kapiva","description":"Flat off","redeemCode":"KAPW1M3LAfAh5e","expiryDate":"2025-05-31","storeNameSource":"ocr","storeNameEvidence":["Kapiva"],"needsAttention":false,"extra":"drop"}]
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("parse", "--stdin"),
            stdin = ByteArrayInputStream(raw.toByteArray()),
            stdout = PrintStream(out),
        )

        val parsed = out.toString()
        assert(parsed.contains("\"storeName\":\"Kapiva\"")) { "got: $parsed" }
        assert(parsed.contains("\"redeemCode\":\"KAPW1M3LAfAh5e\"")) { "got: $parsed" }
        assertFalse(parsed.contains("extra"))
        assertFalse(parsed.trim().startsWith("["))
    }

    @Test
    fun `prompt subcommand renders deterministic text from given OCR JSON`() {
        val ocrJson = """{"text":"GET 40% OFF on Acme","tiles":[]}"""

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("prompt", "--stdin"),
            stdin = ByteArrayInputStream(ocrJson.toByteArray()),
            stdout = PrintStream(out),
        )

        val prompt = out.toString()
        assert(prompt.isNotBlank())
        assert(prompt.contains("Acme")) { "Expected 'Acme' in prompt, got: $prompt" }
        assert(prompt.contains("<|im_start|>system"))
        assert(prompt.contains("<|im_start|>assistant"))
    }

    @Test
    fun `prompt subcommand threads OCR tile data through PromptBuilder`() {
        val ocrJson = """{"text":"Kapiva","tiles":[{"text":"Kapiva","left":100,"top":50,"right":200,"bottom":80,"confidence":0.95}]}"""

        val out = ByteArrayOutputStream()
        ExtractionToolCli.main(
            args = arrayOf("prompt", "--stdin"),
            stdin = ByteArrayInputStream(ocrJson.toByteArray()),
            stdout = PrintStream(out),
        )

        val prompt = out.toString()
        assert(prompt.isNotBlank())
        assert(prompt.contains("Kapiva")) { "Expected 'Kapiva' in prompt, got: $prompt" }
        assert(prompt.contains("<|im_start|>system"))
    }
}

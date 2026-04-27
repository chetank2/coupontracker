package com.example.coupontracker.tools

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.ocr.BoundingBox
import com.example.coupontracker.ocr.OcrResultProcessor
import com.example.coupontracker.preprocessing.ImagePreprocessorCore
import com.example.coupontracker.preprocessing.PreprocessConfig
import com.example.coupontracker.prompt.PromptBuilder
import com.example.coupontracker.schema.CouponSchema
import java.io.InputStream
import java.io.PrintStream
import java.security.MessageDigest
import javax.imageio.ImageIO
import org.json.JSONArray
import org.json.JSONObject

object ExtractionToolCli {

    @JvmStatic
    fun main(args: Array<String>) = main(args, System.`in`, System.out)

    fun main(args: Array<String>, stdin: InputStream, stdout: PrintStream) {
        when (args.firstOrNull()) {
            "preprocess" -> preprocess(stdin, stdout)
            "parse" -> parse(stdin, stdout)
            "prompt" -> renderPrompt(stdin, stdout)
            else -> {
                stdout.println("usage: ExtractionToolCli {preprocess|parse|prompt} [--stdin]")
                throw IllegalArgumentException("unknown subcommand: ${args.firstOrNull()}")
            }
        }
    }

    private fun preprocess(stdin: InputStream, stdout: PrintStream) {
        val img = ImageIO.read(stdin) ?: error("could not decode image from stdin")
        val w = img.width; val h = img.height
        val pixels = IntArray(w * h)
        img.getRGB(0, 0, w, h, pixels, 0, w)
        val out = ImagePreprocessorCore(PreprocessConfig.DEFAULT).preprocess(pixels, w, h)
        val sha = sha256OfPixels(out.pixels)
        stdout.println("""{"width":${out.width},"height":${out.height},"sha256":"$sha"}""")
    }

    private fun parse(stdin: InputStream, stdout: PrintStream) {
        val raw = stdin.readBytes().toString(Charsets.UTF_8)
        val json = extractJsonPayload(raw) ?: raw.trim()
        stdout.println(CouponJsonContract.enforce(json))
    }

    private fun renderPrompt(stdin: InputStream, stdout: PrintStream) {
        val input = stdin.readBytes().toString(Charsets.UTF_8).trim()
        val payload = runCatching { JSONObject(input) }.getOrNull()
        val ocrText = payload?.optString("text") ?: input
        val tilesArray = payload?.optJSONArray("tiles") ?: JSONArray()
        val tiles = (0 until tilesArray.length()).map { i ->
            val t = tilesArray.getJSONObject(i)
            OcrResultProcessor.OcrTile(
                text = t.optString("text", ""),
                bounds = BoundingBox(
                    left = t.optInt("left", 0),
                    top = t.optInt("top", 0),
                    right = t.optInt("right", 0),
                    bottom = t.optInt("bottom", 0)
                ),
                confidence = t.optDouble("confidence", 1.0).toFloat()
            )
        }
        val v2Enabled = payload?.optBoolean("v2Enabled", false) == true
        val result = PromptBuilder(
            schema = CouponSchema.SCHEMA,
            isV2Enabled = { v2Enabled }
        ).build(ocrText, tiles)
        stdout.print(result.prompt)
    }

    private fun sha256OfPixels(pixels: IntArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(pixels.size * 4)
        for (p in pixels) buf.putInt(p)
        md.update(buf.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonPayload(text: String): String? {
        val objectStart = text.indexOf('{')
        val arrayStart = text.indexOf('[')
        val start = when {
            objectStart < 0 -> arrayStart
            arrayStart < 0 -> objectStart
            else -> minOf(objectStart, arrayStart)
        }
        if (start < 0) return null
        val open = text[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == open -> depth++
                !inString && ch == close -> {
                    depth--
                    if (depth == 0) return firstObject(text.substring(start, i + 1))
                }
            }
        }
        return null
    }

    private fun firstObject(json: String): String {
        if (json.trimStart().startsWith("{")) return json
        val array = JSONArray(json)
        val first = array.optJSONObject(0) ?: return json
        return first.toString()
    }
}

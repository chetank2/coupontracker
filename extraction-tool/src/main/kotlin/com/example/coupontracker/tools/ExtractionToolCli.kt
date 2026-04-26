package com.example.coupontracker.tools

import com.example.coupontracker.contract.CouponJsonContract
import com.example.coupontracker.preprocessing.ImagePreprocessorCore
import com.example.coupontracker.preprocessing.PreprocessConfig
import com.example.coupontracker.schema.CouponSchema
import com.example.coupontracker.schema.PromptGenerator
import java.io.InputStream
import java.io.PrintStream
import java.security.MessageDigest
import javax.imageio.ImageIO
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
        val json = extractJsonObject(raw) ?: raw.trim()
        stdout.println(CouponJsonContract.enforce(json))
    }

    private fun renderPrompt(stdin: InputStream, stdout: PrintStream) {
        val input = stdin.readBytes().toString(Charsets.UTF_8).trim()
        val ocrText = runCatching {
            JSONObject(input).optString("text")
        }.getOrDefault(input)
        stdout.print(PromptGenerator.generateCompletePrompt(CouponSchema.SCHEMA, ocrText))
    }

    private fun sha256OfPixels(pixels: IntArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(pixels.size * 4)
        for (p in pixels) buf.putInt(p)
        md.update(buf.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                !inString && ch == '{' -> depth++
                !inString && ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

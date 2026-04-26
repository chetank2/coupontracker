package com.example.coupontracker.tools

import com.example.coupontracker.preprocessing.ImagePreprocessorCore
import com.example.coupontracker.preprocessing.PreprocessConfig
import java.io.InputStream
import java.io.PrintStream
import java.security.MessageDigest
import javax.imageio.ImageIO

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
        // Wired in Task 1.5.
        TODO("parse subcommand not yet implemented")
    }

    private fun renderPrompt(stdin: InputStream, stdout: PrintStream) {
        // Wired in Task 1.5.
        TODO("prompt subcommand not yet implemented")
    }

    private fun sha256OfPixels(pixels: IntArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = java.nio.ByteBuffer.allocate(pixels.size * 4)
        for (p in pixels) buf.putInt(p)
        md.update(buf.array())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

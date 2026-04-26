package com.example.coupontracker.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreprocessorCoreTest {

    @Test
    fun `resize is a no-op when input is already within min and max bounds`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(1000 * 500) { 0xFF000000.toInt() }  // black 1000x500
        val out = core.preprocess(src, width = 1000, height = 500)
        // 1000x500 has max(w,h)=1000 ≤ 1600 (no downscale)
        // and is not the case that BOTH dims are < 800 (1000 ≥ 800; no upscale)
        // So output dims should equal input dims.
        assertEquals(1000, out.width)
        assertEquals(500, out.height)
    }

    @Test
    fun `resize upscales when both dimensions are below minDimension`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(100 * 50) { 0xFF000000.toInt() }  // black 100x50
        val out = core.preprocess(src, width = 100, height = 50)
        // Both 100 < 800 and 50 < 800: scaleFactor = 800 / max(100,50) = 8
        // Output should be 800x400, preserving 2:1 aspect ratio.
        assertEquals(800, out.width)
        assertEquals(400, out.height)
    }

    @Test
    fun `preprocess output is deterministic for identical input`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(800 * 600) { i -> (0xFF000000.toInt()) or (i and 0xFFFFFF) }
        val a = core.preprocess(src.copyOf(), 800, 600)
        val b = core.preprocess(src.copyOf(), 800, 600)
        assertEquals(a.pixels.toList(), b.pixels.toList())
    }

    @Test
    fun `resize downscales when max dimension exceeds maxDimension`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        // 2400x1200: max(w,h) = 2400 > 1600. scaleFactor = 1600/2400 = 0.6667
        // newW = (2400 * 0.6667).toInt() = 1600
        // newH = (1200 * 0.6667).toInt() = 800
        val src = IntArray(2400 * 1200) { 0xFF000000.toInt() }
        val out = core.preprocess(src, width = 2400, height = 1200)
        assertEquals(1600, out.width)
        assertEquals(800, out.height)
    }

    @Test
    fun `grayscale stage maps pure red to expected luminance`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        // 1000x500 pure red, opaque. Stays in comfort zone (no resize).
        // Pipeline: grayscale → contrast(2x-160) → threshold(3x-200)
        // Grayscale: lum = (0.213*255 + 0.715*0 + 0.072*0 + 0.5).toInt() = 54
        // After contrast: clamp(54*2 - 160 + 0.5, 0, 255) = clamp(-51.5, 0, 255).toInt() = 0
        // After threshold: clamp(0*3 - 200 + 0.5, 0, 255).toInt() = 0
        // So expected output channel = 0; alpha preserved (0xFF).
        val red = 0xFFFF0000.toInt()
        val src = IntArray(1000 * 500) { red }
        val out = core.preprocess(src, 1000, 500)
        // Sample one pixel, since the input is uniform.
        val p = out.pixels[0]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        assertEquals("alpha preserved", 0xFF, a)
        assertEquals("red driven to zero by contrast+threshold", 0, r)
        assertEquals(0, g)
        assertEquals(0, b)
    }
}

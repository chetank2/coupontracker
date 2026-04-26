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
}

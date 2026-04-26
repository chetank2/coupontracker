package com.example.coupontracker.preprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class ImagePreprocessorCoreTest {

    @Test
    fun `resize keeps aspect ratio and respects max dimension`() {
        val core = ImagePreprocessorCore(PreprocessConfig.DEFAULT)
        val src = IntArray(100 * 50) { 0xFF000000.toInt() }  // black 100x50
        val out = core.preprocess(src, width = 100, height = 50)
        assertEquals(out.width <= PreprocessConfig.DEFAULT.maxDimension, true)
        assertEquals(out.height <= PreprocessConfig.DEFAULT.maxDimension, true)
        // No upscaling for already-small images:
        assertEquals(100, out.width)
        assertEquals(50, out.height)
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

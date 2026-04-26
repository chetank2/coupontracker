package com.example.coupontracker.preprocessing

import kotlin.math.max

/**
 * Pure-JVM image preprocessing core — no android.graphics dependencies.
 *
 * Implements the same pipeline as [com.example.coupontracker.util.ImagePreprocessor]:
 *   1. Bilinear resize to [PreprocessConfig.maxDimension] / [PreprocessConfig.minDimension]
 *      (matches Android's Bitmap.createScaledBitmap(..., filterBitmap = true))
 *   2. Grayscale pass — Android ColorMatrix.setSaturation(0f) coefficients:
 *      R_out = G_out = B_out = 0.213*R + 0.715*G + 0.072*B  (ITU-R / AOSP)
 *   3. Contrast pass — ColorMatrix [2,0,0,0,-160 / 0,2,0,0,-160 / 0,0,2,0,-160 / 0,0,0,1,0]
 *   4. Threshold pass — ColorMatrix [3,0,0,0,-200 / 0,3,0,0,-200 / 0,0,3,0,-200 / 0,0,0,1,0]
 *
 * Pixels are represented as ARGB packed ints (same encoding as android.graphics.Color / Bitmap.ARGB_8888).
 */
class ImagePreprocessorCore(private val config: PreprocessConfig) {

    /**
     * Preprocessed image output.
     *
     * [pixels] are ARGB-packed ints, row-major, [width] pixels per row.
     */
    data class Output(val pixels: IntArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean {
            if (other !is Output) return false
            return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
        }
        override fun hashCode(): Int = (width * 31 + height) * 31 + pixels.contentHashCode()
    }

    /**
     * Run the full preprocessing pipeline.
     *
     * @param src   ARGB-packed int pixels, row-major (size must equal width × height)
     * @param width  source image width in pixels
     * @param height source image height in pixels
     * @return preprocessed output
     */
    fun preprocess(src: IntArray, width: Int, height: Int): Output {
        require(src.size == width * height) { "src.size=${src.size} != width($width)*height($height)" }

        // 1. Resize (bilinear, matching Bitmap.createScaledBitmap(..., filterBitmap=true))
        val (newW, newH) = targetDimensions(width, height)
        val resized = if (newW == width && newH == height) src.copyOf()
                      else resizeBilinear(src, width, height, newW, newH)

        // 2. Enhanced processing pipeline (mirrors applyEnhancedProcessing):
        //    a) grayscale  b) contrast (×2 −160)  c) threshold (×3 −200)
        val grayscale  = applyGrayscale(resized, newW, newH)
        val contrast   = applyColorMatrix(grayscale, newW, newH, scale = 2f, translate = -160f)
        val threshold  = applyColorMatrix(contrast,  newW, newH, scale = 3f, translate = -200f)

        return Output(threshold, newW, newH)
    }

    // -------------------------------------------------------------------------
    // Resize
    // -------------------------------------------------------------------------

    /**
     * Compute target (width, height) applying the same rules as ImagePreprocessor.resizeBitmap:
     *  - Scale DOWN if max(w,h) > maxDimension
     *  - Scale UP   if both w < minDimension AND h < minDimension
     *  - Otherwise, return unchanged
     */
    internal fun targetDimensions(w: Int, h: Int): Pair<Int, Int> {
        var newW = w
        var newH = h

        // Scale down large images (matches: if (width > MAX || height > MAX))
        if (w > config.maxDimension || h > config.maxDimension) {
            val scaleFactor = config.maxDimension.toFloat() / max(w, h)
            newW = (w * scaleFactor).toInt()
            newH = (h * scaleFactor).toInt()
        }

        // Scale up small images (matches: if (width < MIN && height < MIN))
        if (w < config.minDimension && h < config.minDimension) {
            val scaleFactor = config.minDimension.toFloat() / max(w, h)
            newW = (w * scaleFactor).toInt()
            newH = (h * scaleFactor).toInt()
        }

        return newW to newH
    }

    /**
     * Bilinear interpolation resize.
     *
     * Matches Android's Bitmap.createScaledBitmap with filterBitmap=true, which uses
     * bilinear sampling with the source pixel centres mapped to (0.5, 0.5)-anchored UV space.
     *
     * Formula:
     *   srcX = (dstX + 0.5) * (sw / dw) - 0.5
     *   srcY = (dstY + 0.5) * (sh / dh) - 0.5
     * then bilinearly interpolate the four surrounding source pixels per channel.
     */
    private fun resizeBilinear(
        src: IntArray, sw: Int, sh: Int,
        dw: Int, dh: Int,
    ): IntArray {
        val out = IntArray(dw * dh)
        val xRatio = sw.toDouble() / dw
        val yRatio = sh.toDouble() / dh

        for (dy in 0 until dh) {
            val sy = (dy + 0.5) * yRatio - 0.5
            val sy0 = sy.toInt().coerceIn(0, sh - 1)
            val sy1 = (sy0 + 1).coerceIn(0, sh - 1)
            val fy = (sy - sy0).coerceIn(0.0, 1.0)
            val ify = 1.0 - fy

            for (dx in 0 until dw) {
                val sx = (dx + 0.5) * xRatio - 0.5
                val sx0 = sx.toInt().coerceIn(0, sw - 1)
                val sx1 = (sx0 + 1).coerceIn(0, sw - 1)
                val fx = (sx - sx0).coerceIn(0.0, 1.0)
                val ifx = 1.0 - fx

                val p00 = src[sy0 * sw + sx0]
                val p10 = src[sy0 * sw + sx1]
                val p01 = src[sy1 * sw + sx0]
                val p11 = src[sy1 * sw + sx1]

                val a = bilinear(alphaOf(p00), alphaOf(p10), alphaOf(p01), alphaOf(p11), ifx, fx, ify, fy)
                val r = bilinear(redOf(p00),   redOf(p10),   redOf(p01),   redOf(p11),   ifx, fx, ify, fy)
                val g = bilinear(greenOf(p00),  greenOf(p10),  greenOf(p01),  greenOf(p11),  ifx, fx, ify, fy)
                val b = bilinear(blueOf(p00),   blueOf(p10),   blueOf(p01),   blueOf(p11),   ifx, fx, ify, fy)

                out[dy * dw + dx] = argb(a, r, g, b)
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Color transforms
    // -------------------------------------------------------------------------

    /**
     * Grayscale pass matching Android's ColorMatrix.setSaturation(0f).
     *
     * AOSP setSaturation coefficients (ITU-R BT.601 approximation used by Android):
     *   hR = 0.213f, hG = 0.715f, hB = 0.072f
     *
     * When saturation = 0:
     *   R_out = G_out = B_out = hR*R + hG*G + hB*B
     *   A_out = A_in (alpha row is identity)
     */
    private fun applyGrayscale(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        for (i in src.indices) {
            val p = src[i]
            val r = redOf(p)
            val g = greenOf(p)
            val b = blueOf(p)
            val lum = (0.213f * r + 0.715f * g + 0.072f * b + 0.5f).toInt().coerceIn(0, 255)
            out[i] = argb(alphaOf(p), lum, lum, lum)
        }
        return out
    }

    /**
     * Applies a diagonal color matrix (same for R, G, B channels) of the form:
     *
     *   out_channel = clamp(in_channel * scale + translate, 0, 255)
     *   out_alpha   = in_alpha  (alpha row is identity)
     *
     * This matches Android's ColorMatrix rows used in applyEnhancedProcessing and
     * applyStandardProcessing:
     *   Contrast:   scale=2f,  translate=-160f   → [2,0,0,0,-160 / …/ 0,0,0,1,0]
     *   Threshold:  scale=3f,  translate=-200f   → [3,0,0,0,-200 / …/ 0,0,0,1,0]
     *
     * Android's ColorMatrixColorFilter operates in float space then clamps to [0,255]
     * before writing back to the 8-bit channel, so we replicate that here.
     */
    private fun applyColorMatrix(src: IntArray, w: Int, h: Int, scale: Float, translate: Float): IntArray {
        val out = IntArray(w * h)
        for (i in src.indices) {
            val p = src[i]
            val r = transform(redOf(p),   scale, translate)
            val g = transform(greenOf(p), scale, translate)
            val b = transform(blueOf(p),  scale, translate)
            out[i] = argb(alphaOf(p), r, g, b)
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private inline fun transform(channel: Int, scale: Float, translate: Float): Int =
        (channel * scale + translate).toInt().coerceIn(0, 255)

    private inline fun bilinear(
        v00: Int, v10: Int, v01: Int, v11: Int,
        ifx: Double, fx: Double, ify: Double, fy: Double,
    ): Int {
        val top    = ifx * v00 + fx * v10
        val bottom = ifx * v01 + fx * v11
        return (ify * top + fy * bottom + 0.5).toInt().coerceIn(0, 255)
    }

    private inline fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF
    private inline fun redOf(argb: Int): Int   = (argb ushr 16) and 0xFF
    private inline fun greenOf(argb: Int): Int = (argb ushr  8) and 0xFF
    private inline fun blueOf(argb: Int): Int  =  argb          and 0xFF

    private inline fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}

package com.example.coupontracker.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Utility helpers to compute simple perceptual hashes and signatures for coupon imagery.
 */
object ImageFingerprintUtils {
    private const val TAG = "ImageFingerprintUtils"

    fun computePerceptualHash(bitmap: Bitmap): String? {
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
            val pixels = IntArray(64)
            scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
            var sum = 0
            val grayValues = IntArray(64)
            for (i in pixels.indices) {
                val color = pixels[i]
                val value = (Color.red(color) * 30 + Color.green(color) * 59 + Color.blue(color) * 11) / 100
                grayValues[i] = value
                sum += value
            }
            val average = sum / 64.0
            val bits = StringBuilder(64)
            for (value in grayValues) {
                bits.append(if (value >= average) '1' else '0')
            }
            val hex = StringBuilder(16)
            for (index in 0 until bits.length step 4) {
                val chunk = bits.substring(index, index + 4)
                hex.append(Integer.toHexString(chunk.toInt(2)))
            }
            if (scaled != bitmap && !scaled.isRecycled) {
                scaled.recycle()
            }
            hex.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute perceptual hash", e)
            null
        }
    }

    fun computeImageSignature(bitmap: Bitmap): String? {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            val bytes = outputStream.toByteArray()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compute image signature", e)
            null
        }
    }
}

package com.example.coupontracker.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelAssetIntegrityJvmTest {

    @Test
    fun `ensureMinSize returns actual bytes when threshold met`() {
        val bytes = ModelAssetIntegrity.ensureMinSize(
            assetName = "stage1_coupon_detector.tflite",
            actualBytes = 1_500_000,
            minExpectedBytes = 1_000_000,
            remediationHint = "replace"
        )

        assertEquals(1_500_000, bytes)
    }

    @Test
    fun `ensureMinSize throws when asset smaller than threshold`() {
        val error = assertFailsWith<IllegalArgumentException> {
            ModelAssetIntegrity.ensureMinSize(
                assetName = "stage1_coupon_detector.tflite",
                actualBytes = 792,
                minExpectedBytes = 1_000_000,
                remediationHint = "replace"
            )
        }

        assertTrue(error.message!!.contains("stage1_coupon_detector.tflite"))
        assertTrue(error.message!!.contains("792"))
    }
}

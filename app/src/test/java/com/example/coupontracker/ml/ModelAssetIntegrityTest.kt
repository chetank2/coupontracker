package com.example.coupontracker.ml

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModelAssetIntegrityTest {

    @Test
    fun `ensureMinSize succeeds when asset meets threshold`() {
        ModelAssetIntegrity.ensureMinSize(
            assetName = "stage1_coupon_detector.tflite",
            actualBytes = 1_500_000,
            minExpectedBytes = 1_000_000,
            remediationHint = "replace"
        )
    }

    @Test
    fun `ensureMinSize throws when asset is smaller than threshold`() {
        val error = assertFailsWith<IllegalStateException> {
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


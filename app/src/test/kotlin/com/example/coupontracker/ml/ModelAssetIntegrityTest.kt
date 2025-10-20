package com.example.coupontracker.ml

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelAssetIntegrityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun ensureAssetMinSize_throwsWhenAssetMissing() {
        assertThrows(IllegalStateException::class.java) {
            ModelAssetIntegrity.ensureAssetMinSize(context, "missing.bin", 1, "missing")
        }
    }

    @Test
    fun ensureAssetMinSize_throwsWhenAssetTooSmall() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelAssetIntegrity.ensureAssetMinSize(context, "models/small_model.bin", 8, "test-model")
        }
    }

    @Test
    fun ensureAssetMinSize_succeedsWhenAssetLargeEnough() {
        ModelAssetIntegrity.ensureAssetMinSize(context, "models/valid_model.bin", 8, "test-model")
    }
}

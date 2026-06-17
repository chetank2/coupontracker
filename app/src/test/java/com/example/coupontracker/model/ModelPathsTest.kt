package com.example.coupontracker.model

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ModelPathsTest {

    @Test
    fun `gemma vision is not installed when task file is missing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ModelPaths.gemmaDir(context).deleteRecursively()
        ModelPaths.gemmaDir(context).mkdirs()
        File(ModelPaths.gemmaDir(context), ".vision_verified").writeText("ok")

        val status = ModelPaths.getGemmaVisionInstallStatus(context)

        assertFalse(status.installed)
        assertTrue(status.message.contains("task file is missing", ignoreCase = true))
    }

    @Test
    fun `gemma vision is not installed when task file is partial`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = ModelPaths.gemmaDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        File(dir, ".vision_verified").writeText("ok")
        File(dir, ModelPaths.GEMMA_VISION_MODEL_FILE).writeBytes(ByteArray(1024))

        val status = ModelPaths.getGemmaVisionInstallStatus(context)

        assertFalse(status.installed)
        assertTrue(status.message.contains("incomplete", ignoreCase = true))
    }
}

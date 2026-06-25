package com.example.coupontracker.extraction.vision

import android.graphics.Bitmap
import com.example.coupontracker.data.preferences.SecurePreferencesManager
import com.example.coupontracker.extraction.model.ModelSelector
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisionFieldStateExtractorTest {

    @Test
    fun `extract skips before model selection when Gemma verifier is disabled`() = runBlocking {
        val prefs = mockk<SecurePreferencesManager>()
        every { prefs.isGemmaVisionVerifierEnabled() } returns false
        val selector = mockk<ModelSelector>()
        val extractor = VisionFieldStateExtractor(
            modelSelector = selector,
            securePreferencesManager = prefs,
            parser = VisionFieldJsonParser()
        )

        val result = extractor.extract(
            bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888),
            ocrText = "SAVE100"
        )

        assertNull(result)
        verify(exactly = 0) { selector.select(any()) }
    }
}

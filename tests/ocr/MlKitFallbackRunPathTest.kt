package com.example.coupontracker.util

import com.example.coupontracker.ocr.TesseractOcrEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitFallbackRunPathTest {

    @Test
    fun `run path includes diagnostics when available`() {
        val stats = TesseractOcrEngine.InitStats(
            attempt = 2,
            success = false,
            assetChecksum = "aabbccdd",
            installedChecksum = "ffeeddccbbaa",
            copiedTrainedData = true,
            errorMessage = "init failed",
            nativeLogTail = "native error"
        )

        val context = MlKitFallbackContext(
            reason = "progressive_exception/model_based_exception",
            cause = IllegalStateException("tesseract unavailable"),
            initStats = stats,
            ocrReady = false,
            attemptedEngines = listOf("TESSERACT", "PROGRESSIVE", "MODEL_BASED")
        )

        val runPath = buildMlKitFallbackRunPath(context)

        assertEquals("OCR_FIRST", runPath.strategy)
        assertEquals("MLKIT", runPath.final)
        assertEquals(listOf("TESSERACT", "PROGRESSIVE", "MODEL_BASED"), runPath.tried)
        assertTrue(runPath.reasons.contains("progressive_exception/model_based_exception"))
        assertTrue(runPath.reasons.any { it.startsWith("error:") })
        assertTrue(runPath.reasons.any { it.startsWith("tesseract:") })
        assertTrue(runPath.reasons.any { it.startsWith("traineddata:") })
        assertTrue(runPath.reasons.contains("attempt:2"))
        assertTrue(runPath.reasons.contains("copied:true"))
    }
}
